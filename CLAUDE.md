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
   - **anchor updates are slew-limited** (`HALL_PLL_K`, 0.25). A hard re-anchor on every event turned residual per-sensor calibration error into a phase jump 6× per revolution — six slightly rotated copies of the image, invisible at the hub, fanning out at the rim. Errors above 10° still snap hard (that is lost sync, not sensor spread).
4. `fillSectorIntoBuffer()` reads the PSRAM `frameBuffer` and builds the SK9822 DMA frame. `sector0` is **fractional**: each LED mixes the two neighbouring 1° source sectors by the fractional part, so an edge lands at a sub-degree angle instead of snapping to a whole degree. Arm `N` renders `sector0 ± 60°·N`; the sign is `global_arm_reverse` (see below). The back face of each arm is mirrored (`540 - base_front`) because it is viewed from the other side of the wheel.
5. Ping-pong DMA via `spi_device_queue_trans` / `spi_device_get_trans_result` (never `spi_device_polling_*` — it holds a global spinlock and starves lwIP/WDT).

### Angular Resolution

The hard limit is SPI bandwidth, not the 360-sector frame: the whole strip must be re-clocked for every angular step, so `Δθ = 360 · (RPM/60) · t_frame`. `SK9822_SPI_HZ` requests 23 MHz but the driver only produces 80 MHz / N and rounds *down*, so the bus actually runs at 20 MHz — a 2153-byte frame takes 861 µs → 1° at ~193 RPM, ~2° at 390 RPM. On radius 273 mm, 1° is 4.8 mm, about one LED pitch; at the hub the same angle is 0.9 mm, which is why the staircase only shows at the rim and only at speed. The next step up, 26.67 MHz (80/3), buys ~34 % and is still inside the SK9822 datasheet ceiling of 30 MHz.

Three things keep it usable:
- `SK9822_SPI_HZ` — see above; the actual clock is logged at boot. End frame is `n/2` **bits**, not bytes.
- **Area sampling over the swept angle** (`boxWeights()`). Each value is lit across the whole `span`, so a point sample at the box centre leaves everything above `1/(2·span)` aliased — that is what turns a straight line into a staircase. Averaging the source over `span` is a mandatory prefilter, not cosmetic blur. `span` is floored at 1° (the frame's own grid, where the filter degenerates to the old tent) and capped at `ANG_TAPS_MAX - 1`.
- The angle is computed for the moment the data will actually be *lit* — after the frame has clocked out, at the middle of its display window. Without that lead the image drifts with speed.

`GET /info` returns the live `step` (degrees per LED update) and `fill` (µs to build a frame) — `step` is the artifact, `fill` must stay well under the SPI frame time or the CPU, not the bus, becomes the limit.

`ANGLE_MIN_STEP` (0.5°) paces updates: finer than the frame grid, coarse enough not to spin the CPU at low RPM.

### Colour Pipeline

Order matters and is deliberate:

```
source → lut_tone[] (gamma + contrast) → saturation → per-LED gain → SK9822 brightness byte
```

`lut_tone` is **one** table for all three channels. The per-channel R/G/B gains are display white balance, not image processing, so they are applied *after* saturation — folded together with the radial compensation into `gain_r/g/b[44]` (8.8 fixed point) by `updateGainTablesIfNeeded()`. Keeping the gains inside the LUT (as earlier revisions did) made saturation operate on an already-unbalanced "white" and pull it further off neutral: with G=60 % and saturation 1.5, neutral grey came out at an effective G of 45 %, and the cast grew with the saturation slider. Folding the two gains costs nothing — the hot loop still does one multiply per channel.

`global_effective_brightness` (the SK9822 5-bit current field) is global per frame; the per-LED shaping all happens in the 8-bit PWM values.

### Radial Brightness

An LED at radius `r` spreads a constant flux over a ring of area `2πr·Δr`, so perceived brightness falls as `1/r` — the rim looks 49/273 = 0.18× as bright as the hub. The rim is already at full output, so the only fix is dimming the centre: `gain = (r / LED_R_OUTER_MM) ^ (global_radial_gain/100)`, so the outermost LED is always ×1.000 and **the rim never gets dimmer** — only the hub does. Little total brightness is lost either: the lower current sum lets ABL raise `bri_level` back up. Exposed as **Radial Gain** in the web UI (0 = off, 100 = full physical compensation).

This is why a white frame reports ~51 % RMS with stock settings: white balance ×0.81, radial mean ×0.65. RMS is normalised *current*, not brightness — 100 % would be all 528 LEDs at full white and current 31.

### Hall Sensor Calibration

Per-sensor mechanical/threshold spread would otherwise inject a phase jump 6× per revolution (visible ghosting). `renderingTask` auto-measures each sensor's angular offset relative to sensor 0 (`rtc_hall_cal[]`), persists it in RTC RAM + NVS (`hallcal`), and only starts anchoring on all six sensors once calibration has converged. Before that it anchors on sensor 0 only — identical to the old single-sensor behaviour.

`global_arm_reverse` flips the arm ordering sign. It is exposed in the web UI as **Arm Order → Flip**: if the image splits into shuffled 60° wedges, toggle it. Changing it resets the Hall calibration (the offsets live in the old coordinate convention).

### Frame Buffer Format

- **Static image:** `FRAME_SIZE` = `360 sectors × 44 LEDs × 3 bytes` = **47,520 bytes** of raw RGB
- **Animation:** `"ANIM"` magic (4 bytes) + frame count (2) + frame delay ms (2) + N × `FRAME_SIZE`
- All files uploaded to LittleFS must have a `.bin` extension
- The browser builds the polar buffer by **area-averaging** each 1°×LED-pitch cell over a 600×600 (400×400 for GIF) working canvas. Nearest-neighbour sampling frayed rim edges before the data ever reached the device — files converted by older UI builds keep those jaggies until re-uploaded.
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
- RPM ≥ `RPM_RENDER_ON` (120) → `PWR_FULL`
- RPM < `RPM_RENDER_OFF` (100) → back to `PWR_SPINUP` (20 RPM hysteresis)
- No rotation > 3 s → `PWR_OFF`; 60 s of no web/rotation/power activity → deep sleep (wake by vibration sensor only)

`renderingTask` enforces the same RPM thresholds itself. It must — while rendering it preempts `loop()` (prio 1), so relying on `loop()` alone would leave the image running below threshold.

### Battery State of Charge

`updateBatterySoc()` reconstructs open-circuit voltage before reading the LiPo curve; percentage is computed on-device and served as `soc`, not derived from `vbat` in the browser. Two reasons the naive terminal-voltage reading was useless: a 1S LiPo curve is flat in the middle (3.73–3.87 V spans 20–60 % SoC, so 20 mV is 6 %), and terminal voltage moves with load — the display coming on dropped the reading 15–20 %, plugging in the charger raised it 10–15 %.

```
OCV = Vbat + BATT_BASE_SAG_MV (if DCDC on) + abl_rms · batt_sag_k − BATT_CHG_RISE_MV (if charging)
```

Load current is never measured: `global_abl_rms` is *by construction* the normalised current draw (frame fill × bri/31), so only mV-of-sag-per-unit-RMS is needed. That coefficient **self-calibrates** — SoC cannot change in the instant rendering starts, so the whole voltage step against the last idle reading is sag. The idle reference expires after 120 s, past which real discharge would contaminate it. Output is slew-limited to 1 %/s so residual model error is absorbed smoothly rather than as a jump. `chg == 2` (charger reports done) pins it to 100 %.

`ocv` and `sag` are exposed in `/battery` for calibration: `ocv` should stay put when the display switches on, and `sag` shows where self-calibration settled.

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
GET  /settings          # bmin,bmax,a,g,s,co,circ,ao,spoke,abl,rad,rg,gg,bg
GET  /get_settings      # JSON of all settings + lux + state/file version counters
GET  /battery           # JSON: {vbat,vusb,chg,usb,soc,ocv,sag}  (chg: 0=discharging 1=charging 2=done)
GET  /info              # JSON: {rpm,dir,pwr,step,fill}  (step: °/LED update, fill: µs)
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
- **`SK9822_SPI_HZ` is the angular-resolution knob, and the clock *duty cycle* is what limits it.** The SPI clock source is APB 80 MHz with integer dividers (`f = 80 / (pre·n)`), so the achievable ladder is 40 / 26.67 / 20 / 16 / 13.3 MHz — nothing between 20 and 26.67, in particular no 25 MHz. Worse, the high phase is `h = round(duty·n/256)` APB ticks, so **an odd `n` cannot give 50 %**: 20 MHz (n=4) is a clean 25/25 ns, but 26.67 MHz (n=3) is 12.5/25 ns. The first arm is clocked straight from the ESP32 over IO_MUX (IO11/IO12 are the native SPI2 pins) and tolerates the 12.5 ns phase; downstream arms are clocked by an SK9822 CKO output through a connector and 50 mm of trace, where that phase collapses — which is why arms 2–6 break first. `SK9822_DUTY_POS` flips which phase is the short one (128 → 12.5 ns high, 170 → 12.5 ns low). Phase widths are printed at boot.
- **Only ADC1 pins (GPIO1–10)** may be used for analog reads; ADC2 conflicts with WiFi.
- **Deep sleep wake is EXT0 on IO15, level-triggered.** The wake level is chosen as the opposite of the pin's current state so a stuck-closed vibration switch cannot cause an immediate re-wake. Both DCDC enables are `gpio_hold_en`'d LOW before sleeping.
- **Cold boot with no USB and no vibration wake goes straight back to deep sleep** so the board does not drain the battery on a shelf. A software reset (OTA reboot) deliberately does *not*, otherwise there would be no window to reflash.
- **Task WDT** is detached from `loopTask` and Core 1 IDLE — `renderingTask` occupies Core 1 almost continuously while spinning.
- Flash partitioned as 16MB via `pov_16MB.csv`; changing the partition scheme requires re-flashing the full chip.
