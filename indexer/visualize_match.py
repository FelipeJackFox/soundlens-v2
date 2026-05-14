import argparse, os, json
import numpy as np
import matplotlib.pyplot as plt
import librosa

from src import db, identify, config, fingerprint


def stft_db_axes(y, sr, n_fft=config.N_FFT, hop_length=config.HOP_LENGTH):
    """STFT → magnitud en dB + ejes de tiempo (s) y frecuencia (Hz)."""
    S = librosa.stft(y, n_fft=n_fft, hop_length=hop_length)
    S_mag = np.abs(S)
    S_db = librosa.amplitude_to_db(S_mag, ref=np.max)
    freqs = librosa.fft_frequencies(sr=sr, n_fft=n_fft)
    times = librosa.frames_to_time(np.arange(S_db.shape[1]), sr=sr, hop_length=hop_length)
    return S_db, times, freqs


def normalize_peak(y: np.ndarray) -> np.ndarray:
    """Normaliza por pico a ±1 (si hay señal)."""
    m = np.max(np.abs(y)) if y.size else 0.0
    if m > 0:
        y = y / m
    return y


def plot_spec(ax, S_db, times, freqs, title, overlay_peaks=None):
    """Dibuja espectrograma (extents en s y Hz). Admite overlay de picos (f_bin, t_bin)."""
    extent = [times[0], times[-1], freqs[0], freqs[-1]]
    im = ax.imshow(S_db, origin="lower", aspect="auto", extent=extent, cmap="viridis")
    ax.set_title(title)
    ax.set_xlabel("Tiempo (s)")
    ax.set_ylabel("Frecuencia (Hz)")

    if overlay_peaks is not None and overlay_peaks.size:
        f_bins = overlay_peaks[:, 0].astype(int)
        t_bins = overlay_peaks[:, 1].astype(int)
        # puntos rojos más visibles
        ax.scatter(times[t_bins], freqs[f_bins], s=18, c="red", alpha=0.8, marker=".", linewidths=0.0, label="Picos")
        ax.legend(loc="upper right")

    return im


def main():
    ap = argparse.ArgumentParser(
        description="Comparar espectrogramas: canción completa (con match resaltado), snippet y segmento coincidente (con picos)."
    )
    ap.add_argument("--db", required=True, help="Base SQLite de huellas (p. ej. fp.sqlite)")
    ap.add_argument("--audio", required=True, help="Snippet a identificar (p. ej. .\\snippets\\clip.wav)")
    ap.add_argument("--plots_dir", default="./plots", help="Carpeta para guardar PNGs")
    ap.add_argument("--highlight_color", default="skyblue", help='Color de la franja del match (p. ej. "skyblue", "lime", "#ff8800")')
    ap.add_argument("--highlight_alpha", type=float, default=0.25, help="Transparencia de la franja (0–1)")
    args = ap.parse_args()

    os.makedirs(args.plots_dir, exist_ok=True)

    # 1) Identificar
    conn = db.connect(args.db)
    res = identify.identify_clip(conn, args.audio)
    if not res or not res.get("is_hit"):
        print("No hubo identificación confiable.")
        print(json.dumps(res, indent=2, ensure_ascii=False))
        return

    best = res["best_meta"]
    best_offset_q = res["best_offset_quant"]
    offset_sec = best_offset_q * config.TIME_QUANT_SEC
    song_path = best["path"]
    print("Best match:", best["title"], "-", best["artist"], "| offset (s) ~", round(offset_sec, 2))
    print("Song path:", song_path)

    # 2) Cargar snippet y normalizar
    y_snip, _ = librosa.load(args.audio, sr=config.SR, mono=True)
    y_snip = normalize_peak(y_snip)
    S_snip_db, t_snip, f_snip = stft_db_axes(y_snip, config.SR)
    peaks_snip = fingerprint.find_peaks_2d(S_snip_db)  # (f_bin, t_bin)
    snip_dur = len(y_snip) / config.SR

    # 3) Cargar canción completa, normalizar y recortar el segmento alineado
    y_song, _ = librosa.load(song_path, sr=config.SR, mono=True)
    y_song = normalize_peak(y_song)
    song_dur = len(y_song) / config.SR

    start_sec = max(0.0, min(offset_sec, max(0.0, song_dur - snip_dur)))
    end_sec = start_sec + snip_dur
    y_seg = y_song[int(start_sec * config.SR): int(end_sec * config.SR)]
    y_seg = normalize_peak(y_seg)

    # 4) STFTs
    S_full_db, t_full, f_full = stft_db_axes(y_song, config.SR)
    S_seg_db, t_seg, f_seg = stft_db_axes(y_seg, config.SR)
    peaks_seg = fingerprint.find_peaks_2d(S_seg_db)

    # 5) Figura con 3 subplots:
    fig, axs = plt.subplots(3, 1, figsize=(12, 11), sharex=False)
    fig.suptitle(f"Identificación: {best['title']} — segmento ≈{start_sec:.2f}s a {end_sec:.2f}s", fontsize=14)

    # (a) Canción completa + franja de match
    im0 = plot_spec(axs[0], S_full_db, t_full, f_full, "Espectrograma de la canción completa")
    axs[0].axvspan(start_sec, end_sec, color=args.highlight_color, alpha=args.highlight_alpha, label="Ventana del match")
    # Líneas punteadas en los bordes (opcional, ayuda visual)
    axs[0].axvline(start_sec, color=args.highlight_color, linestyle="--", linewidth=2)
    axs[0].axvline(end_sec, color=args.highlight_color, linestyle="--", linewidth=2)
    axs[0].legend(loc="upper right")

    # (b) Snippet + picos
    im1 = plot_spec(axs[1], S_snip_db, t_snip, f_snip, "Snippet (clip grabado) con picos", overlay_peaks=peaks_snip)

    # (c) Segmento coincidente + picos
    title_seg = f"Segmento coincidente con picos ({best['title']})"
    im2 = plot_spec(axs[2], S_seg_db, t_seg, f_seg, title_seg, overlay_peaks=peaks_seg)

    # Colorbars por subplot (opcional)
    for ax, im in zip(axs, [im0, im1, im2]):
        fig.colorbar(im, ax=ax, fraction=0.025, pad=0.02)

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    out_path = os.path.join(args.plots_dir, "comparison_full_snippet_segment_with_peaks.png")
    plt.savefig(out_path, dpi=160, bbox_inches="tight")
    plt.show()
    print(f"✅ Gráfica guardada en: {out_path}")


if __name__ == "__main__":
    main()
