import sqlite3
from typing import List, Tuple

SCHEMA = """
CREATE TABLE IF NOT EXISTS songs(
    song_id TEXT PRIMARY KEY,
    title   TEXT,
    artist  TEXT,
    year    INTEGER,
    path    TEXT,
    youtube_url TEXT,
    youtube_id  TEXT
);
CREATE TABLE IF NOT EXISTS fingerprints(
    hash TEXT,
    song_id TEXT,
    t_anchor INTEGER
);
CREATE INDEX IF NOT EXISTS idx_hash ON fingerprints(hash);
CREATE INDEX IF NOT EXISTS idx_song ON fingerprints(song_id);
"""
def connect(db_path: str) -> sqlite3.Connection:
    return sqlite3.connect(db_path)

def init_db(conn: sqlite3.Connection):
    conn.executescript(SCHEMA)
    conn.commit()

def add_song(conn, song_id, title, artist, year, path, youtube_url=None, youtube_id=None):
    conn.execute(
        "INSERT OR REPLACE INTO songs(song_id, title, artist, year, path, youtube_url, youtube_id) VALUES(?,?,?,?,?,?,?)",
        (song_id, title, artist, year, path, youtube_url, youtube_id),
    )
    conn.commit()

def add_fingerprints(conn, song_id: str, hashes: List[Tuple[str,int]]):
    conn.executemany(
        "INSERT INTO fingerprints(hash, song_id, t_anchor) VALUES(?,?,?)",
        [(h, song_id, t) for (h, t) in hashes],
    )
    conn.commit()

def query_hash(conn, h: str) -> List[Tuple[str,int]]:
    cur = conn.execute("SELECT song_id, t_anchor FROM fingerprints WHERE hash = ?", (h,))
    return cur.fetchall()

def get_song_meta(conn, song_id: str):
    cur = conn.execute("SELECT song_id, title, artist, year, path FROM songs WHERE song_id = ?", (song_id,))
    return cur.fetchone()
