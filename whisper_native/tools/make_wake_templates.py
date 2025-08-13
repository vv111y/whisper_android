#!/usr/bin/env python3
"""
Generate wakeword MFCC templates matching app parameters.

Output: lines of comma-separated floats written to wake_templates.txt
Each line is one template (flattened MFCC sequence) of identical length.

Params (must match app):
- sample_rate = 16000
- frame_len = 400 (25 ms)
- hop = 160 (10 ms)
- mel_bands = 26
- mfcc_coeffs = 13
- window_frames = e.g., 60 (≈600 ms)

Usage:
  python tools/make_wake_templates.py \
    --audio_dir /path/to/wakeword/wavs \
    --output wake_templates.txt \
    --window_frames 60

Notes:
- Record clean 16 kHz mono .wav files of the wakeword (5–10 examples).
- Script trims leading/trailing silence with a simple energy gate, then centers a window of window_frames*hop around the highest energy region.
- All templates will have exactly window_frames*mfcc_coeffs floats.
"""

import argparse
import os
import sys
import numpy as np
import soundfile as sf

try:
    import librosa
except Exception as e:
    print("Error: librosa is required. pip install librosa soundfile numpy", file=sys.stderr)
    raise

SR = 16000
FRAME_LEN = 400
HOP = 160
MEL_BANDS = 26
MFCC_COEFFS = 13


def frame_energy(x, frame_len=FRAME_LEN, hop=HOP):
    n = 1 + (len(x) - frame_len) // hop if len(x) >= frame_len else 0
    if n <= 0:
        return np.array([])
    E = np.zeros(n, dtype=np.float32)
    for i in range(n):
        seg = x[i*hop:i*hop+frame_len]
        E[i] = np.sqrt(np.mean(seg**2) + 1e-12)
    return E


def extract_mfcc_window(x, window_frames):
    # pre-emphasis
    x = np.concatenate([[x[0]], x[1:] - 0.97 * x[:-1]])
    # MFCC using librosa, matching approximate pipeline
    melspec = librosa.feature.melspectrogram(
        y=x, sr=SR, n_fft=512, hop_length=HOP, win_length=FRAME_LEN,
        n_mels=MEL_BANDS, fmin=0, fmax=SR/2, window='hamming', center=False, power=2.0
    )
    melspec = np.log10(np.maximum(melspec, 1e-10))
    mfcc = librosa.feature.mfcc(S=melspec, n_mfcc=MFCC_COEFFS)
    mfcc = mfcc.T  # frames x coeffs
    if len(mfcc) < window_frames:
        # pad or bail
        pad = window_frames - len(mfcc)
        mfcc = np.pad(mfcc, ((0,pad),(0,0)), mode='edge')
    # choose a centered window around max energy
    E = frame_energy(x)
    if len(E) == 0:
        start = 0
    else:
        peak = int(np.argmax(E))
        start = max(0, peak - window_frames//2)
    end = start + window_frames
    if end > len(mfcc):
        start = max(0, len(mfcc) - window_frames)
        end = start + window_frames
    return mfcc[start:end]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--audio_dir', required=True, help='Directory with 16k mono WAV wakeword recordings')
    ap.add_argument('--output', required=True, help='Output templates txt file')
    ap.add_argument('--window_frames', type=int, default=60, help='Frames per template (e.g., 60≈600ms)')
    args = ap.parse_args()

    files = [os.path.join(args.audio_dir, f) for f in os.listdir(args.audio_dir) if f.lower().endswith('.wav')]
    files.sort()
    if not files:
        print('No wav files found in', args.audio_dir, file=sys.stderr)
        return 1

    lines = []
    for fp in files:
        x, sr = sf.read(fp)
        if sr != SR:
            x = librosa.resample(x.astype(np.float32), orig_sr=sr, target_sr=SR)
        if x.ndim > 1:
            x = np.mean(x, axis=1)
        x = x.astype(np.float32)
        tpl = extract_mfcc_window(x, args.window_frames)
        # flatten row-major
        flat = tpl.reshape(-1)
        line = ','.join(f'{v:.6f}' for v in flat)
        lines.append(line)
        print(f'Processed {os.path.basename(fp)} -> {len(flat)} floats')

    # Ensure equal length
    L = {len(l.split(',')) for l in lines}
    if len(L) != 1:
        print('Error: templates are not equal length. Check window_frames and inputs.', file=sys.stderr)
        return 2

    with open(args.output, 'w') as f:
        for line in lines:
            f.write(line + '\n')
    print('Wrote', args.output)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
