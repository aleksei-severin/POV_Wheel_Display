import subprocess
import numpy as np
import matplotlib.pyplot as plt
import sys
import os

if len(sys.argv) < 2:
    print("Usage: python3 spectrum.py video.mp4")
    sys.exit(1)

video = sys.argv[1]

if not os.path.exists(video):
    print(f"ERROR: file not found: {video}")
    sys.exit(1)

print(f"Reading: {video}")

cmd = [
    "ffmpeg",
    "-i", video,
    "-vn",
    "-ac", "1",
    "-ar", "48000",
    "-f", "f32le",
    "-"
]

result = subprocess.run(
    cmd,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE
)

if result.returncode != 0:
    print("FFmpeg error:")
    print(result.stderr.decode(errors="ignore"))
    sys.exit(1)

audio = np.frombuffer(result.stdout, dtype=np.float32)

print(f"Audio samples: {len(audio)}")
print(f"Duration: {len(audio) / 48000:.2f} s")

if len(audio) == 0:
    print("ERROR: no audio data")
    sys.exit(1)

# Берём первые 5 секунд
N = min(len(audio), 48000 * 5)
audio = audio[:N]

# FFT
window = np.hanning(N)
spectrum = np.fft.rfft(audio * window)

freq = np.fft.rfftfreq(N, 1 / 48000)

magnitude = 20 * np.log10(np.abs(spectrum) + 1e-12)

# Ищем пик в диапазоне 14–22 kHz
mask = (freq >= 14000) & (freq <= 22000)

peak_freq = freq[mask][np.argmax(magnitude[mask])]

print(f"Peak 14–22 kHz: {peak_freq:.1f} Hz")

# График
plt.figure(figsize=(12, 6))
plt.plot(freq, magnitude)

plt.xlim(14000, 22000)

plt.xlabel("Frequency (Hz)")
plt.ylabel("Magnitude (dB)")
plt.title(f"Audio spectrum — peak {peak_freq:.1f} Hz")

plt.grid(True)

plt.savefig("spectrum.png", dpi=150)

print("Saved: spectrum.png")