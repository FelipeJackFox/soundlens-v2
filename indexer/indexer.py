# src/indexer.py
import csv
import hashlib
import os
import re
import sys
import unicodedata
from typing import Dict, Iterable, List, Optional, Tuple
from urllib.parse import quote
from dotenv import load_dotenv

# ---- DB (Aurora/MySQL) ----
from src import db_mysql as db

# ---- Fingerprinting (se usa tu implementación si existe) ----
try:
    from src import fingerprint
    HAVE_FINGERPRINT = hasattr(fingerprint, "audio_to_hashes")
except Exception:
    HAVE_FINGERPRINT = False

def _fallback_fingerprint_file(path: str) -> List[Tuple[str, int]]:
    import numpy as np
    import librosa
    y, sr = librosa.load(path, sr=44100, mono=True)
    S = np.abs(librosa.stft(y, n_fft=2048, hop_length=1024))
    S_db = librosa.amplitude_to_db(S, ref=np.max)

    freq_bins, time_frames = S_db.shape
    bands = 5
    band_size = max(1, freq_bins // bands)

    peaks: List[Tuple[int, int]] = []
    for t in range(time_frames):
        for b in range(bands):
            start = b * band_size
            end = freq_bins if b == bands - 1 else (b + 1) * band_size
            sl = S_db[start:end, t]
            if sl.size == 0:
                continue
            f_rel = int(np.argmax(sl))
            f_idx = start + f_rel
            peaks.append((f_idx, t))

    fan_window = 15
    hashes: List[Tuple[str, int]] = []
    for i, (f1, t1) in enumerate(peaks):
        for j in range(i + 1, min(i + 1 + 64, len(peaks))):
            f2, t2 = peaks[j]
            dt = t2 - t1
            if 0 < dt <= fan_window:
                h = f"{f1}|{f2}|{dt}"
                hashes.append((h, t1))
    return hashes

def compute_fingerprints(path: str) -> List[Tuple[str, int]]:
    if HAVE_FINGERPRINT:
        return fingerprint.audio_to_hashes(path)
    return _fallback_fingerprint_file(path)


# ---------------- Utilities ----------------
load_dotenv()

# Requeridas (compatibilidad con manifest.csv)
CSV_REQUIRED = {"path", "title", "artist"}
# Opcionales extendidas para manifest2.csv
CSV_OPTIONAL = {"year", "youtube_url", "youtube_id", "song_id", "genre", "highlight", "song_path"}
CSV_ALL = CSV_REQUIRED | CSV_OPTIONAL

YT_RE = re.compile(r"(?:v=|/v/|/embed/|youtu\.be/|/shorts/)([A-Za-z0-9_-]{6,})", re.IGNORECASE)

def youtube_id_from_url(url: Optional[str]) -> Optional[str]:
    if not url:
        return None
    m = YT_RE.search(url)
    return m.group(1) if m else None

def norm_str(x: Optional[str]) -> Optional[str]:
    if x is None:
        return None
    x = str(x).strip()
    return x or None

def to_int_or_none(x: Optional[str]) -> Optional[int]:
    x = norm_str(x)
    if not x:
        return None
    try:
        return int(x)
    except Exception:
        return None

def parse_timecode_to_seconds(tc: Optional[str]) -> Optional[int]:
    tc = norm_str(tc)
    if not tc:
        return None
    parts = [p for p in tc.split(':') if p != '']
    try:
        if len(parts) == 2:
            m, s = map(int, parts)
            return m * 60 + s
        if len(parts) == 3:
            h, m, s = map(int, parts)
            return h * 3600 + m * 60 + s
    except Exception:
        return None
    return None

def add_time_param(url: Optional[str], seconds: Optional[int]) -> Optional[str]:
    if not url:
        return None
    if not seconds or seconds < 0:
        return url
    sep = '&' if '?' in url else '?'
    return f"{url}{sep}t={int(seconds)}"

def stable_song_id(title: Optional[str], artist: Optional[str], year: Optional[int], fallback_key: str) -> str:
    key = f"{norm_str(title) or ''}|{norm_str(artist) or ''}|{year if year is not None else ''}"
    if key == "||":
        key = fallback_key
    return hashlib.md5(key.encode("utf-8")).hexdigest()[:32]

def file_exists(path: str) -> bool:
    return os.path.isfile(path)

def guess_rel_path(p: str) -> str:
    return p

# ---------- S3 helpers ----------
_S3_BUCKET = os.getenv("S3_BUCKET", "").strip()
_S3_PREFIX = os.getenv("S3_PREFIX", "songs").strip().strip("/")

def _ascii_slug(text: str) -> str:
    if not text:
        return ""
    text = unicodedata.normalize("NFKD", text)
    text = "".join([c for c in text if not unicodedata.combining(c)])
    text = text.lower()
    out = []
    for ch in text:
        out.append(ch if ch.isalnum() else "_")
    s = "".join(out)
    while "__" in s:
        s = s.replace("__", "_")
    return s.strip("_")

def derive_s3_key(genre: Optional[str], local_path: Optional[str], song_path: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    """
    Devuelve (s3_key, s3_url) o (None, None) si falta bucket.
    Preferimos song_path si viene; si no, intentamos: audio_corpus/<GENERO>/<archivo> -> songs/<GENERO>/<archivo_sanitizado>.
    """
    if not _S3_BUCKET:
        return (None, None)

    # --- Caso 1: el CSV ya trae song_path (preferido) ---
    if song_path:
        # Normalizar para Windows
        sp = song_path.replace("\\", "/").lstrip("/")
        key = f"{_S3_PREFIX}/{sp}"
        url = f"https://{_S3_BUCKET}.s3.amazonaws.com/{quote(key)}"
        return key, url

    # --- Caso 2: deducir desde audio_corpus/<GENERO>/archivo ---
    genre_folder = None
    lp = (local_path or '').replace("\\", "/")
    parts = [p for p in lp.split('/') if p]
    try:
        if 'audio_corpus' in parts:
            i = parts.index('audio_corpus')
            genre_folder = parts[i+1] if i+1 < len(parts) else None
    except Exception:
        genre_folder = None

    folder = genre_folder or (genre or 'misc')
    base = os.path.basename(lp)
    name, ext = os.path.splitext(base)
    ext = ext or ".mp3"

    fname = _ascii_slug(name) + ext.lower()
    key = f"{_S3_PREFIX}/{folder}/{fname}"
    url = f"https://{_S3_BUCKET}.s3.amazonaws.com/{quote(key)}"
    return key, url



# --------------- Indexación por archivo (v2 → songs2 / fingerprints2) ---------------
def index_file_v2(conn, path: str, title: Optional[str] = None, artist: Optional[str] = None,
                  genre: Optional[str] = None, year: Optional[int] = None,
                  youtube_url: Optional[str] = None, youtube_id: Optional[str] = None,
                  highlight_sec: Optional[int] = None, song_path: Optional[str] = None,
                  song_id: Optional[str] = None) -> Tuple[bool, str]:
    path = norm_str(path)
    if not path:
        return (False, "skip: path vacío")
    norm_path = path.replace('\\\\', os.sep).replace('/', os.sep)
    if not file_exists(norm_path):
        return (False, f"skip: file not found -> {path}")

    if not title:
        title = os.path.splitext(os.path.basename(norm_path))[0].replace("_", " ") or "Unknown Title"
    if not artist:
        artist = "Unknown Artist"
    if year is None:
        year = None

    if not youtube_id and youtube_url:
        youtube_id = youtube_id_from_url(youtube_url)
    yt_highlight = add_time_param(youtube_url, highlight_sec)

    if not song_id:
        fallback_key = os.path.basename(norm_path)
        song_id = stable_song_id(title, artist, year, fallback_key)

    s3_key, s3_url = derive_s3_key(genre, norm_path, song_path)

    db.add_song2(
        conn,
        song_id=song_id,
        title=title,
        artist=artist,
        genre=genre,
        year=year,
        path=path,
        s3_key=s3_key,
        s3_url=s3_url,
        youtube_url=youtube_url,
        youtube_id=youtube_id,
        youtube_url_highlight=yt_highlight,
        highlight_sec=highlight_sec,
    )

    hashes = compute_fingerprints(norm_path)
    if not hashes:
        return (False, f"no fingerprints -> {path}")

    db.add_fingerprints2(conn, song_id=song_id, hashes=hashes, batch_size=5000)
    return (True, f"indexed2 {title} ({artist}) - hashes: {len(hashes)}")


# --------------- Lectura CSV ---------------
def read_csv_rows(csv_path: str) -> Iterable[Dict[str, str]]:
    with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        headers = {h.strip() for h in (reader.fieldnames or [])}
        missing = CSV_REQUIRED - headers
        if missing:
            raise ValueError(f"CSV missing required columns: {sorted(missing)}")
        for row in reader:
            yield row

def index_manifest_v2(conn, csv_path: str = "manifest2.csv", limit: Optional[int] = None):
    total = ok = skipped = errors = 0
    for row in read_csv_rows(csv_path):
        if limit is not None and total >= limit:
            break
        total += 1
        try:
            path    = norm_str(row.get("path"))
            title   = norm_str(row.get("title"))
            artist  = norm_str(row.get("artist"))
            genre   = norm_str(row.get("genre"))
            year    = to_int_or_none(row.get("year"))
            hl_sec  = parse_timecode_to_seconds(row.get("highlight"))
            yt_url  = norm_str(row.get("youtube_url"))
            yt_id   = norm_str(row.get("youtube_id"))
            s_id    = norm_str(row.get("song_id"))
            s_path  = norm_str(row.get("song_path"))

            done, msg = index_file_v2(
                conn,
                path=path or "",
                title=title,
                artist=artist,
                genre=genre,
                year=year,
                youtube_url=yt_url,
                youtube_id=yt_id,
                highlight_sec=hl_sec,
                song_path=s_path,
                song_id=s_id,
            )
            if done:
                ok += 1
                print(f"✅ {msg}")
            else:
                skipped += 1
                print(f"⏭️  {msg}")
        except Exception as e:
            errors += 1
            print(f"❌ error fila {total}: {e}")

    print("\n---- RESUMEN (CSV v2) ----")
    print(f"total filas:    {total}")
    print(f"indexadas:      {ok}")
    print(f"saltadas:       {skipped}")
    print(f"errores:        {errors}")


# --------------- Backwards compat (v1) ---------------
AUDIO_EXT = {".mp3", ".wav", ".flac", ".m4a", ".ogg", ".aac"}

def index_file(conn, path: str, title: Optional[str] = None, artist: Optional[str] = None,
               year: Optional[int] = None, youtube_url: Optional[str] = None,
               youtube_id: Optional[str] = None, song_id: Optional[str] = None) -> Tuple[bool, str]:
    # Versión original (tablas v1)
    path = norm_str(path)
    if not path:
        return (False, "skip: path vacío")
    if not os.path.isfile(path):
        return (False, f"skip: file not found -> {path}")

    if not title:
        title = os.path.splitext(os.path.basename(path))[0].replace("_", " ") or "Unknown Title"
    if not artist:
        artist = "Unknown Artist"
    if year is None:
        year = None
    if not youtube_id and youtube_url:
        youtube_id = youtube_id_from_url(youtube_url)
    if not song_id:
        fallback_key = os.path.basename(path)
        song_id = stable_song_id(title, artist, year, fallback_key)

    db.add_song(conn, song_id, title, artist, year, path, youtube_url, youtube_id)
    hashes = compute_fingerprints(path)
    if not hashes:
        return (False, f"no fingerprints -> {path}")
    db.add_fingerprints(conn, song_id=song_id, hashes=hashes, batch_size=5000)
    return (True, f"indexed {title} ({artist}) - hashes: {len(hashes)}")

def index_manifest(conn, csv_path: str = "manifest.csv", limit: Optional[int] = None):
    # Versión original (tablas v1)
    total = ok = skipped = errors = 0
    for row in read_csv_rows(csv_path):
        if limit is not None and total >= limit:
            break
        total += 1
        try:
            path   = norm_str(row.get("path"))
            title  = norm_str(row.get("title"))
            artist = norm_str(row.get("artist"))
            year   = to_int_or_none(row.get("year"))
            yt_url = norm_str(row.get("youtube_url"))
            yt_id  = norm_str(row.get("youtube_id"))
            s_id   = norm_str(row.get("song_id"))

            done, msg = index_file(
                conn,
                path=path or "",
                title=title,
                artist=artist,
                year=year,
                youtube_url=yt_url,
                youtube_id=yt_id,
                song_id=s_id,
            )
            if done:
                ok += 1
                print(f"✅ {msg}")
            else:
                skipped += 1
                print(f"⏭️  {msg}")
        except Exception as e:
            errors += 1
            print(f"❌ error fila {total}: {e}")

    print("\n---- RESUMEN (CSV) ----")
    print(f"total filas:    {total}")
    print(f"indexadas:      {ok}")
    print(f"saltadas:       {skipped}")
    print(f"errores:        {errors}")


# --------------- Entrypoint CLI ---------------
def _csv_has_genre(csv_path: str) -> bool:
    try:
        with open(csv_path, "r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            headers = {h.strip().lower() for h in (reader.fieldnames or [])}
        return "genre" in headers or "highlight" in headers or "song_path" in headers
    except Exception:
        return False

def index_folder(conn, root: str = "audio_corpus"):
    total, ok, skipped, errors = 0, 0, 0, 0
    for dirpath, _, files in os.walk(root):
        for name in files:
            ext = os.path.splitext(name)[1].lower()
            if ext not in AUDIO_EXT:
                continue
            total += 1
            path = os.path.join(dirpath, name)
            try:
                done, msg = index_file(conn, path)
                if done:
                    ok += 1
                    print(f"✅ {msg}")
                else:
                    skipped += 1
                    print(f"⏭️  {msg}")
            except Exception as e:
                errors += 1
                print(f"❌ error: {path} -> {e}")

    print("\n---- RESUMEN (folder) ----")
    print(f"total archivos: {total}")
    print(f"indexados:      {ok}")
    print(f"saltados:       {skipped}")
    print(f"errores:        {errors}")

def main():
    """
    Usos:
      python -m src.indexer                         # indexar carpeta (v1)
      python -m src.indexer manifest.csv            # CSV v1 → tablas v1
      python -m src.indexer manifest2.csv           # CSV v2 → tablas v2
      python -m src.indexer manifest2.csv 50        # CSV v2 con límite
    """
    args = sys.argv[1:]
    conn = db.connect()
    db.init_db(conn)  # idempotente: crea v1 y v2 sin borrar nada

    try:
        if not args:
            index_folder(conn, root="audio_corpus")
        else:
            csv_path = args[0]
            limit = int(args[1]) if len(args) > 1 else None
            if _csv_has_genre(csv_path) or os.path.basename(csv_path).lower().startswith("manifest2"):
                index_manifest_v2(conn, csv_path=csv_path, limit=limit)
            else:
                index_manifest(conn, csv_path=csv_path, limit=limit)
    finally:
        conn.close()

if __name__ == "__main__":
    main()
