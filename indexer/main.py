import argparse, sys, json, os
from dotenv import load_dotenv
from src.util_youtube import extract_youtube_id

load_dotenv()

def pick_db_module(backend: str):
    if backend == "mysql":
        from src import db_mysql as db
    else:
        from src import db as db  # SQLite legacy
    return db

def cmd_index(args):
    db = pick_db_module(args.backend or os.getenv("DB_BACKEND","sqlite"))
    from src import indexer
    conn = db.connect(args.db)  # db path se ignora en mysql; mantenemos firma
    db.init_db(conn)
    if args.root:
        indexer.index_folder(conn, args.root)
    elif args.file:
        indexer.index_file(conn, args.file, title=args.title, artist=args.artist, year=args.year)
    else:
        print("Proporciona --root (carpeta) o --file (archivo)")
        sys.exit(1)
    print("Indexado listo.")

def cmd_identify(args):
    db = pick_db_module(args.backend or os.getenv("DB_BACKEND","sqlite"))
    from src import identify
    conn = db.connect(args.db)
    result = identify.identify_clip(conn, args.audio)
    if not result:
        print("Sin coincidencias.")
        sys.exit(2)
    print(json.dumps(result, indent=2, ensure_ascii=False))

def cmd_index_manifest(args):
    db = pick_db_module(args.backend or os.getenv("DB_BACKEND","sqlite"))
    from src import indexer
    import csv
    conn = db.connect(args.db); db.init_db(conn)
    with open(args.csv, newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for row in reader:
            path   = row["path"]
            title  = row.get("title") or None
            artist = row.get("artist") or None
            year   = int(row["year"]) if row.get("year") else None
            yt_url = row.get("youtube_url") or None
            yt_id  = extract_youtube_id(yt_url) if yt_url else None
            indexer.index_file(conn, path, title=title, artist=artist, year=year,
                               youtube_url=yt_url, youtube_id=yt_id)
    print("Indexado desde manifest listo.")

def main():
    p = argparse.ArgumentParser(description="Mini-Shazam demo (fingerprinting acústico)")
    sub = p.add_subparsers(dest="cmd")

    # index
    p_idx = sub.add_parser("index", help="Indexar canciones")
    p_idx.add_argument("--db", required=False, help="Ruta DB (solo SQLite). En MySQL se ignora")
    p_idx.add_argument("--backend", choices=["sqlite","mysql"], help="Backend de base de datos")
    g = p_idx.add_mutually_exclusive_group(required=True)
    g.add_argument("--root")
    g.add_argument("--file")
    p_idx.add_argument("--title")
    p_idx.add_argument("--artist")
    p_idx.add_argument("--year", type=int)
    p_idx.set_defaults(func=cmd_index)

    # identify
    p_id = sub.add_parser("identify", help="Identificar un clip de audio")
    p_id.add_argument("--db", required=False)
    p_id.add_argument("--backend", choices=["sqlite","mysql"])
    p_id.add_argument("--audio", required=True)
    p_id.set_defaults(func=cmd_identify)

    # index-manifest
    p_m = sub.add_parser("index-manifest", help="Indexar usando un CSV con metadatos")
    p_m.add_argument("--db", required=False)
    p_m.add_argument("--backend", choices=["sqlite","mysql"])
    p_m.add_argument("--csv", required=True, help="CSV con columnas: path,title,artist,year")
    p_m.set_defaults(func=cmd_index_manifest)

    args = p.parse_args()
    if not args.cmd:
        p.print_help(); return
    args.func(args)

if __name__ == "__main__":
    main()