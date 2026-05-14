from typing import Dict, List, Tuple, Optional
from collections import defaultdict

from src import db_mysql as db
from src import fingerprint


def identify_clip(conn, clip_path: str, debug_limit_hashes: Optional[int] = None) -> Dict:
    """
    Identifica un clip contra la BD de fingerprints en MySQL usando tabla temporal + JOIN.
    Retorna dict con metadatos y métricas.
    """
    # 1) Fingerprints del clip (hash, t_clip)
    clip_hashes: List[Tuple[str, int]] = fingerprint.audio_to_hashes(clip_path)
    if not clip_hashes:
        return {"ok": False, "reason": "no_hashes_from_clip", "clip": clip_path}

    if debug_limit_hashes:
        clip_hashes = clip_hashes[:debug_limit_hashes]

    # 2) Crear tabla temporal e insertar los hashes del clip
    #    Nota: tabla sin PRIMARY KEY para permitir duplicados (mismo hash con distintos t_clip)
    with conn.cursor() as cur:
        cur.execute("DROP TEMPORARY TABLE IF EXISTS tmp_clip_hashes")
        cur.execute("""
            CREATE TEMPORARY TABLE tmp_clip_hashes (
                hash    VARCHAR(64) NOT NULL,
                t_clip  INT NOT NULL
            ) ENGINE=Memory
        """)
        # Insert masivo
        cur.executemany(
            "INSERT INTO tmp_clip_hashes (hash, t_clip) VALUES (%s, %s)",
            clip_hashes
        )
    conn.commit()

    # 3) SQL: Votación por offsets (t_anchor - t_clip) y ganador global
    #    a) Mejor offset por canción + número de matches en ese offset
    best_by_song_sql = """
        SELECT
            f.song_id AS song_id,
            (f.t_anchor - t.t_clip) AS offset_frames,
            COUNT(*) AS matches_at_offset
        FROM fingerprints f
        JOIN tmp_clip_hashes t ON t.hash = f.hash
        GROUP BY f.song_id, offset_frames
        ORDER BY matches_at_offset DESC
    """
    #    b) Total de matches por canción (para calcular "confidence")
    totals_by_song_sql = """
        SELECT
            f.song_id AS song_id,
            COUNT(*) AS matches_for_song
        FROM fingerprints f
        JOIN tmp_clip_hashes t ON t.hash = f.hash
        GROUP BY f.song_id
    """

    # Ejecutar consultas
    best_candidates: List[Tuple[str, int, int]] = []  # (song_id, offset_frames, matches_at_offset)
    totals_map: Dict[str, int] = {}

    with conn.cursor() as cur:
        # a) mejores offsets por canción (ordenados por matches desc)
        cur.execute(best_by_song_sql)
        for row in cur.fetchall():
            song_id, offset_frames, matches_at_offset = row
            best_candidates.append((song_id, int(offset_frames), int(matches_at_offset)))

        if not best_candidates:
            return {
                "ok": False,
                "reason": "no_db_matches",
                "clip": clip_path,
                "hashes": len(clip_hashes),
            }

        # b) totales por canción
        cur.execute(totals_by_song_sql)
        for row in cur.fetchall():
            song_id, matches_for_song = row
            totals_map[str(song_id)] = int(matches_for_song)

    # 4) Elegir ganador: el primer best_candidate ya viene con mayor matches_at_offset
    best_song_id, best_offset_frames, best_matches_at_offset = best_candidates[0]
    song_votes_total = totals_map.get(str(best_song_id), 0)
    confidence = (best_matches_at_offset / song_votes_total) if song_votes_total else 0.0

    # 5) Metadatos de la canción
    meta = db.get_song_meta(conn, best_song_id)
    if not meta:
        return {"ok": False, "reason": "meta_not_found", "song_id": best_song_id}

    song_id, title, artist, year, path, youtube_url, youtube_id = meta

    return {
        "ok": True,
        "clip": clip_path,
        "song_id": song_id,
        "title": title,
        "artist": artist,
        "year": year,
        "path": path,
        "youtube_url": youtube_url,
        "youtube_id": youtube_id,
        "offset_frames": best_offset_frames,
        "matches_at_best_offset": best_matches_at_offset,
        "matches_for_song": song_votes_total,
        "confidence": round(confidence, 4),
        "clip_hashes": len(clip_hashes),
    }
