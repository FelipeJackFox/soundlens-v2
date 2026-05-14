# SoundLens v2

Song identification and music-genre analysis system based on the Fourier transform and spectral fingerprinting (Shazam-style), with an Android app, AWS Lambda backend, and a Python indexer for the audio corpus.

> **Version 2.** The original repository is [`FelipeJackFox/shazam-demo`](https://github.com/FelipeJackFox/shazam-demo) (archived). This repo reorganizes the project into a clean structure: the Android app lives in `android/`, the backend in `backend/`, the indexer in `indexer/`, and documentation in `docs/`. Audio datasets and album covers are copyrighted material and are kept out of the repository.

## Structure

```
soundlens-v2/
├── android/      Android app (Kotlin)
├── backend/
│   ├── lambda-identify/   Song and genre identification Lambda
│   └── lambda-plot/       Plot-generation Lambda
├── indexer/      Python scripts to build the fingerprint database from the audio corpus
└── docs/         Research, final report, poster, and branding
```

`dataset/` and `media/` exist locally but are blocked by `.gitignore`.

## Documentation

- `docs/Investigacion.pdf` — prior research.
- `docs/Contexto_del_proyecto.pdf` — project context and goals.
- `docs/Reporte_final.pdf` — final system report.
- `docs/Tests_genero.pdf` — genre-identification test results.
- `docs/poster/SOUNDLENS_poster.pdf` — exposition poster.
