import os, json, uuid, tempfile, boto3
from typing import List, Tuple, Dict
from src import db_mysql as db
from src import fingerprint

s3 = boto3.client("s3")

BEST_HIST_LIMIT = 200  # bins top por canción ganadora

def _download_from_s3(bucket: str, key: str, local_path: str):
    s3.download_file(bucket, key, local_path)

def _put_json_to_s3(bucket: str, key: str, payload: dict):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    s3.put_object(Bucket=bucket, Key=key, Body=body, ContentType="application/json")

def _presign(bucket: str, key: str, expires: int) -> str:
    return s3.generate_presigned_url(
        "get_object",
        Params={"Bucket": bucket, "Key": key},
        ExpiresIn=expires,
    )

def handler(event, context):
    # ---- Entradas (por evento o ENV) ----
    upload_bucket = event.get("s3_bucket") or os.environ.get("UPLOAD_BUCKET")
    upload_key    = event.get("s3_key")
    request_id    = event.get("request_id") or str(uuid.uuid4())

    result_bucket = os.environ.get("RESULT_BUCKET") or upload_bucket
    results_prefix = os.environ.get("RESULTS_PREFIX", "results/").rstrip("/")

    presign_ttl = int(os.environ.get("PRESIGN_EX_SEC", "0"))

    if not upload_bucket or not upload_key:
        return {"ok": False, "reason": "missing_s3_input"}

    # ---- Descarga del audio a /tmp ----
    local_wav = f"/tmp/{request_id}.wav"
    _download_from_s3(upload_bucket, upload_key, local_wav)

    conn = db.connect()
    try:
        # 1) Fingerprints del clip (hash, t_clip)
        clip_hashes: List[Tuple[str, int]] = fingerprint.audio_to_hashes(local_wav)
        if not clip_hashes:
            result = {"ok": False, "reason": "no_hashes_from_clip", "clip": upload_key}
        else:
            # 2) Tabla temporal con hashes del clip
            with conn.cursor() as cur:
                cur.execute("DROP TEMPORARY TABLE IF EXISTS tmp_clip_hashes")
                cur.execute("""
                    CREATE TEMPORARY TABLE tmp_clip_hashes (
                        hash   VARCHAR(64) NOT NULL,
                        t_clip INT NOT NULL
                    ) ENGINE=Memory
                """)
                cur.executemany(
                    "INSERT INTO tmp_clip_hashes (hash, t_clip) VALUES (%s, %s)",
                    clip_hashes,
                )
            conn.commit()

            # 3) Mejor offset por canción y totales (igual a tu identify.py)
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
            totals_by_song_sql = """
                SELECT f.song_id, COUNT(*) AS matches_for_song
                FROM fingerprints f
                JOIN tmp_clip_hashes t ON t.hash = f.hash
                GROUP BY f.song_id
            """

            best_candidates: List[Tuple[str, int, int]] = []
            totals_map: Dict[str, int] = {}

            with conn.cursor() as cur:
                cur.execute(best_by_song_sql)
                for row in cur.fetchall():
                    song_id, offset_frames, matches_at_offset = row
                    best_candidates.append((str(song_id), int(offset_frames), int(matches_at_offset)))

                if not best_candidates:
                    result = {
                        "ok": False,
                        "reason": "no_db_matches",
                        "clip": upload_key,
                        "hashes": len(clip_hashes),
                    }
                else:
                    cur.execute(totals_by_song_sql)
                    for row in cur.fetchall():
                        song_id, matches_for_song = row
                        totals_map[str(song_id)] = int(matches_for_song)

            if best_candidates:
                best_song_id, best_offset_frames, best_matches_at_offset = best_candidates[0]
                song_votes_total = totals_map.get(best_song_id, 0)
                confidence = round(
                    (best_matches_at_offset / song_votes_total) if song_votes_total else 0.0, 4
                )

                # 4) Metadatos de la canción
                meta = db.get_song_meta(conn, best_song_id)
                if not meta:
                    result = {"ok": False, "reason": "meta_not_found", "song_id": best_song_id}
                else:
                    song_id, title, artist, year, path, youtube_url, youtube_id = meta

                    # 5) Histograma para la canción ganadora (para la segunda Lambda)
                    with conn.cursor() as cur:
                        cur.execute(
                            f"""
                            SELECT (f.t_anchor - t.t_clip) AS offset_frames, COUNT(*) AS matches_at_offset
                            FROM fingerprints f
                            JOIN tmp_clip_hashes t ON t.hash = f.hash
                            WHERE f.song_id = %s
                            GROUP BY offset_frames
                            ORDER BY matches_at_offset DESC
                            LIMIT {BEST_HIST_LIMIT}
                            """,
                            (best_song_id,),
                        )
                        hist_rows = cur.fetchall()
                        histogram_top = [
                            {"offset": int(ofs), "count": int(cnt)} for (ofs, cnt) in hist_rows
                        ]

                    result = {
                        "ok": True,
                        "request_id": request_id,
                        "input": {"bucket": upload_bucket, "key": upload_key},
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
                        "confidence": confidence,
                        "clip_hashes": len(clip_hashes),
                        "histogram_top": histogram_top,
                    }

        # 6) Persistimos resultado en S3
        result_key = f"{results_prefix}/{request_id}/result.json"
        result_with_key = dict(result, result_s3_key=result_key)
        _put_json_to_s3(result_bucket, result_key, result_with_key)

        # 7) URL presign si lo pides
        if presign_ttl > 0:
            result_with_key["result_url"] = _presign(result_bucket, result_key, presign_ttl)

        return result_with_key

    finally:
        conn.close()
