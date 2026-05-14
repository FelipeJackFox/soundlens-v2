# Mini-Shazam — Versión Cloud (Aurora MySQL backend)

Esta versión del proyecto implementa el algoritmo tipo Shazam con el cómputo de huellas (fingerprints) en local,
pero delega la **búsqueda y coincidencia de hashes** a una base de datos MySQL/Aurora en la nube.

---

## 🚀 Instalación
```bash
python -m venv .venv
source .venv/bin/activate       # En Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

---

## ⚙️ Configuración del entorno (.env)
En la raíz del proyecto, crea un archivo `.env` con tus credenciales de Aurora:

```bash
DB_BACKEND=mysql
DB_HOST=<endpoint-de-tu-cluster>
DB_PORT=3306
DB_USER=<usuario>
DB_PASS=<contraseña>
DB_NAME=shazam
```

Opcionalmente puedes agregar `DB_SSL_CA` si tu instancia exige SSL.

---

## 📁 Estructura del proyecto

```
shazam_demo/
├── src/
│   ├── config.py           # Parámetros del pipeline (FFT, hop, umbrales)
│   ├── db_mysql.py         # Backend Aurora MySQL
│   ├── fingerprint.py      # STFT, picos y hashes
│   ├── identify.py         # Identificación vía JOIN con Aurora
│   ├── indexer.py          # Indexado desde carpetas o manifest.csv
│   └── util_youtube.py     # Extrae IDs de URLs de YouTube
├── scripts/
│   └── test_identify.py    # Script rápido de prueba
├── main.py                 # CLI principal (indexar / identificar)
├── manifest.csv            # Metadatos de canciones
├── requirements.txt
└── .env
```

---

## 🧠 Concepto general
1. El **hashing acústico** (fingerprinting) se realiza localmente con `librosa` y `scipy`.
2. Los hashes se almacenan en la base de datos Aurora MySQL.
3. La identificación compara los hashes del clip con los de la base y determina la canción más probable.

---

## 🧩 Comandos disponibles

### 1️⃣ Indexar canciones (desde carpeta)
```bash
python main.py index --backend mysql --root ./audio_corpus
```
Indexa todos los `.wav` / `.mp3` del directorio `audio_corpus/` en la base de datos.

---

### 2️⃣ Indexar canciones desde un CSV
El `manifest.csv` debe contener las columnas:

```
path,title,artist,year,youtube_url,youtube_id,song_id
```

Ejemplo:
```bash
python main.py index-manifest --backend mysql --csv manifest.csv
```

Cada fila del CSV genera sus huellas y las inserta en la base de datos Aurora.

---

### 3️⃣ Identificar un clip de audio
```bash
python main.py identify --backend mysql --audio ./snippets/one_kiss_test_clip.wav

python main.py identify --backend mysql --audio ./snippets/hedwig_s_theme_test_clip.wav

python main.py identify --backend mysql --audio ./snippets/suite_bergamasque_2_test_clip_1.wav

python main.py identify --backend mysql --audio ./snippets/the_final_countdown_test_clip_1.wav

python main.py identify --backend mysql --audio ./snippets/abba.ogg

python main.py identify --backend mysql --audio ./snippets/interstellar.ogg


```

Ejemplo de salida:
```json
{
  "ok": true,
  "song_id": "a83f19...",
  "title": "One Kiss",
  "artist": "Calvin Harris ft. Dua Lipa",
  "confidence": 0.92,
  "matches_for_song": 412,
  "matches_at_best_offset": 380,
  "offset_frames": 118,
  "youtube_id": "Dd5tFhZp",
  "clip_hashes": 520
}
```

---

### 4️⃣ Probar identificación automática
Desde `scripts/test_identify.py`:

```bash
python -m scripts.test_identify
```
Este busca automáticamente el primer clip en la carpeta `snippets/`
y ejecuta la identificación completa contra la base Aurora.

---

## 💡 Tips útiles
- Mantén el audio **mono y 44.1 kHz** para consistencia.
- Si el audio suena muy bajo, normaliza el volumen antes de indexarlo.
- Usa clips de **5–8 segundos** para buena precisión.
- Si hay muy pocas coincidencias, revisa `PEAK_AMPLITUDE_DB` en `config.py`.

---

## 🧰 Mantenimiento de base de datos
Para inicializar las tablas o verificar conexión:
```bash
python -m src.db_mysql
```
O dentro del código:
```python
from src import db_mysql as db
conn = db.connect(); db.init_db(conn)
```

---
