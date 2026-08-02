# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
Если мой запрос на русском языке, то отвечай тоже на русском. Комментарии к коду должны быть тоже на русском языке.
Если у тебя есть предложения, или ты не согласен с моим предложением - напиши об этом и предложи лучший вариант реализации, опиши его сильные стороны.
В конце пиши очень краткий комментарий для коммита на английском по всем внесенным в код изменениям, если таковые были.

## Project Overview

POV (Persistence of Vision) Wheel Display — an ESP32-S3 embedded system that drives 528 SK9822-A addressable LEDs across a 6-arm spinning rotor. Six Hall effect sensors (one per arm) synchronize LED rendering to rotation and detect rotation direction. Features a web UI and OTA updates.

**Hardware revision: V5.** Earlier revisions used a BQ25792 charger, BH1750 lux sensor, ICM45605 IMU, an I2C bus, a TXU0104 level shifter, a DRV5032 wake sensor, a single Hall sensor, and a configurable 1–8 arm count. All of that is gone — do not reintroduce it.

## Build & Upload Commands

```bash
# Build
pio run -e cable

# Upload via USB (firmware + LittleFS image)
pio run -e cable --target upload
pio run -e cable --target uploadfs

# Upload OTA to specific devices
pio run -e wheel_dc14 --target upload
pio run -e wheel_3 --target upload    # mDNS: pov-wheel-5e6f.local

# Serial monitor
pio device monitor -e cable -b 115200
```

No automated tests exist. Validation is done via the serial monitor and the web UI at the device IP or `http://<hostname>.local`. `pio run -e cable` is the fastest compile-level check.

## Architecture

### Rendering Pipeline

1. Six Hall sensors trigger `hallInterruptHandler()` (shared handler, sensor index passed via `attachInterruptArg`). One fixed magnet on the fork + six rotating sensors = **6 events per revolution, 60° apart**.
2. The ISR is integer-only (no FPU in ISRs on Xtensa). It records per-sensor timestamps, the full-revolution period, and votes on rotation direction from the firing order.
3. `renderingTask` (Core 1, prio 2) turns those events into a continuous rotor angle:
   `sector0 = anchor_deg + ω·Δt + ½·α·Δt² + angle_offset`
   - **ω** comes from the interval between two firings of the *same* sensor — exactly one revolution, so sensor placement error cannot bias it. Six sensors give six staggered revolution measurements per turn.
   - **α** (angular acceleration) is estimated from successive ω measurements. This is what keeps the image from drifting under acceleration/braking — without it the error is ½·α·T², tens of degrees on hard braking.
   - **anchor** is the phase reference set by the last Hall event.
4. `fillSectorIntoBuffer()` reads the PSRAM `frameBuffer` and builds the SK9822 DMA frame. Arm `N` renders `sector0 ± 60°·N`; the sign is `global_arm_reverse` (see below). The back face of each arm is mirrored (`540 - base_front`) because it is viewed from the other side of the wheel.
5. Ping-pong DMA via `spi_device_queue_trans` / `spi_device_get_trans_result` (never `spi_device_polling_*` — it holds a global spinlock and starves lwIP/WDT).

### Hall Sensor Calibration

Per-sensor mechanical/threshold spread would otherwise inject a phase jump 6× per revolution (visible ghosting). `renderingTask` auto-measures each sensor's angular offset relative to sensor 0 (`rtc_hall_cal[]`), persists it in RTC RAM + NVS (`hallcal`), and only starts anchoring on all six sensors once calibration has converged. Before that it anchors on sensor 0 only — identical to the old single-sensor behaviour.

`global_arm_reverse` flips the arm ordering sign. It is exposed in the web UI as **Arm Order → Flip**: if the image splits into shuffled 60° wedges, toggle it. Changing it resets the Hall calibration (the offsets live in the old coordinate convention).

### Frame Buffer Format

- **Static image:** `FRAME_SIZE` = `360 sectors × 44 LEDs × 3 bytes` = **47,520 bytes** of raw RGB
- **Animation:** `"ANIM"` magic (4 bytes) + frame count (2) + frame delay ms (2) + N × `FRAME_SIZE`
- All files uploaded to LittleFS must have a `.bin` extension
- Buffer lives in PSRAM; the old buffer is freed only after the new one is fully read, so playback never stalls
- Files shorter than `FRAME_SIZE` (e.g. old 41,040-byte V4 files) are zero-padded, not rejected — they will render wrong. Re-upload the source image/GIF instead.

### Power Management (two-stage)

`PowerState` in [include/config.h](include/config.h):

| State | DCDC1 (IO10) | DCDC2 (IO38) | Meaning |
|---|---|---|---|
| `PWR_OFF` | off | off | Idle, waiting for vibration or Play |
| `PWR_SPINUP` | on | off | Arm 1 + Hall 1 + light sensor powered, LEDs blanked, measuring RPM |
| `PWR_FULL` | on | on | All 6 arms and all 6 Hall sensors, rendering |

- Wake from deep sleep → `PWR_SPINUP` (only arm 1 powered, only Hall 1 counted)
- RPM ≥ `RPM_RENDER_ON` (60) → `PWR_FULL`
- RPM < `RPM_RENDER_OFF` (55) → back to `PWR_SPINUP` (5 RPM hysteresis)
- No rotation > 3 s → `PWR_OFF`; 60 s of no web/rotation/power activity → deep sleep (wake by vibration sensor only)

`renderingTask` enforces the same RPM thresholds itself. It must — while rendering it preempts `loop()` (prio 1), so relying on `loop()` alone would leave the image running below threshold.

`setHallMask()` must be called on every power transition: it clears the timestamps of sensors that were unpowered, otherwise their first post-power-up event yields a bogus "revolution period".

### Module Breakdown

| File | Role |
|------|------|
| [src/main.cpp](src/main.cpp) | Setup, main loop, Hall/rotor tracking, rendering, power FSM, ADC telemetry, deep sleep |
| [src/network.cpp](src/network.cpp) | WiFi (AP+STA), AsyncWebServer, file upload/playback, OTA, mDNS, web log |
| [include/config.h](include/config.h) | Pin map, display geometry, RPM thresholds, globals |
| [data/index.html](data/index.html) | Web UI served from LittleFS (also does image→polar conversion in-browser) |

### Web API Endpoints

```
GET  /list              # JSON list of .bin files on LittleFS
GET  /play?file=X       # Load and start playing file X
GET  /stop              # Stop rendering
GET  /delete?file=X     # Delete file from LittleFS
GET  /settings          # bmin,bmax,a,g,s,co,circ,ao,spoke,abl,rg,gg,bg
GET  /get_settings      # JSON of all settings + lux + state/file version counters
GET  /battery           # JSON: {vbat,vusb,chg,usb}  (chg: 0=discharging 1=charging 2=done)
GET  /info              # JSON: {rpm,dir,pwr}
GET  /preview?file=X    # First frame (FRAME_SIZE bytes) for browser-side thumbnail
GET  /fs_info           # LittleFS total/used/free
GET  /album             # Slideshow control (action=start|stop&delay=ms)
GET  /logs?since=N      # Incremental web log
POST /settime?t=&tz=    # Browser clock sync for log timestamps
POST /upload            # Multipart upload of .bin file to LittleFS
```

### Auto-Brightness

ALS-PT19 photodiode with a 12 kΩ load on `PIN_ADC_LIGHT` (IO9), sampled every 100 ms in `loop()` with a 10-sample moving average. `ALS_MV_AT_1000LX` (2400 mV) maps ADC millivolts to lux; brightness is scaled between `min_brightness` and `max_brightness` over 0…`LUX_FULL_SCALE`. The sensor is powered from DCDC1, so readings are only taken when `power_state != PWR_OFF`; otherwise the last value is held. Live lux is shown in the web UI next to the brightness slider for calibration.

## Key Configuration (config.h)

```c
#define NUM_ARMS            6    // fixed by hardware, no longer configurable
#define LEDS_PER_SIDE      44    // per face of the arm PCB
#define LEDS_PER_ARM       88    // both faces
#define NUM_LEDS          528
#define FRAME_SIZE      47520    // 360 × 44 × 3
#define LED_R_INNER_MM   49.0f   // radius of innermost LED
#define LED_R_OUTER_MM  273.0f   // radius of outermost LED

#define PIN_LED_DATA       11    // SK9822 SPI data
#define PIN_LED_CLK        12    // SK9822 SPI clock
#define PIN_BUTTON          0
#define PIN_VIBRATION      15    // HX 0805-C2, LOW pulses, deep-sleep wake (EXT0)
#define PIN_EN_DCDC_ARM1   10    // TPS631000 #1: arm 1 + Hall 1 + light sensor
#define PIN_EN_DCDC_REST   38    // TPS631000 #2: arms 2–6 + Halls 2–6
#define PIN_ADC_VUSB        6    // ADC1_CH5, 1:2 divider
#define PIN_ADC_VBAT        7    // ADC1_CH6, 1:2 divider
#define PIN_CHG_STAT        8    // IP2312U D2: HIGH = charge complete
#define PIN_ADC_LIGHT       9    // ADC1_CH8, ALS-PT19
#define HALL_PIN_LIST  {13, 21, 14, 18, 17, 16}   // Hall 1..6, numbered along rotation
```

## Important Caveats

- **WiFi credentials** are hardcoded in [src/network.cpp](src/network.cpp). The device always creates its own AP hotspot (`pov-wheel-XXXX`) regardless of STA connection status.
- **No floating point in ISRs** — Xtensa does not save FPU context for interrupt handlers. `hallInterruptHandler` is integer-only; all ω/α math happens in `renderingTask`.
- **Use `spi_device_queue_trans` + `spi_device_get_trans_result`, never `spi_device_polling_start`** — polling holds a global spinlock for ~433 µs and starves lwIP and the watchdog.
- **Only ADC1 pins (GPIO1–10)** may be used for analog reads; ADC2 conflicts with WiFi.
- **Deep sleep wake is EXT0 on IO15, level-triggered.** The wake level is chosen as the opposite of the pin's current state so a stuck-closed vibration switch cannot cause an immediate re-wake. Both DCDC enables are `gpio_hold_en`'d LOW before sleeping.
- **Cold boot with no USB and no vibration wake goes straight back to deep sleep** so the board does not drain the battery on a shelf. A software reset (OTA reboot) deliberately does *not*, otherwise there would be no window to reflash.
- **Task WDT** is detached from `loopTask` and Core 1 IDLE — `renderingTask` occupies Core 1 almost continuously while spinning.
- Flash partitioned as 16MB via `pov_16MB.csv`; changing the partition scheme requires re-flashing the full chip.
