import os, sys, glob, json
from src import db_mysql as db
from src.identify import identify_clip

SNIPPET_GLOBS = ["snippets/*.wav", "snippets/*.mp3", "snippets/*.m4a", "snippets/*.flac", "snippets/*.ogg"]

def auto_pick_snippet() -> str | None:
    files = []
    for g in SNIPPET_GLOBS:
        files.extend(glob.glob(g))
    files = sorted(files, key=lambda p: os.path.getsize(p) if os.path.isfile(p) else 0, reverse=True)
    return files[0] if files else None

def main():
    # Permite pasar un path manual o elegir automáticamente el primero que encuentre
    snippet = sys.argv[1] if len(sys.argv) > 1 else auto_pick_snippet()
    if not snippet or not os.path.isfile(snippet):
        print("⛔ No encontré snippet. Pon uno en la carpeta 'snippets/' o pasa la ruta como argumento.")
        print("Ejemplos:")
        print("  python scripts/test_identify.py snippets/tu_clip.wav")
        sys.exit(1)

    print(f"▶️  Probando identificación con: {snippet}")

    conn = db.connect()
    try:
        result = identify_clip(conn, snippet)
        print("\n✅ Resultado:")
        print(json.dumps(result, indent=2, ensure_ascii=False))
    finally:
        conn.close()

if __name__ == "__main__":
    main()
