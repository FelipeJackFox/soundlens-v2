import numpy as np
import librosa
import scipy.ndimage as ndi
from typing import List, Tuple
from . import config

def load_mono(path: str, sr: int = config.SR) -> np.ndarray:
    y, _ = librosa.load(path, sr=sr, mono=True)
    if np.max(np.abs(y)) > 0:
        y = y / np.max(np.abs(y))  # 🔹 normalización simple
    return y

def stft_db(y: np.ndarray):
    S = librosa.stft(y, n_fft=config.N_FFT, hop_length=config.HOP_LENGTH)
    S_mag = np.abs(S)
    S_db = librosa.amplitude_to_db(S_mag, ref=np.max)
    freqs = librosa.fft_frequencies(sr=config.SR, n_fft=config.N_FFT)
    times = librosa.frames_to_time(np.arange(S_db.shape[1]), sr=config.SR, hop_length=config.HOP_LENGTH)
    return S_db, freqs, times

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
