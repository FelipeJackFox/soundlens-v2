# --- imports (remueve 'import librosa') ---
import numpy as np
from scipy import ndimage as ndi
from scipy.io import wavfile
from scipy.signal import stft, resample_poly
from typing import List, Tuple
from . import config

def load_mono(path: str, sr: int = config.SR) -> np.ndarray:
    # Leer WAV con SciPy (no requiere libsndfile)
    orig_sr, data = wavfile.read(path)  # data: np.ndarray

    # A mono
    if data.ndim == 2:
        data = data.mean(axis=1)

    # Normaliza a float32 [-1, 1] según dtype
    if data.dtype == np.int16:
        y = data.astype(np.float32) / 32768.0
    elif data.dtype == np.int32:
        y = data.astype(np.float32) / 2147483648.0
    elif data.dtype == np.uint8:
        y = (data.astype(np.float32) - 128.0) / 128.0
    else:
        y = data.astype(np.float32)

    # Resample si no es 44.1 kHz
    if orig_sr != sr:
        g = np.gcd(orig_sr, sr)
        y = resample_poly(y, sr // g, orig_sr // g).astype(np.float32)

    # Normalización simple (como ya hacías)
    m = np.max(np.abs(y))
    if m > 0:
        y = y / m
    return y

def _amplitude_to_db(S_mag: np.ndarray, ref: float = None, amin: float = 1e-10) -> np.ndarray:
    # Equivalente a librosa.amplitude_to_db(S, ref=np.max) sin dependencias
    if S_mag.size == 0:
        return S_mag
    if ref is None:
        ref = float(S_mag.max())
    S_db = 20.0 * np.log10(np.maximum(S_mag, amin))
    S_db -= 20.0 * np.log10(max(ref, amin))
    return S_db

def stft_db(y: np.ndarray):
    # STFT con SciPy: misma ventana/tamaño/paso que en config
    nperseg = config.N_FFT
    hop = config.HOP_LENGTH
    noverlap = nperseg - hop
    f, t, Zxx = stft(
        y, fs=config.SR, window='hann',
        nperseg=nperseg, noverlap=noverlap,
        boundary='zeros', padded=True, return_onesided=True
    )
    S_mag = np.abs(Zxx)
    S_db = _amplitude_to_db(S_mag, ref=S_mag.max() if S_mag.size else 1.0)
    # freqs en Hz y times en segundos vienen de SciPy directamente
    return S_db, f, t

def find_peaks_2d(S_db, amp_threshold_db: float = None):
    if amp_threshold_db is None:
        amp_threshold_db = config.PEAK_AMPLITUDE_DB
    footprint = np.ones((config.PEAK_NEIGHBORHOOD_SIZE, config.PEAK_NEIGHBORHOOD_SIZE), dtype=bool)
    local_max = S_db == ndi.maximum_filter(S_db, footprint=footprint, mode='constant', cval=S_db.min())
    detected = np.where(local_max & (S_db >= amp_threshold_db))
    peaks = np.stack(detected, axis=1) if detected[0].size else np.zeros((0,2), dtype=int)
    if peaks.size:
        peaks = peaks[np.argsort(peaks[:,1])]
    return peaks

def quantize_freq(f_hz: float) -> int:
    return int(np.round(f_hz / config.FREQ_QUANT_HZ))

def quantize_time(t_sec: float) -> int:
    return int(np.round(t_sec / config.TIME_QUANT_SEC))

def generate_hashes(peaks, freqs, times) -> List[Tuple[str, int]]:
    if peaks.size == 0:
        return []
    hashes = []
    f_bins = peaks[:,0]
    t_bins = peaks[:,1]
    f_hz = freqs[f_bins]
    t_sec = times[t_bins]
    N = len(peaks)
    for i in range(N):
        f1, t1 = f_hz[i], t_sec[i]
        for j in range(1, config.FAN_VALUE+1):
            k = i + j
            if k >= N: break
            f2, t2 = f_hz[k], t_sec[k]
            dt = t2 - t1
            if dt < config.TARGET_DT_MIN or dt > config.TARGET_DT_MAX:
                continue
            qf1 = quantize_freq(f1)
            qf2 = quantize_freq(f2)
            qdt = quantize_time(dt)
            tq = quantize_time(t1)
            h = f"{qf1}|{qf2}|{qdt}"
            hashes.append((h, tq))
    return hashes

def audio_to_hashes(path: str):
    y = load_mono(path)
    S_db, freqs, times = stft_db(y)
    peaks = find_peaks_2d(S_db)
    return generate_hashes(peaks, freqs, times)
