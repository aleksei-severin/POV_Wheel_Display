# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
Если мой запрос на русском языке, то отвечай тоже на русском. Комментарии к коду должны быть тоже на русском языке.
Если у тебя есть предложения, или ты не согласен с моим предложением - напиши об этом и предложи лучший вариант реализации, опиши его сильные стороны.
В конце пиши очень краткий комментарий для коммита на английском по всем внесенным в код изменениям, если таковые были.

## Project Overview

POV (Persistence of Vision) Wheel Display — an ESP32-S3 embedded system that drives 528 SK9822-A addressable LEDs across a 6-arm spinning rotor. Six Hall effect sensors (one per arm) synchronize LED rendering to rotation and detect rotation direction. Features a web UI and OTA updates.

**Hardware revision: V5.** Earlier revisions used a BQ25792 charger, BH1750 lux sensor, ICM45605 IMU, an I2C bus, a TXU0104 level shifter, a DRV5032 wake sensor, a single Hall sensor, and a configurable 1–8 arm count. All of that is gone — do not reintroduce it. The arms now radiate exactly from the axis centre, so the per-LED angular correction for an off-axis spoke (the old **Hub Offset** setting, `global_spoke_offset`) is gone too: every LED on a face shares one angle, and `fillSectorIntoBuffer()` resolves the box weights once per face instead of once per LED.

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

**Animation frame rate is set by the sweep, not by the revolution.** Six arms each paint their own 60° sector simultaneously, so the full 360° image is complete after 1/6 of a turn — the image refresh rate is `RPM/10` (20 Hz at 200 RPM), which is what makes 10 fps video viable down to ~100 RPM. `fillSectorIntoBuffer()` therefore **latches `frame_idx` on the 60° boundary** instead of using wall-clock time directly: an unlatched switch mid-sweep left part of the circle on frame N and part on N+1, a visible seam at video frame rates. The latch is clamped against `totalFrames` on every use — a shorter file loaded underneath a stale latch would otherwise address past the end of the buffer.

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
RGB565 code → lut_tone5/6[] (gamma + contrast) → saturation → per-LED gain → SK9822 brightness byte
```

The tone curve is **one** curve for all three channels — two tables only because R/B carry 5 bits and G carries 6, and each is evaluated at the exact code fraction (`i/31`, `i/63`) rather than via an 8-bit intermediate, which would shift the dark end where gamma is steepest. The per-channel R/G/B gains are display white balance, not image processing, so they are applied *after* saturation — folded together with the radial compensation into `gain_r/g/b[44]` (8.8 fixed point) by `updateGainTablesIfNeeded()`. Keeping the gains inside the LUT (as earlier revisions did) made saturation operate on an already-unbalanced "white" and pull it further off neutral: with G=60 % and saturation 1.5, neutral grey came out at an effective G of 45 %, and the cast grew with the saturation slider. Folding the two gains costs nothing — the hot loop still does one multiply per channel.

`global_effective_brightness` (the SK9822 5-bit current field) is global per frame; the per-LED shaping all happens in the 8-bit PWM values.

### Radial Brightness

An LED at radius `r` spreads a constant flux over a ring of area `2πr·Δr`, so perceived brightness falls as `1/r` — the rim looks 49/273 = 0.18× as bright as the hub. The rim is already at full output, so the only fix is dimming the centre: `gain = (r / LED_R_OUTER_MM) ^ (RADIAL_GAIN_PCT/100)`, so the outermost LED is always ×1.000 and **the rim never gets dimmer** — only the hub does. Little total brightness is lost either: the lower current sum lets ABL raise `bri_level` back up. `RADIAL_GAIN_PCT` is fixed at 80 in [include/config.h](include/config.h) and is no longer adjustable from the web UI.

This is why a white frame reports ~51 % RMS with stock settings: white balance ×0.81, radial mean ×0.65. RMS is normalised *current*, not brightness — 100 % would be all 528 LEDs at full white and current 31.

### Hall Sensor Calibration

Per-sensor mechanical/threshold spread would otherwise inject a phase jump 6× per revolution (visible ghosting). `renderingTask` auto-measures each sensor's angular offset relative to sensor 0 (`rtc_hall_cal[]`), persists it in RTC RAM + NVS (`hallcal`), and only starts anchoring on all six sensors once calibration has converged. Before that it anchors on sensor 0 only — identical to the old single-sensor behaviour.

`global_arm_reverse` flips the arm ordering sign. It is exposed in the web UI as **Arm Order → Flip**: if the image splits into shuffled 60° wedges, toggle it. Changing it resets the Hall calibration (the offsets live in the old coordinate convention).

### Frame Buffer Format

- **Pixel is RGB565 little-endian** — `(r5<<11) | (g6<<5) | b5`. 5 bits on R/B is a level step of 8/255, far finer than the sub-LED edge placement the browser's area-averaging exists to produce; what 32 levels *would* wreck is a smooth gradient, so the converter dithers with a 4×4 Bayer threshold (mean over a tile stays within 0.5/255 of the true value — no DC shift, and 0→0 / 31→255 map exactly, so the white point does not move). Unpacking on device is free: the 5/6-bit code *is* the index into `lut_tone5[32]` / `lut_tone6[64]`, so `samplePix` does one aligned 16-bit load instead of three byte loads.
- **Static image:** `FRAME_SIZE` = `360 sectors × 44 LEDs × 2 bytes` = **31,680 bytes**
- **Animation:** `"ANI5"` magic (4 bytes) + frame count (2) + frame delay ms (2) + N × `FRAME_SIZE`
- **Legacy RGB888 (47,520 B/frame, `"ANIM"` magic) is converted at load**, frame by frame through one scratch buffer — the whole animation is never expanded to 888, that is the point. So the PSRAM saving applies immediately to everything already uploaded; only re-uploaded files shrink on flash. `/preview` converts too, so the browser only ever sees RGB565. Legacy conversion rounds rather than truncates (truncation would darken by half a level) and does **not** dither — the source is already quantised, so noise would only add grain.
- All files uploaded to LittleFS must have a `.bin` extension
- The browser builds the polar buffer by **area-averaging** each 1°×LED-pitch cell over a 600×600 (400×400 for GIF and video) working canvas. Nearest-neighbour sampling frayed rim edges before the data ever reached the device — files converted by older UI builds keep those jaggies until re-uploaded.
- **Video (MP4/WebM/MOV) is a browser-side source, not a device format.** `extractVideoFrames()` steps a hidden `<video>` frame by frame (`currentTime` → `seeked` → `drawImage`) and emits the same `ANI5` file as GIF — the device never learns that video exists. Decoding is the browser's, so whatever it can play converts (H.264/VP9/AV1 everywhere, HEVC not everywhere); no demuxer library is bundled because the page is served from LittleFS and must work with no internet. Options are fps (5/10/15), crop-vs-fit, start and length; `vid_` filename prefix.
- **PSRAM, not flash, caps animation length.** The whole animation is resident, so ~8 MB of PSRAM ÷ 31,680 B ≈ 240 frames ≈ **24 s at 10 fps**, well under the 12.2 MB LittleFS partition. `/fs_info` reports `psram_free` so the UI can cap frame count and warn *before* an upload that would only fail at play time. Streaming frames from flash instead is not an option — see the blanking note above.
- Buffer lives in PSRAM; the old buffer is freed only after the new one is fully read
- **Rendering is blanked for the whole load (`frame_loading`), and this is not negotiable.** Any flash operation disables the instruction cache and parks the other core, so `renderingTask` — which runs from flash and reads the frame from PSRAM — is stopped regardless. It stops *at an arbitrary angle*, leaving DMA lighting a frame captured for a different rotor position: blocky garbage on the rim. Black is the honest output. `frame_loading` is separate from `newFrameReady` on purpose — the latter drives the power FSM and would cut power to the arms. The load duration (= length of the black gap) is logged with `[DISP] Loaded`.
- **Swapping the buffer is a handshake, not a store.** The renderer addresses a frame as `frameBuffer + frame_idx·FRAME_SIZE` with `frame_idx` derived from `totalFrames`, so publishing the new `totalFrames` before the new `frameBuffer` sends it far past the end of the old allocation — blocky PSRAM garbage on the rim, worse the heavier the incoming animation. `loadFrameFromFile()` clears `newFrameReady`, waits for `render_in_fill` to drop, and only then swaps and frees. `renderingTask` raises `render_in_fill` *before* testing `newFrameReady` so the loader cannot slip into the gap.
- The read is chunked with `vTaskDelay(1)` between blocks: a multi-megabyte straight read saturates the shared MSPI controller that `renderingTask` also uses to reach PSRAM.
- **Never write NVS or flash while rendering.** A flash write disables the instruction cache on *both* cores and freezes `renderingTask` (which runs from flash) for tens of ms. `last_file` is therefore deferred via `pending_last_file` and flushed on `PWR_OFF` / before deep sleep.
- Files that match neither a known magic nor a known frame size are zero-padded to `FRAME_SIZE`, not rejected — they will render wrong. Re-upload the source image/GIF instead.

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
GET  /settings          # bmin,bmax,a,g,s,co,circ,ao,abl,rg,gg,bg
GET  /get_settings      # JSON of all settings + lux + state/file version counters
GET  /battery           # JSON: {vbat,vusb,chg,usb,soc,ocv,sag}  (chg: 0=discharging 1=charging 2=done)
GET  /info              # JSON: {rpm,dir,pwr,step,fill}  (step: °/LED update, fill: µs)
GET  /preview?file=X    # First frame (FRAME_SIZE bytes) for browser-side thumbnail
GET  /fs_info           # JSON: {total,used,free,psram_free,frame_size}  (psram_free = largest free PSRAM block)
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
#define FRAME_SIZE      31680    // 360 × 44 × 2  (RGB565)
#define FRAME_SIZE_888  47520    // старый формат, конвертируется при загрузке
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
