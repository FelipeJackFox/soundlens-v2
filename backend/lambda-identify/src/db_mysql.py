import os
import socket
from typing import List, Tuple, Optional

import mysql.connector
from mysql.connector import Error
from dotenv import load_dotenv

# Carga variables de entorno desde .env en la raíz del proyecto
load_dotenv()


# -----------------------------
# Utilidades de entorno / DNS
# -----------------------------
def _env(name: str, default=None):
    v = os.getenv(name, default)
    if isinstance(v, str):
        return v.strip()
    return v

def _resolve_host(hostname: str) -> str:
    """
    En algunos Windows el conector C falla resolviendo el endpoint del CLUSTER.
    Esto intenta resolver a IP (preferencia IPv4). Si falla, devuelve el hostname.
    """
    try:
        infos = socket.getaddrinfo(hostname, None)
        # prioriza IPv4
        for fam, *_ in infos:
            if fam == socket.AF_INET:
                return infos[0][4][0]
        return infos[0][4][0]
    except Exception:
        return hostname


# -----------------------------
# Configuración y conexión
# -----------------------------
def _get_cfg():
    # Permite usar endpoint de instancia o cluster. Si quieres forzar IP, usa DB_HOST_IP.
    host = _env("DB_HOST", "127.0.0.1")
    host_ip_override = _env("DB_HOST_IP")
    if host_ip_override:
        host_final = host_ip_override
    else:
        host_final = _resolve_host(host)

    cfg = {
        "host": host_final,
        "port": int(_env("DB_PORT", "3306")),
        "user": _env("DB_USER", "root"),
        "password": _env("DB_PASS", ""),
        "database": _env("DB_NAME", "shazam"),
        "connection_timeout": 10,
        # En Windows a veces ayuda forzar la implementación pura-Python
        "use_pure": True,
    }

    # SSL opcional (si RDS te lo exige o lo quieres)
    ssl_ca = _env("DB_SSL_CA")
    if ssl_ca and os.path.exists(ssl_ca):
        cfg["ssl_ca"] = ssl_ca
        cfg["ssl_verify_cert"] = True

    return cfg


def connect(_: str = None):  # mantenemos la firma compatible con tu código previo
    return mysql.connector.connect(**_get_cfg())


def ping() -> bool:
    """Devuelve True si la conexión ejecuta SELECT 1 correctamente."""
    cfg = _get_cfg()
    # Debug corto para ver qué host terminó usando:
    print("DB_HOST (final) =", repr(cfg["host"]))
    conn = mysql.connector.connect(**cfg)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
            cur.fetchone()
        return True
    finally:
        conn.close()


# -----------------------------
# Esquema / índices
# -----------------------------
SCHEMA_STATEMENTS = [
    """
    CREATE TABLE IF NOT EXISTS songs (
      song_id     VARCHAR(32)  PRIMARY KEY,
      title       VARCHAR(255) NOT NULL,
      artist      VARCHAR(255),
      year        INT,
      path        TEXT,
      youtube_url VARCHAR(512),
      youtube_id  VARCHAR(32)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
    """
    CREATE TABLE IF NOT EXISTS fingerprints (
      hash     VARCHAR(64) NOT NULL,
      song_id  VARCHAR(32) NOT NULL,
      t_anchor INT NOT NULL,
      CONSTRAINT fk_fingerprints_song
        FOREIGN KEY (song_id) REFERENCES songs(song_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
]

def _ensure_index(conn, table: str, index_name: str, index_sql: str):
    """
    Crea un índice solo si NO existe (idempotente, evita 'Duplicate key name').
    """
    check_sql = """
        SELECT COUNT(1)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = %s
          AND index_name = %s
    """
    with conn.cursor() as cur:
        cur.execute(check_sql, (table, index_name))
        (exists,) = cur.fetchone()
        if not exists:
            cur.execute(index_sql)

def init_db(conn):
    """Crea tablas e índices de forma idempotente."""
    with conn.cursor() as cur:
        for stmt in SCHEMA_STATEMENTS:
            cur.execute(stmt.strip())

    _ensure_index(conn, "fingerprints", "idx_hash", "CREATE INDEX idx_hash ON fingerprints(hash)")
    _ensure_index(conn, "fingerprints", "idx_song", "CREATE INDEX idx_song ON fingerprints(song_id)")
    conn.commit()


# -----------------------------
# Operaciones CRUD usadas por indexer/identify
# -----------------------------
def add_song(
    conn,
    song_id: str,
    title: str,
    artist: Optional[str],
    year: Optional[int],
    path: Optional[str],
    youtube_url: Optional[str] = None,
    youtube_id: Optional[str] = None,
):
    """
    Inserta/actualiza metadatos de canción.
    """
    sql = """
        INSERT INTO songs (song_id, title, artist, year, path, youtube_url, youtube_id)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
          title=VALUES(title),
          artist=VALUES(artist),
          year=VALUES(year),
          path=VALUES(path),
          youtube_url=VALUES(youtube_url),
          youtube_id=VALUES(youtube_id)
    """
    with conn.cursor() as cur:
        cur.execute(sql, (song_id, title, artist, year, path, youtube_url, youtube_id))
    conn.commit()


def add_fingerprints(
    conn,
    song_id: str,
    hashes: List[Tuple[str, int]],
    batch_size: int = 5000,
):
    """
    Inserta huellas (hash, t_anchor) para una canción en lotes.
    'hashes' es una lista de tuplas: [(hash_str, t_anchor), ...]
    """
    if not hashes:
        return
    sql = "INSERT INTO fingerprints (hash, song_id, t_anchor) VALUES (%s, %s, %s)"
    with conn.cursor() as cur:
        for i in range(0, len(hashes), batch_size):
            chunk = hashes[i : i + batch_size]
            cur.executemany(sql, [(h, song_id, t) for (h, t) in chunk])
            conn.commit()


def query_hash(conn, h: str) -> List[Tuple[str, int]]:
    """
    Devuelve [(song_id, t_anchor), ...] para un hash dado.
    """
    with conn.cursor() as cur:
        cur.execute("SELECT song_id, t_anchor FROM fingerprints WHERE hash=%s", (h,))
        return cur.fetchall()


def get_song_meta(conn, song_id: str):
    """
    Devuelve metadatos de la canción, incluyendo YouTube.
    """
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT song_id, title, artist, year, path, youtube_url, youtube_id
            FROM songs
            WHERE song_id=%s
            """,
            (song_id,),
        )
        return cur.fetchone()
