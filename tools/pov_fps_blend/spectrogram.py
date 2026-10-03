import subprocess
import sys
import os
import cv2
import numpy as np
from scipy import signal
from scipy.io import wavfile


# ============================================================
# SETTINGS
# ============================================================

MAX_FREQ = 24000

# Размер FFT
NFFT = 4096

# Перекрытие FFT окон
OVERLAP = 3072

# Высота спектрограммы относительно высоты видео
SPEC_HEIGHT_RATIO = 0.30

# Прозрачность спектрограммы
SPEC_ALPHA = 0.70


# ============================================================
# INPUT
# ============================================================

if len(sys.argv) < 2:
    print("Usage:")
    print("python3 spectrogram.py video.mp4")
    sys.exit(1)

input_video = sys.argv[1]

if not os.path.isfile(input_video):
    print(f"ERROR: file not found: {input_video}")
    sys.exit(1)

base = os.path.splitext(input_video)[0]

audio_file = base + "_audio.wav"
temp_video = base + "_temp.mp4"
output_video = base + "_spectrogram.mp4"


# ============================================================
# VIDEO INFO
# ============================================================

cap = cv2.VideoCapture(input_video)

if not cap.isOpened():
    print("ERROR: cannot open video")
    sys.exit(1)

fps = cap.get(cv2.CAP_PROP_FPS)
width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))

duration = frame_count / fps

print()
print(f"Video:    {width}x{height}")
print(f"FPS:      {fps:.3f}")
print(f"Frames:   {frame_count}")
print(f"Duration: {duration:.2f} s")
print()


# ============================================================
# EXTRACT AUDIO
# ============================================================

print("Extracting audio...")

cmd = [
    "ffmpeg",
    "-y",
    "-i", input_video,
    "-vn",
    "-map", "0:a:0",
    "-ac", "1",
    "-ar", "48000",
    "-c:a", "pcm_s16le",
    audio_file
]

result = subprocess.run(
    cmd,
    stdout=subprocess.DEVNULL,
    stderr=subprocess.PIPE
)

if result.returncode != 0:
    print(result.stderr.decode(errors="ignore"))
    sys.exit(1)

print(f"Audio: {audio_file}")


# ============================================================
# LOAD AUDIO
# ============================================================

sample_rate, audio = wavfile.read(audio_file)

print(f"Audio sample rate: {sample_rate} Hz")

if audio.dtype == np.int16:
    audio = audio.astype(np.float32) / 32768.0
else:
    audio = audio.astype(np.float32)

if audio.ndim > 1:
    audio = np.mean(audio, axis=1)


# ============================================================
# SPECTROGRAM
# ============================================================

print("Calculating spectrogram...")

frequencies, times, Sxx = signal.spectrogram(
    audio,
    fs=sample_rate,
    window="hann",
    nperseg=NFFT,
    noverlap=OVERLAP,
    scaling="spectrum",
    mode="magnitude"
)

Sxx_db = 20 * np.log10(Sxx + 1e-12)

# Keep only 0–24 kHz
mask = frequencies <= MAX_FREQ

frequencies = frequencies[mask]
Sxx_db = Sxx_db[mask, :]

print(
    f"Frequency resolution: "
    f"{sample_rate / NFFT:.2f} Hz"
)

print(
    f"Spectrogram: "
    f"{len(frequencies)} × {len(times)}"
)


# ============================================================
# NORMALIZE
# ============================================================

DB_MIN = -80
DB_MAX = 0

Sxx_db = np.clip(
    Sxx_db,
    DB_MIN,
    DB_MAX
)

Sxx_norm = (
    (Sxx_db - DB_MIN)
    / (DB_MAX - DB_MIN)
    * 255
).astype(np.uint8)


# Flip vertically:
# 0 Hz at bottom
# 24 kHz at top

Sxx_norm = np.flipud(Sxx_norm)


# ============================================================
# CREATE COLOR SPECTROGRAM
# ============================================================

spectrogram_color = cv2.applyColorMap(
    Sxx_norm,
    cv2.COLORMAP_TURBO
)

spec_height = int(
    height * SPEC_HEIGHT_RATIO
)

spectrogram_color = cv2.resize(
    spectrogram_color,
    (width, spec_height),
    interpolation=cv2.INTER_AREA
)


# ============================================================
# VIDEO OUTPUT
# ============================================================

print("Rendering video...")

fourcc = cv2.VideoWriter_fourcc(*"mp4v")

out = cv2.VideoWriter(
    temp_video,
    fourcc,
    fps,
    (width, height)
)

if not out.isOpened():
    print("ERROR: cannot create output video")
    sys.exit(1)


# ============================================================
# RENDER FRAMES
# ============================================================

frame_number = 0

while True:

    ret, frame = cap.read()

    if not ret:
        break

    current_time = frame_number / fps

    # Position on spectrogram
    x = int(
        current_time / duration
        * (width - 1)
    )

    y0 = height - spec_height

    # --------------------------------------------------------
    # Spectrogram overlay
    # --------------------------------------------------------

    roi = frame[y0:height, :]

    blended = cv2.addWeighted(
        roi,
        1.0 - SPEC_ALPHA,
        spectrogram_color,
        SPEC_ALPHA,
        0
    )

    frame[y0:height, :] = blended

    # --------------------------------------------------------
    # Current time line
    # --------------------------------------------------------

    cv2.line(
        frame,
        (x, y0),
        (x, height),
        (255, 255, 255),
        2
    )

    # --------------------------------------------------------
    # Frequency labels
    # --------------------------------------------------------

    for freq in [
        0,
        5000,
        10000,
        15000,
        18000,
        20000,
        24000
    ]:

        y = int(
            height -
            (freq / MAX_FREQ)
            * spec_height
        )

        if 0 <= y < height:

            cv2.line(
                frame,
                (0, y),
                (20, y),
                (255, 255, 255),
                1
            )

            cv2.putText(
                frame,
                f"{freq / 1000:g} kHz",
                (25, y + 5),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.45,
                (255, 255, 255),
                1,
                cv2.LINE_AA
            )

    # --------------------------------------------------------
    # Time
    # --------------------------------------------------------

    cv2.putText(
        frame,
        f"{current_time:.2f} s",
        (width - 150, y0 - 10),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.5,
        (255, 255, 255),
        1,
        cv2.LINE_AA
    )

    out.write(frame)

    frame_number += 1

    if frame_number % max(1, int(fps * 2)) == 0:

        progress = (
            current_time / duration * 100
        )

        print(
            f"\rRendering: "
            f"{progress:6.1f}%",
            end=""
        )


cap.release()
out.release()

print()
print("Video rendering complete.")


# ============================================================
# ADD ORIGINAL AUDIO
# ============================================================

print("Adding original audio...")

cmd = [
    "ffmpeg",
    "-y",

    "-i", temp_video,
    "-i", input_video,

    "-map", "0:v:0",
    "-map", "1:a:0",

    "-c:v", "libx264",
    "-preset", "medium",
    "-crf", "18",

    "-c:a", "aac",
    "-b:a", "256k",

    "-shortest",

    output_video
]

result = subprocess.run(cmd)

if result.returncode != 0:
    print("ERROR: failed to add audio")
    sys.exit(1)


# ============================================================
# CLEANUP
# ============================================================

try:
    os.remove(temp_video)
    os.remove(audio_file)
except:
    pass


print()
print("========================================")
print("DONE!")
print("========================================")
print(f"Output:")
print(output_video)