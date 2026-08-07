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

**Animation frame rate is set by the sweep, not by the revolution.** Six arms each paint their own 60° sector simultaneously, so the full 360° image is complete after 1/6 of a turn — the image refresh rate is `RPM/10` (20 Hz at 200 RPM), which is what makes 10 fps video viable down to ~100 RPM.

**The animation position is fractional, and consecutive frames are blended.** Every point on the rim is lit once per sweep, and two points either side of a 60° boundary are lit a whole sweep apart — so whatever the frame index does over one sweep appears there as a step. With an integer index that step was a *whole frame*, pinned to the six sector boundaries: on a spiral the turns visibly failed to meet. `fillSectorIntoBuffer()` therefore carries a continuous position (integer part = frame, fraction = weight of the next frame) and `boxWeightsSecBlend()` folds both frames' taps into one weight list — the per-LED loop is unchanged and the weights still sum to exactly 256. The residual step shrinks to *how much the animation moves in 1/6 of a revolution*, which is the floor: six arms cannot paint the circle in zero time. Cost is up to 8 taps instead of 4, so watch `fill` in `GET /info`. A single-frame file blends nothing and costs nothing extra. `frame_idx` is clamped against `totalFrames` on every use — a shorter file loaded underneath would otherwise address past the end of the buffer.

### Angular Resolution

The hard limit is SPI bandwidth, not the 360-sector frame: the whole strip must be re-clocked for every angular step, so `Δθ = 360 · (RPM/60) · t_frame`. `SK9822_SPI_HZ` requests 23 MHz but the driver only produces 80 MHz / N and rounds *down*, so the bus actually runs at 20 MHz — a 2153-byte frame takes 861 µs → 1° at ~193 RPM, ~2° at 390 RPM. On radius 273 mm, 1° is 4.8 mm, about one LED pitch; at the hub the same angle is 0.9 mm, which is why the staircase only shows at the rim and only at speed. The next step up, 26.67 MHz (80/3), buys ~34 % and is still inside the SK9822 datasheet ceiling of 30 MHz.

Three things keep it usable:
- `SK9822_SPI_HZ` — see above; the actual clock is logged at boot. End frame is `n/2` **bits**, not bytes.
- **Area sampling over the swept angle** (`boxWeightsSec()`). Each value is lit across the whole `span`, so a point sample at the box centre leaves everything above `1/(2·span)` aliased — that is what turns a straight line into a staircase. Averaging the source over `span` is a mandatory prefilter, not cosmetic blur. `span` is floored at 1° (the frame's own grid, where the filter degenerates to the old tent) and capped at `ANG_TAPS_MAX - 1`. It returns sector *numbers*, not pointers — the two frame formats share the angle maths and differ only in how a pixel is addressed.
- The angle is computed for the moment the data will actually be *lit* — after the frame has clocked out, at the middle of its display window. Without that lead the image drifts with speed.

`GET /info` returns the live `step` (degrees per LED update) and `fill` (µs to build a frame) — `step` is the artifact, `fill` must stay well under the SPI frame time or the CPU, not the bus, becomes the limit.

`ANGLE_MIN_STEP` (0.5°) paces updates: finer than the frame grid, coarse enough not to spin the CPU at low RPM.

### Colour Pipeline

Order matters and is deliberate:

```
palette index → palette RGB888 → lut_tone8[] (gamma + contrast) → saturation → per-LED gain → SK9822 brightness byte
```

The tone curve is **one** curve for all three channels. For the palette format it is a single `lut_tone8[256]` applied *to the palette*, not to pixels: `expandPalette()` folds it into `pal_tone_r/g/b[]` on a frame change (~10 Hz), so the hot loop still does one table read per channel. Legacy RGB565 frames keep `lut_tone5[32]` / `lut_tone6[64]` — two tables because R/B carry 5 bits and G carries 6, each evaluated at the exact code fraction (`i/31`, `i/63`) rather than via an 8-bit intermediate, which would shift the dark end where gamma is steepest. The palette format has no such intermediate to lose: it stores the channel in its original 8 bits. The per-channel R/G/B gains are display white balance, not image processing, so they are applied *after* saturation — folded together with the radial compensation into `gain_r/g/b[44]` (8.8 fixed point) by `updateGainTablesIfNeeded()`. Keeping the gains inside the LUT (as earlier revisions did) made saturation operate on an already-unbalanced "white" and pull it further off neutral: with G=60 % and saturation 1.5, neutral grey came out at an effective G of 45 %, and the cast grew with the saturation slider. Folding the two gains costs nothing — the hot loop still does one multiply per channel.

`global_effective_brightness` (the SK9822 5-bit current field) is global per frame; the per-LED shaping all happens in the 8-bit PWM values.

### Radial Brightness

An LED at radius `r` spreads a constant flux over a ring of area `2πr·Δr`, so perceived brightness falls as `1/r` — the rim looks 49/273 = 0.18× as bright as the hub. The rim is already at full output, so the only fix is dimming the centre: `gain = (r / LED_R_OUTER_MM) ^ (RADIAL_GAIN_PCT/100)`, so the outermost LED is always ×1.000 and **the rim never gets dimmer** — only the hub does. Little total brightness is lost either: the lower current sum lets ABL raise `bri_level` back up. `RADIAL_GAIN_PCT` is fixed at 80 in [include/config.h](include/config.h) and is no longer adjustable from the web UI.

This is why a white frame reports ~51 % RMS with stock settings: white balance ×0.81, radial mean ×0.65. RMS is normalised *current*, not brightness — 100 % would be all 528 LEDs at full white and current 31.

### Hall Sensor Calibration

Per-sensor mechanical/threshold spread would otherwise inject a phase jump 6× per revolution (visible ghosting). `renderingTask` auto-measures each sensor's angular offset relative to sensor 0 (`rtc_hall_cal[]`), persists it in RTC RAM + NVS (`hallcal`), and only starts anchoring on all six sensors once calibration has converged. Before that it anchors on sensor 0 only — identical to the old single-sensor behaviour.

`global_arm_reverse` flips the arm ordering sign. It is exposed in the web UI as **Arm Order → Flip**: if the image splits into shuffled 60° wedges, toggle it. Changing it resets the Hall calibration (the offsets live in the old coordinate convention).

### Frame Buffer Format

- **Pixel is an 8-bit index into a 256-colour RGB888 palette, and the palette is per frame.** `FRAME_STRIDE_PAL` = 768 B palette + `360 × 44` indices = **16,608 bytes**, against 31,680 for RGB565 — twice the frames in PSRAM *and less error*. RGB565 spends its quantisation evenly across all 15,840 pixels (±4/255 on R/B in every one, however few colours the frame actually has); a palette spends it only where colours are genuinely many. Measured over 10 GIFs from the library: RMSE 1.93 for the palette against 2.80 for RGB565, and 2.37 for PIL's own median cut. No dithering is used or needed — a second win, since the device blends consecutive frames and a per-frame error-diffusion pattern would show up as crawling noise.
- **The browser quantises** (`quantizeFrame()`): histogram over 6-bit-per-channel bins → median cut splitting the box with the largest *pixels × longest side* at the population-weighted median → palette entry = mean of the box computed from the **original 8-bit** values → each bin remapped to its *nearest* palette entry, not to its own box (box borders are not Voronoi borders). Bins are 6-bit, not 5-bit: at 5 bits, images with few exact colours (flat graphics, logos) put two distinct inks in one bin and averaged them.
- **On device the palette is expanded, not read per pixel.** `expandPalette()` runs the tone curve over the 256 entries into `pal_tone_r/g/b[512]` — slot 0 is the current frame, slot 1 the next one being blended in — and it only runs when the frame index, the buffer or the tone curve changes. Taps therefore address a palette by a precomputed `+0 / +PAL_COLORS` offset rather than a branch, and `boxWeightsSecBlend()` hands out the tap split (`n_a`) so a tap knows which frame it came from.
- **Animation and static image alike:** `"ANI6"` magic (4 bytes) + frame count (2) + frame delay ms (2) + N × `FRAME_STRIDE_PAL`. A single image is just `N = 1`; the old header-less static format is no longer written (it was identified by file size, which stopped being unambiguous once a second format existed).
- **Legacy formats are still read, unconverted, on a second render path.** `"ANI5"` (RGB565) and header-less RGB565 stills load as-is; `"ANIM"` (RGB888, 47,520 B/frame) is converted to RGB565 at load, frame by frame through one scratch buffer. `frame_fmt` selects `samplePix8` or `samplePix565` once per arm face. Quantising legacy files on device instead is not an option: median cut over 15,840 pixels × N frames would add seconds to the black gap. `/preview` always emits RGB565 whatever the file holds, so the browser draws every thumbnail with one code path.
- All files uploaded to LittleFS must have a `.bin` extension
- The browser builds the polar buffer by **area-averaging** each 1°×LED-pitch cell over a 600×600 (400×400 for GIF and video) working canvas. Nearest-neighbour sampling frayed rim edges before the data ever reached the device — files converted by older UI builds keep those jaggies until re-uploaded.
- **Video (MP4/WebM/MOV) is a browser-side source, not a device format.** `extractVideoFrames()` steps a hidden `<video>` frame by frame (`currentTime` → `seeked` → `drawImage`) and emits the same `ANI6` file as GIF — the device never learns that video exists. Decoding is the browser's, so whatever it can play converts (H.264/VP9/AV1 everywhere, HEVC not everywhere); no demuxer library is bundled because the page is served from LittleFS and must work with no internet. Options are fps (5/10/15), crop-vs-fit, start and length; `vid_` filename prefix.
- **PSRAM, not flash, caps animation length.** The whole animation is resident, so ~8 MB of PSRAM ÷ 16,608 B ≈ **480 frames ≈ 48 s at 10 fps**, still well under the 12.2 MB LittleFS partition. `/fs_info` reports `psram_free` so the UI can cap frame count and warn *before* an upload that would only fail at play time. Streaming frames from flash instead is not an option — see the blanking note above.
- Buffer lives in PSRAM; the old buffer is freed only after the new one is fully read
- **Rendering is blanked for the whole load (`frame_loading`), and this is not negotiable.** Any flash operation disables the instruction cache and parks the other core, so `renderingTask` — which runs from flash and reads the frame from PSRAM — is stopped regardless. It stops *at an arbitrary angle*, leaving DMA lighting a frame captured for a different rotor position: blocky garbage on the rim. Black is the honest output. `frame_loading` is separate from `newFrameReady` on purpose — the latter drives the power FSM and would cut power to the arms. The load duration (= length of the black gap) is logged with `[DISP] Loaded`.
- **Swapping the buffer is a handshake, not a store.** The renderer addresses a frame as `frameBuffer + frame_idx·stride` with `frame_idx` derived from `totalFrames` and the stride from `frame_fmt`, so publishing either the new `totalFrames` or the new `frame_fmt` before the new `frameBuffer` sends it far past the end of the old allocation — blocky PSRAM garbage on the rim, worse the heavier the incoming animation. `loadFrameFromFile()` clears `newFrameReady`, waits for `render_in_fill` to drop, and only then swaps and frees. `renderingTask` raises `render_in_fill` *before* testing `newFrameReady` so the loader cannot slip into the gap.
- The read is chunked with `vTaskDelay(1)` between blocks: a multi-megabyte straight read saturates the shared MSPI controller that `renderingTask` also uses to reach PSRAM.
- **Never write NVS or flash while rendering.** A flash write disables the instruction cache on *both* cores and freezes `renderingTask` (which runs from flash) for tens of ms. `last_file` is therefore deferred via `pending_last_file` and flushed on `PWR_OFF` / before deep sleep.
- Files that match neither a known magic nor a known frame size are zero-padded to `FRAME_SIZE` and read as RGB565, not rejected — they will render wrong. Re-upload the source image/GIF instead.
- `palette_gen` is bumped by both `loadFrameFromFile()` and `rebuildGammaLUT()`. Without it, a new file whose frame index is again 0 would keep the previous buffer's expanded palette.

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

### Wall Clock

The clock lives in **newlib system time**, not in an `epoch + millis()` pair. System time is anchored to the RTC counter, which keeps running through deep sleep and through a software reset, and ESP-IDF restores it at startup (`esp_set_time_from_rtc`). The old pair could not: `millis()` restarts at zero in a new session, so the time was lost on *every* sleep — which is every 60 s of inactivity, making the Clock effect useless.

`_currentEpoch()` rejects anything before 2023 as "never set", so if the restore ever fails the behaviour degrades to exactly what it was: `??:??:??` in the log and a bare dial until a browser connects. The browser re-syncs from `_logPoll()` whenever the device reports time 0 or drifts more than 5 s, so a power cut fixes itself as soon as any tab is open. Accuracy between syncs is that of the internal RTC RC oscillator — expect drift of seconds per hour of sleep, not milliseconds.

`_time_tz_offset` stays in RTC memory (so local time shows immediately after a wake) but is range-checked on read: it now drives a clock face, not just log lines.

### Battery State of Charge

`updateBatterySoc()` reconstructs open-circuit voltage before reading the LiPo curve; percentage is computed on-device and served as `soc`, not derived from `vbat` in the browser. Two reasons the naive terminal-voltage reading was useless: a 1S LiPo curve is flat in the middle (3.73–3.87 V spans 20–60 % SoC, so 20 mV is 6 %), and terminal voltage moves with load — the display coming on dropped the reading 15–20 %, plugging in the charger raised it 10–15 %.

```
OCV = Vbat + BATT_BASE_SAG_MV (if DCDC on) + abl_rms · batt_sag_k − BATT_CHG_RISE_MV (if charging)
```

Load current is never measured: `global_abl_rms` is *by construction* the normalised current draw (frame fill × bri/31), so only mV-of-sag-per-unit-RMS is needed. That coefficient **self-calibrates** — SoC cannot change in the instant rendering starts, so the whole voltage step against the last idle reading is sag. The idle reference expires after 120 s, past which real discharge would contaminate it. Output is slew-limited to 1 %/s so residual model error is absorbed smoothly rather than as a jump. `chg == 2` (charger reports done) pins it to 100 %.

`ocv` and `sag` are exposed in `/battery` for calibration: `ocv` should stay put when the display switches on, and `sag` shows where self-calibration settled.

**A missed magnet pass is indistinguishable from a slow revolution, and that used to blank the display.** `rotation_period` is measured between two firings of the *same* sensor, so if one of the six misses its pass the next interval is an exact multiple — 2×, 3× — and the RPM reads half or a third of the truth. The `PWR_FULL → PWR_SPINUP` branch had no debounce at all, so one such sample cut power to arms 2–6 and bought a 2.2 s relight lockout: the display blinked once or twice a second at 130–180 rpm, less often as speed rose (a halved reading only crosses the off-threshold below 2× it), and looked stable above ~40 km/h. Three things now prevent it:

- **The ISR rejects an implausible revolution** — one more than 1.5× the current period is held back until the *next* measurement confirms it. A real slowdown is seen by all six sensors, a miss by one. This does not mask a real stop: `rpmEstimate()` also counts sensor silence, which grows on its own with no events at all.
- **`PWR_FULL → PWR_SPINUP` holds for 400 ms**, mirroring the 700 ms hold on the way up. Longer than the gap between six sensors' events at threshold speed (83 ms at 120 rpm), so one bad sample is always covered by the next good one. Measured cost on a genuine spin-down: blanking moves from ~400 ms to ~800 ms after the threshold is crossed, and `renderingTask` blanks the strip on its own `age_limit` well before that anyway.
- **`micros()` is read inside the same `noInterrupts()` block** as `last_hall_time`. `loop()` runs at priority 1 on Core 1 and is preempted by `renderingTask`; a preemption between the two reads inflated the event age and invented a speed drop.

`hall_rev_skips` counts rejected measurements and `loop()` logs `[HALL] Sensor N missed the magnet` at most once per 5 s. A sensor that keeps appearing there is a mechanical problem — magnet gap on that arm — not a firmware one.

`setHallMask()` must be called on every power transition: it clears the timestamps of sensors that were unpowered, otherwise their first post-power-up event yields a bogus "revolution period".

### Procedural Effects

Frames can come from a generator instead of a file — that is the only way `Speed` can follow the wheel as you ride. [src/effects.cpp](src/effects.cpp) computes 360×44 RGB565 into its own pair of PSRAM buffers and publishes a finished frame by swapping `frameBuffer`; `frame_fmt` stays `FRAME_FMT_565`, so `renderingTask` needs no special case. Six effects: `Speed`, `Fire`, `Rainbow`, `Plasma`, `Ripples`, `Clock` (`EffectId` in [include/effects.h](include/effects.h) — the web UI sends the raw number, so the two lists must stay in step).

- **Two buffers, not one.** The renderer holds the frame pointer for the duration of one sector fill; writing in place would show a half-updated frame, which on the Speed digits reads as a torn number.
- **An effect and a file cannot be live at once.** `loadFrameFromFile()` calls `effectsStop()` before it swaps, otherwise the generator keeps writing into memory the loader has just freed. `effectsStop()` also hands the PSRAM back — a 480-frame animation wants all of it.
- **`eff_mutex` guards start/stop against the generator.** Without it, the whole of `effectsStop()` fits between "generator read `effect_id`" and "generator started writing".
- **Start and stop wait for the renderer to release the frame** (up to a second in the worst case), so `/effect` only queues `pending_effect` and `fileLoaderTask` does the work — the same reason `/play` defers.
- **Fire is tuned by the spread between sectors, not by looks in one column.** While sparks were born in single sectors the spread sat near 12/255 and the effect read as an even glow; sparks are therefore seeded across an *arc* (a flame tongue is wider than one degree, and the angular blur erases anything narrower), which lifts it to ~45.
- **The Speed mask uses screen coordinates, y down** — the same convention as the browser converter. A "maths" y-up sign flips every digit upside down. The number sits above centre and `km/h` below, clear of the 49 mm hub hole.
- **The clock dial is built once, not per frame.** Ticks and the twelve numerals never move, so `buildClockBase()` renders them into a PSRAM frame at effect start and each frame is a `memcpy` plus three hands. The numerals use a 3×5 font because a 5×7 "12" would fill most of the ~110 mm of arc that one hour gets at that radius.
- Which direction is "up" for the digits and for 12 o'clock follows from the `angle_offset` calibration — the frame is world-fixed, but where its zero lands on the wheel is what that setting decides.

### Module Breakdown

| File | Role |
|------|------|
| [src/main.cpp](src/main.cpp) | Setup, main loop, Hall/rotor tracking, rendering, power FSM, ADC telemetry, deep sleep |
| [src/network.cpp](src/network.cpp) | WiFi (AP+STA), AsyncWebServer, file upload/playback, OTA, mDNS, web log |
| [src/effects.cpp](src/effects.cpp) | Procedural effects: generator task, frame buffers, the six effects |
| [include/config.h](include/config.h) | Pin map, display geometry, RPM thresholds, globals |
| [data/index.html](data/index.html) | Web UI served from LittleFS (also does image→polar conversion in-browser) |

### Web API Endpoints

```
GET  /list              # JSON list of .bin files on LittleFS
GET  /play?file=X       # Load and start playing file X
GET  /stop              # Stop rendering
GET  /delete?file=X     # Delete file from LittleFS
GET  /settings          # bmin,bmax,a,g,s,co,circ,ao,abl,rg,gg,bg
GET  /get_settings      # JSON of all settings + lux + effect/speed_red + state/file version counters
GET  /battery           # JSON: {vbat,vusb,chg,usb,soc,ocv,sag}  (chg: 0=discharging 1=charging 2=done)
GET  /info              # JSON: {rpm,dir,pwr,step,fill,kmh}  (step: °/LED update, fill: µs)
GET  /effect?id=N       # Procedural effect: 0 = off, 1..6 = EffectId. &speed_red=NN sets the red point
GET  /preview?file=X    # First frame (FRAME_SIZE bytes) for browser-side thumbnail
GET  /fs_info           # JSON: {total,used,free,psram_free,frame_size}  (psram_free = largest free PSRAM block; frame_size = FRAME_STRIDE_PAL, what a new upload will cost)
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
#define FRAME_STRIDE_PAL 16608   // 768 палитра + 360 × 44 индексов — основной формат
#define FRAME_SIZE      31680    // 360 × 44 × 2  (RGB565) — старые файлы
#define FRAME_SIZE_888  47520    // ещё более старый формат, конвертируется при загрузке
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
