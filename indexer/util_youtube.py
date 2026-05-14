import re

_YT_PATTERNS = [
    r"(?:v=|vi=)([A-Za-z0-9_-]{11})",            # ...watch?v=ID
    r"(?:youtu\.be/)([A-Za-z0-9_-]{11})",        # youtu.be/ID
    r"(?:/embed/)([A-Za-z0-9_-]{11})",           # /embed/ID
    r"(?:/shorts/)([A-Za-z0-9_-]{11})",          # /shorts/ID
    r"(?:/v/)([A-Za-z0-9_-]{11})",               # /v/ID
]

def extract_youtube_id(url: str | None) -> str | None:
    if not url:
        return None
    for pat in _YT_PATTERNS:
        m = re.search(pat, url)
        if m:
            return m.group(1)
    # fallback: si parece un ID suelto
    if re.fullmatch(r"[A-Za-z0-9_-]{11}", url):
        return url
    return None