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
   - **α** (angular acceleration) is estimated from successive ω measurements. This is what keeps the image from drifting under acceleration/braking — without it the error is ½·α·T², tens of degrees on hard braking. **The estimate is smoothed adaptively (`HALL_ALPHA_K` / `HALL_ALPHA_K_FAST`), and the adaptive part is the whole point.** A plain slow filter kills the noise and the real acceleration with it — α is precisely what stops the image drifting under acceleration and braking, and lagging it by a revolution defeats it exactly where it matters. So the filter is slow on a steady spin and opens as soon as a reading disagrees with the current estimate by more than `HALL_ALPHA_FAST_DEG` of per-revolution correction. That threshold comes from a reading on real hardware, and the first attempt at deriving it was wrong by a factor of fifty: a PLL residual of ~0.3° suggested raw-α noise near 5° per revolution, so the threshold was set to 6 — and a live wheel then logged `aT2 = 0.1` on a steady spin. The sensors are orders of magnitude better than that estimate implied, so a threshold of 6 essentially never opened, the filter stayed slow permanently, and it throttled exactly the acceleration it is adaptive for. 0.5 separates the measured noise (0.1) from any real acceleration with room to spare.

**The PLL has a second stage for the same reason.** On a steady spin the residual is small and the gentle quarter-pull is right — it smears residual sensor spread instead of stepping six times a revolution. Under acceleration the residual turns *systematic*, and a first-order loop against a constant residual settles at `(1-K)/K` = 3× that residual — which is the drift seen while speeding up. Above `HALL_PLL_ERR_FAST` (1°, comfortably above the measured 0.3°) the gain rises to `HALL_PLL_K_FAST`, leaving steady-state behaviour untouched. The `aT2` field in the log is that quantity — well under the threshold on a steady spin, clearly above it while accelerating.

**The PLL also corrects velocity, not just phase (`HALL_PLL_KV`) — the second *order*, distinct from the second *stage* above.** The phase residual `err` accumulated between two Hall events is the integral of the *velocity* error over that interval, so `err / Δt_event` is that velocity error directly. Without feeding it back, `rotor_omega` is only ever recomputed once per event as `w_avg + α·T/2` — a whole revolution stale between events, worse with fewer sensors — and `fillSectorIntoBuffer()` then multiplies that stale error by `dtf`, which *includes the lead* (∝ 1/SPI clock). That is the residual "thin line rocks over several revolutions on a dead-steady spin" wobble — visible with the **Testing** cross, worse at a lower SPI clock, and *not* what `err`/`lead`/`jit` audit (those watch phase residual and lead spread, never the `ω·dtf` term). It is a hand-spun-wheel problem too: a leg can't hold RPM to a fraction of a percent, the α EMA lags the drive ripple, and the lag rides straight into the angle through the lead. The fix is a one-shot nudge `rotor_omega += (err/Δt)·HALL_PLL_KV` per event, clipped to ±10 % of ω, that does not accumulate (next event rebuilds `rotor_omega` from scratch) so the loop cannot wind up. It runs behind **the same gate as the second stage** — `HALL_PLL_ERR_FAST` < `|err|` < 10°: below the threshold the residual is sensor noise and nudging ω by it would pump that noise into the angle through `dtf` (the exact trap raw α falls into, see below), above 10° it is lost sync and the anchor hard-snaps instead. So a dead-steady motor spin is untouched; a systematic residual (acceleration, or a lagging ω estimate on a hand-spun wheel) is driven out in ~4 events — under a revolution with six sensors. `wcorr` in the `[HALL] lead …` log line is the peak nudge as a percent of ω: zero on a steady spin (gated out), single-digit percent while the α feed-forward lags hard (few sensors, or brisk acceleration).

**Why smoothing is needed at all:** Raw, it is the difference of two noisy ω readings divided by the gap between events (T/6), and `rotor_omega` then multiplies it back by T/2 — so ω's own noise re-enters the speed with a gain of about three, times another √2 for taking a difference. The acceleration correction was injecting roughly four times more jitter than it removed, which is exactly the phase wobble visible at the rim on a steady spin. α is physically slow (wheel inertia and a human leg — seconds, not milliseconds), so a time constant of about one revolution costs nothing real and cuts the noise ~3.5×.
- **`fill_us` is smoothed with the same 0.25 coefficient as `show_us`.** It is not a measurement of what happened but a *prediction* of how long the next buffer fill will take, and it goes straight into the lead: the angle shifts by `rotor_omega · fill_us`. Raw, it jumps frame to frame — blending two animation frames costs up to eight taps instead of four, and a changed brightness byte rewrites all 528 cells — and every jump landed directly on where the picture sat at the rim. `show_us` was already smoothed; `fill_us` being raw was an asymmetry, not a decision.
- **The buffer fill starts in a fixed phase of the SPI cycle, and that is a stability fix, not a scheduling detail.** Waiting for the rotor to advance by `ANGLE_MIN_STEP` only makes sense once the bus is already free; while it is still clocking out a frame there is nothing to gain by postponing, because the loop will block on it anyway — but the *moment of calculation* then drifts, and `start_in = max(fill_us, bus_busy)` flips between `fill_us` and a whole frame depending on which phase of the transfer the loop happened to reach. Measured on hardware: 525…872 µs of spread, i.e. **0.8° of wobble at the rim while the PLL residual was only 0.3°** — the phase was fine, the lead was not. The check is therefore skipped while `bus_busy > fill_us`, so the fill always begins right after the queue.
- **Re-anchoring on a Hall event must not itself break that fixed phase, and for a while it did.** `renderingTask`'s inner loop breaks out to the outer loop on every Hall event to update `anchor_deg`/`rotor_omega`/`rotor_alpha`, then immediately re-enters the same inner loop to keep rendering. The cleanup at the bottom of the outer loop used to unconditionally drain the in-flight SPI transaction (`get_trans_result`) before that happened — forcing `bus_busy = 0` for the very next frame, instead of the ≈`dma_frame_us` every other frame sees from being computed right after its own `queue_trans`. That single frame's `start_in` collapsed from ≈900 µs to ≈`fill_us` (≈550–650 µs) — a ~300 µs, *systematic* (not random) shortfall, six times a revolution, one per Hall sensor: exactly the "image nudges at every Hall event, snaps back once a revolution" wobble reported on hardware, and it lined up with the measured `lead` spread (≈360 µs / 0.72°) while `cal`/`err`/`off` all showed the phase-lock itself was fine (err 0.1°) — proof the fault was downstream of the PLL, not in it. Fixed by skipping that drain specifically when the inner loop exited for a routine Hall event (`exited_for_hall`): `tx_pending` carries over and the inner loop's own pre-`queue_trans` wait collects it, so this frame is computed at the same fixed phase as all the others. The two `blankAllLEDs_DMA()` call sites right after (rendering genuinely stopping) each drain explicitly first — `blankAllLEDs_DMA()` calls `spi_device_transmit()`, and calling that while a `queue_trans` transaction is still uncollected would let its internal `get_trans_result()` reap the wrong transaction.
- `[HALL] cal=N err=X off ...` and `[HALL] lead A..B us = J deg ...` are logged every 10 s while rendering. `cal` says whether the six-sensor anchor is live at all — until calibration converges only sensor 0 anchors, so error accumulates over a whole revolution instead of a sixth of one, and the image wobbles once per revolution rather than six times. `err` is the peak PLL residual, i.e. the phase jitter in degrees, and `off` the per-sensor offsets. Argue about stability with those numbers, not by eye.
   - **anchor** is the phase reference set by the last Hall event.
   - **anchor updates are slew-limited** (`HALL_PLL_K`, 0.25). A hard re-anchor on every event turned residual per-sensor calibration error into a phase jump 6× per revolution — six slightly rotated copies of the image, invisible at the hub, fanning out at the rim. Errors above 10° still snap hard (that is lost sync, not sensor spread).
4. `fillSectorIntoBuffer()` reads the PSRAM `frameBuffer` and builds the SK9822 DMA frame. `sector0` is **fractional**: each LED mixes the two neighbouring 1° source sectors by the fractional part, so an edge lands at a sub-degree angle instead of snapping to a whole degree. Arm `N` renders `sector0 ± 60°·N`; the sign is `global_arm_reverse` (see below). **Both faces of the arm sample the same angle `bf` by default** (`mirror_back_face == false`) — they light the same pixels for a given rotor position, so the image is identical on both sides and one side reads mirror-reversed (fine for a symmetric picture or logo). `mirror_back_face == true` feeds the **LED 44–87 face** the mirrored angle `540 - bf` while the LED 0–43 face (the one on the first-LED side of this hardware) keeps `bf` — the reflection cancels the "seen from the far side" flip so text reads correctly from both sides. Which physical face is which is a hardware fact, found by testing on a real wheel: Clock and Speed came out mirror-reversed on *both* faces at once, which means the two roles (not just one side) were swapped — `bf`/`bm` are swapped in `fillSectorIntoBuffer` to fix it. If a future hardware revision reverses it again, the symptom to watch for is the same: both faces wrong together points at a swapped role assignment, one face wrong points at something else. The flag is **per source**, published in the blanked swap window next to `frame_fmt` and re-derived on every load — never persisted: `loadFrameFromFile()` takes it from bit 15 of the ANI6 frame-count word (see Frame Buffer Format), and `effectsStart()` sets it `true` only for `Speed` and `Clock` (their text must read from both sides). Upgrading to this firmware therefore un-mirrors every animation already on the device and the four non-text effects, which were previously mirrored unconditionally — re-upload an animation with the **Mirror back face** checkbox ticked to restore a two-sided-readable file; legacy `ANI5`/`ANIM` stills stay unmirrored for good.
5. Ping-pong DMA via `spi_device_queue_trans` / `spi_device_get_trans_result` (never `spi_device_polling_*` — it holds a global spinlock and starves lwIP/WDT).

**Animation frame rate is set by the sweep, not by the revolution.** Six arms each paint their own 60° sector simultaneously, so the full 360° image is complete after 1/6 of a turn — the image refresh rate is `RPM/10` (20 Hz at 200 RPM), which is what makes 10 fps video viable down to ~100 RPM.

**The animation position is fractional, and consecutive frames are blended.** Every point on the rim is lit once per sweep, and two points either side of a 60° boundary are lit a whole sweep apart — so whatever the frame index does over one sweep appears there as a step. With an integer index that step was a *whole frame*, pinned to the six sector boundaries: on a spiral the turns visibly failed to meet. `fillSectorIntoBuffer()` therefore carries a continuous position (integer part = frame, fraction = weight of the next frame) and `boxWeightsSecBlend()` folds both frames' taps into one weight list — the per-LED loop is unchanged and the weights still sum to exactly 256. The residual step shrinks to *how much the animation moves in 1/6 of a revolution*, which is the floor: six arms cannot paint the circle in zero time. Cost is up to 8 taps instead of 4, so watch `fill` in `GET /info`. A single-frame file blends nothing and costs nothing extra. `frame_idx` is clamped against `totalFrames` on every use — a shorter file loaded underneath would otherwise address past the end of the buffer.

### Angular Resolution

The hard limit is SPI bandwidth, not the 360-sector frame: the whole strip must be re-clocked for every angular step, so `Δθ = 360 · (RPM/60) · t_frame`. `SK9822_SPI_HZ` is 20 MHz and the driver produces exactly 80 MHz / 4 there (no rounding) — a 2153-byte frame takes 861 µs → 1° at ~193 RPM, ~2° at 390 RPM. On radius 273 mm, 1° is 4.8 mm, about one LED pitch; at the hub the same angle is 0.9 mm, which is why the staircase only shows at the rim and only at speed.

**The clock is a fixed compile-time constant, not a setting.** An earlier revision made it live — a `spi_div` field in `SettingsBlob` and `PovSettings`, a "SPI clock" slider in both UIs (`POST /settings?spidiv=n`), an `applySpiDiv()` that did `spi_bus_remove_device`/`spi_bus_add_device` from `loop()` behind a `spi_reconfig_pending` render-pause, and a `g_dma_frame_us` the renderer re-read every pass. All of that is gone. `initSK9822Device()` adds the SK9822 once at boot at `SK9822_SPI_HZ`, `SK9822_FRAME_US` is a plain `#define`, and `renderingTask`'s `dma_frame_us` is `const`. The higher rungs of the `80 MHz / n` ladder were only ever a testing knob (arms 2–6 break above ~20 MHz on this hardware — see `SK9822_DUTY_POS` in [config.h](include/config.h)), so nothing real is lost. The NVS `SettingsBlob` keeps the byte as `_rsvd_spi_div`; the BLE struct dropped the field (`static_assert(sizeof(PovSettings) == 26)` and `Settings.SIZE` in `Proto.kt` moved together).

Two things keep the resolution usable:
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

**`rtc_hall_cal[]` has to reach the per-arm render angle too, not just the phase anchor — for a while it didn't.** The offset feeds `anchor_deg`'s smoothing (so the *timing* estimate is correct), but `fillSectorIntoBuffer()` computed every arm's angle as the bare `sector0 + ray·arm_step` — exactly on the nominal 60° grid, with no correction for that same arm's own measured deviation from it. That is a **static angular error, not a timing one**: fixed in size, identical every revolution, present on completely static content, and — the tell that finally pinned it down — invariant to RPM, because it isn't a race against a clock at all, it's a wrong constant. That combination (visible on a still image, tied one-for-one to which arm/sensor just fired, same every revolution, unmoved by speed) is what a genuine per-sensor calibration measurement never being applied to rendering looks like, and it doesn't show up in `err`/`lead` — those only audit how well the *smoothed* anchor tracks the raw per-event phase measurements over time, not whether the static per-arm offset got used at all. Fixed by feeding it in with a minus sign: `bf = sector0 + ray·arm_step − rtc_hall_cal[ray]` (derived from the same firing-time identity the calibration measurement itself comes from — sensor 0's own entry is always 0, so ray 0 is unaffected, and the correction is applied unconditionally, not gated on `hall_cal_ready`: even a partially-converged EMA value is strictly better than assuming a perfect 60° grid, and the acceptance gate already keeps outliers out).

`global_arm_reverse` flips the arm ordering sign. It is exposed in the web UI as **Arm Order → Flip**: if the image splits into shuffled 60° wedges, toggle it. Changing it resets the Hall calibration (the offsets live in the old coordinate convention).

**There is no manual per-arm angle trim.** An earlier revision had `global_arm_trim[NUM_ARMS]` — a hand-tuned ±15° per-arm offset on top of `rtc_hall_cal[]`, to absorb LED-board mounting tolerance that Hall timing cannot see — exposed as six sliders in both UIs (`/settings?t0..t5`, `"trim":[...]` in `GET /get_settings`, `PovSettings.arm_trim_x10[6]` on BLE, an "Arm trim" card in `DeviceScreen.kt`). All of that is gone. `fillSectorIntoBuffer()` renders each arm at `bf = sector0 + ray·arm_step − rtc_hall_cal[ray]` and nothing else. If a board is seated visibly crooked on its arm, the only remedy now is mechanical. The NVS `SettingsBlob` keeps the 24 bytes as `_rsvd_arm_trim[NUM_ARMS]` (dead space, so old blobs still load); the BLE struct dropped the field outright, so `static_assert(sizeof(PovSettings) == 26)` and `Settings.SIZE` in `Proto.kt` moved together.

### Frame Buffer Format

- **Pixel is an 8-bit index into a 256-colour RGB888 palette, and the palette is per frame.** `FRAME_STRIDE_PAL` = 768 B palette + `360 × 44` indices = **16,608 bytes**, against 31,680 for RGB565 — twice the frames in PSRAM *and less error*. RGB565 spends its quantisation evenly across all 15,840 pixels (±4/255 on R/B in every one, however few colours the frame actually has); a palette spends it only where colours are genuinely many. Measured over 10 GIFs from the library: RMSE 1.93 for the palette against 2.80 for RGB565, and 2.37 for PIL's own median cut. No dithering is used or needed — a second win, since the device blends consecutive frames and a per-frame error-diffusion pattern would show up as crawling noise.
- **The browser quantises** (`quantizeFrame()`): histogram over 6-bit-per-channel bins → median cut splitting the box with the largest *pixels × longest side* at the population-weighted median → palette entry = mean of the box computed from the **original 8-bit** values → each bin remapped to its *nearest* palette entry, not to its own box (box borders are not Voronoi borders). Bins are 6-bit, not 5-bit: at 5 bits, images with few exact colours (flat graphics, logos) put two distinct inks in one bin and averaged them.
- **On device the palette is expanded, not read per pixel.** `expandPalette()` runs the tone curve over the 256 entries into `pal_tone_r/g/b[512]` — slot 0 is the current frame, slot 1 the next one being blended in — and it only runs when the frame index, the buffer or the tone curve changes. Taps therefore address a palette by a precomputed `+0 / +PAL_COLORS` offset rather than a branch, and `boxWeightsSecBlend()` hands out the tap split (`n_a`) so a tap knows which frame it came from.
- **Animation and static image alike:** `"ANI6"` magic (4 bytes) + frame count (2, LE) + frame delay ms (2, LE) + N × `FRAME_STRIDE_PAL`. A single image is just `N = 1`; the old header-less static format is no longer written (it was identified by file size, which stopped being unambiguous once a second format existed). **Bit 15 of the frame-count word is the `mirror_back_face` flag, not part of the count** — the real count can never reach 2¹⁰ (13 MB LittleFS ÷ 16,608 ≈ 820 frames across *all* files, ~480 in PSRAM), so the top bits of that `uint16` are free. `loadFrameFromFile()` and `GET /list` mask it off with `& 0x7FFF`; the browser and Android converter set it from the "Mirror back face" upload checkbox (default off). Legacy `ANI5`/`ANIM` carry no such flag and always render unmirrored.
- **The header carries one delay for the whole animation, and the converter resamples to it.** GIF and animated WebP both allow a per-frame delay, and cinemagraphs / hand-tuned loops use it; a single `uint16` cannot. Both converters (`data/index.html` `planAnimTiming` / `_expandAnimFrames`, Android `Converter.planAnimTiming`) therefore pick a base `tick` — the most common per-frame delay, smaller on a tie — and **repeat each source frame `round(delay/tick)` times** (≥ 1). A constant-delay GIF (the common case) repeats every frame once — the file is unchanged. A variable-delay one is expanded so total loop time is preserved and the device still sees an even stream; the extra frames cost PSRAM, so the plan coarsens `tick` and then hard-caps the repeat list at the frame budget (`GET /fs_info` `psram_free`). Repeats duplicate the already-quantised frame block, not a re-sample. **Sub-threshold delays are clamped first:** a GIF/WebP frame delay below ~20 ms means "as fast as the player allows", which every browser and Android's own decoders render at 100 ms — the decoders (`MinimalGIF` / `GifDecoder`, `extractWebPFrames` / `convertWebP`) apply the same `< 20 ms → 100 ms` rule, without which such files ran several times too fast on the wheel while looking correct on the phone. Video needs none of this — it is sampled at a fixed fps and the delay is exactly `1000/fps`.
- **Legacy formats are still read, unconverted, on a second render path.** `"ANI5"` (RGB565) and header-less RGB565 stills load as-is; `"ANIM"` (RGB888, 47,520 B/frame) is converted to RGB565 at load, frame by frame through one scratch buffer. `frame_fmt` selects `samplePix8` or `samplePix565` once per arm face. Quantising legacy files on device instead is not an option: median cut over 15,840 pixels × N frames would add seconds to the black gap. `/preview` always emits RGB565 whatever the file holds, so the browser draws every thumbnail with one code path.
- All files uploaded to LittleFS must have a `.bin` extension
- The browser builds the polar buffer by **area-averaging** each 1°×LED-pitch cell over a 600×600 (400×400 for GIF and video) working canvas. Nearest-neighbour sampling frayed rim edges before the data ever reached the device — files converted by older UI builds keep those jaggies until re-uploaded.
- **Video (MP4/WebM/MOV) is a browser-side source, not a device format.** `extractVideoFrames()` steps a hidden `<video>` frame by frame (`currentTime` → `seeked` → `drawImage`) and emits the same `ANI6` file as GIF — the device never learns that video exists. Decoding is the browser's, so whatever it can play converts (H.264/VP9/AV1 everywhere, HEVC not everywhere); no demuxer library is bundled because the page is served from LittleFS and must work with no internet. Options are fps (5/10/15), crop-vs-fit, start and length; `vid_` filename prefix. **The Length field tracks fps until the user edits it by hand** (`_vidLenTouched`): PSRAM caps the frame *count*, so the seconds that fit scale inversely with fps — 15 fps might allow 40 s where 5 fps allows 120 s. Switching fps re-fills Length with `min(source, cap)` both up and down; once the user types a value there, fps changes only clamp it down when it no longer fits. The Android app mirrors this in `WheelVm` (`upLenTouched` / `defaultLengthSec`, `setFps`/`setLength`). **The Android app does not port `extractVideoFrames` line-for-line** — `MediaMetadataRetriever.getFrameAtTime` there is one seek-and-decode per frame (~3 fps), so `convert/VideoFrames.kt` runs the stream through `MediaCodec` once, sequentially (~15–30 fps), downscales each frame on the GPU (`convert/GlVideoScaler.kt`, GLES 2.0) and fans the polar-sample + quantise out to a worker pool; rotation and Crop/Fit stay on the shared `Bitmaps` path so the rim looks the same. `Converter.convertVideoSlow` is the MMR fallback when the codec or GL won't come up. **Frame orientation is decided empirically, not from metadata** — `KEY_ROTATION` is absent from the extractor format before API 29, some decoders silently apply the container rotation to their own output, and SurfaceTexture-matrix sign heuristics vary by device (those were the thing flipping frames). So a pre-pass runs the decoder to ~⅓ into the clip, grabs a frame and notes its `pts`, then pulls the reference for **that exact `pts`** from `MediaMetadataRetriever` (which returns it already display-rotated); comparing the *same frame* means the correct rotation scores ~0 per-pixel error and a wrong one scores high — unambiguous, no reliance on the metadata angle. `matchRotation` compares at native aspect (no square-squish — that confused 90° with 270°): the decoder frame is rendered in its own orientation (`codec.outputFormat` W/H + crop, never the coded size) and only the two of {0,90,180,270} that leave it the same shape as the reference are scored by SSD on the reference grid; the metadata angle only nudges genuine ties. **A 1:1 (square) video skips the fast path entirely** — `convertVideo` sees `srcW == srcH` from the MMR metadata and routes straight to `convertVideoSlow` (per-frame `MediaMetadataRetriever`, sped up with `getScaledFrameAtTime` to `SRC_SIZE_VID` on API 27+), which is exactly the always-correct path the upload/library preview already uses. The reason: for a square frame every rotation keeps the square shape, so `matchRotation`'s shape filter can't fence anything off, and the residual pixel comparison against the MMR reference is a coin-flip between 0°/180° and 90°/270° on any clip without pronounced top-bottom asymmetry (a person, a logo, most product shots) — square videos came out on their side or upside-down while the MMR preview stayed correct, through several tries at tuning that comparison (a ±20 % gate toward 0°, then closest-wins with a mean-centred `diff()` and a bigger `THUMB_LONG`). None held. MMR gets orientation right unconditionally because it returns the frame already display-rotated, so for the square minority the ~3×-slower decode is the right trade. `matchRotation`'s square branch is still there as a last resort if the metadata has no dimensions, but nothing normally reaches it. Non-square still takes the fast MediaCodec + GL path with `matchRotation` (shape filter + mean-centred `diff` on a 160-px reference thumbnail); the pre-pass then `flush()`es and `seekTo(0)`s for the real run. **The Android upload preview (`Converter.posterOf`) runs the frame through the polar transform and back (`convert/DiscRender`)** so "Add animation" shows the disc exactly as the wheel and the library thumbnail (`ui/WheelThumb`) will — Crop/Fit, letterbox bars and the hub hole included — instead of a plain square clipped to a circle. A single image / GIF / animated WebP is converted once on file-select (to show size and frame count) and the result is memoised (`_convertFileCached`, keyed by file identity + crop/mirror/fps/len) so the Upload pass reuses it instead of grinding through the polar+quantise loop a second time.
- **PSRAM, not flash, caps animation length.** The whole animation is resident, so ~8 MB of PSRAM ÷ 16,608 B ≈ **480 frames ≈ 48 s at 10 fps**, still well under the 13 MB LittleFS partition, which holds ~820 frames in total across all files. Streaming frames from flash instead is not an option — see the blanking note above.
- **The `/fs_info` PSRAM cap is measured from the *total* PSRAM heap minus a fixed reserve (`PS_RESERVE`, 640 KB — BLE's 112 KB of staged buffers, the 32 KB inflate dictionary, fragmentation headroom), *not* from what is free right now.** Only one animation is resident at a time and `loadFrameFromFile()` drops the outgoing buffer before allocating the incoming one (see below), so a new file can occupy nearly all of PSRAM regardless of what is currently on the rim — a 4 MB animation playing must not halve the size of the next upload. `psram_free` in the JSON therefore reports that usable figure, not `heap_caps_get_largest_free_block`.
- Buffer lives in PSRAM. `loadFrameFromFile()` normally frees the old buffer only *after* the new one is read (seamless), but if `ps_malloc(new)` fails while the old buffer is still up — the `old + new` peak exceeds PSRAM — it frees the old one and retries. The rim is already black on `frame_loading`, so nothing is lost visually, and any file that fit in flash then fits in PSRAM. Only a genuine over-cap file (should be impossible given the `/fs_info` maths) leaves the rim black with `[ERR] PSRAM alloc failed`.
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
- **While a client is associated with the softAP the idle limit is 5 minutes, not 60 s.** A browser can legitimately go quiet: GIF/video→polar conversion is a mostly-synchronous loop that holds the JS thread for tens of seconds on a long clip, and a phone freezes its timers the moment the screen locks. One minute of silence expired mid-upload, and the device slept out from under the user. `renderGIFFrames()` yields every few frames so the poll chain survives the conversion — but the *background* poll endpoints (`/info`, `/get_settings`, `/logs`, `/ping`) deliberately do **not** touch `last_web_activity_time` (a tab in a pocket must not keep the wheel awake forever), so surviving polls alone don't stop the sleep. The browser therefore does two things for the duration of a conversion/upload (`keepAwakeStart`/`keepAwakeStop` in [data/index.html](data/index.html)): pings `/list` every 40 s — one of the endpoints that *does* reset the timer, with only a zero-byte-file sweep as a side effect — and, since `http://192.168.4.1` is not a secure context and the Screen Wake Lock API is absent there, plays a tiny looping muted video (the NoSleep.js trick) to keep a phone screen from locking and freezing the loop outright. The 5-minute window is the remaining safety net.
- **A connected USB cable does not postpone that sleep**, and must not. While charging, an awake ESP32 eats ~100 mA of what the charger would otherwise put into the cell — in trickle mode that is the charger's entire output. Once the IP2312U reports "charged" and stops driving current, the same 100 mA comes *out of the battery*: the pack discharges to the recharge threshold, charges again, and cycles pointlessly. Asleep the board draws ~10 µA and interferes with neither. An open browser tab keeps it awake by itself through `last_web_activity_time`, so uploading files on the charger still works.

**While USB is connected, neither DCDC comes up at all — the FSM is pinned to `PWR_OFF`.** On a cable the wheel physically cannot turn, so there is nothing to measure, and raising DCDC 1 just for Hall 1 is worse than useless: the IP2312U drops to trickle charging below 3 V and supplies only 100 mA, which the extra draw eats outright. A flat cell then never climbs back over 3 V and charging stalls indefinitely. `applyPowerState()` refuses any non-`PWR_OFF` target while `pwr_cache.usb` as a second line of defence, and `setup()` samples power telemetry before the first `loop()` pass so the very first iteration already knows the cable is in.

`renderingTask` enforces the same RPM thresholds itself. It must — while rendering it preempts `loop()` (prio 1), so relying on `loop()` alone would leave the image running below threshold.

### Transport Mode (software off)

Holding **IO0 for 1.5 s** shuts the wheel down (`XPORT_HOLD_MS`). Waking is a
**single click** (`transportConfirmWake()` — ~`XPORT_WAKE_MS` of steady LOW, not
a hold): the 1.5 s is only for going *to* sleep. The whole point is still the one
line that differs from `enterDeepSleep()`: **the vibration sensor is not armed as
a wake source at all.** Ordinary idle sleep is woken by any shake, and in a bag
or on a rack the wheel shakes continuously — it woke, waited out its idle minute,
slept, and repeated until the cell was flat. Here the only wake source is the
button. Making the wake a click rather than a hold does re-open a narrower
version of the bag problem — an accidental firm press for `XPORT_WAKE_MS`+ wakes
it into *normal* mode (not transport), and then continuous shaking keeps it up —
but that is the tradeoff the shorter gesture buys; `XPORT_WAKE_MS` (80 ms)
filters ordinary brushes.

**`OP_POWEROFF` (BLE) reaches the same `enterTransportSleep()`** — the phone's
*Power off* button. The command only raises `pending_transport_off`; `loop()` runs
the shutdown, because `enterTransportSleep()` writes flash and drives the SPI wipe
and the NimBLE host task must do neither. There is deliberately **no wake over
BLE** to match: once off, only a button hold brings it back, so a wheel powered
off from a phone that then walks away stays off.

- `transport_mode` lives in RTC memory: it has to survive the very sleep it
  causes. A full power cut loses it, which is the correct escape hatch — a wheel
  with the battery reconnected boots normally (and now shows the wake wave — see
  Power-on reset below).
- **EXT0 wakes on a level, not an edge**, so two things follow. `transportSleepArm()`
  waits for the button to be released (50 ms of steady HIGH, the switch bounces)
  before sleeping, or the chip would wake in the same millisecond. And any brush
  of the button wakes the chip, so `transportConfirmWake()` re-checks it before
  anything is initialised — now it wants a steady ~`XPORT_WAKE_MS` LOW (a click),
  where it used to want the full 1.5 s hold; an unconfirmed wake still goes
  straight back to sleep without touching flash.
- **`loop()` will not count a hold until the button has been released once.**
  Coming out of transport mode *is* a press of the same length, and control reaches `loop()` with
  the button still down — without that latch, hesitating to let go would switch the
  wheel back off with the very gesture that just switched it on.
- `enterTransportSleep()` waits **200 ms** after raising `force_stop_display`,
  the same pause `safeOTAShutdown()` uses and for the same reason: flags alone do
  not undo a transaction `renderingTask` has already queued on the SPI bus.
- The blue radial wipe (`transportWipe()`, `XPORT_ANIM_MS` 500 ms, current field
  capped at `XPORT_ANIM_BRI` 9/31 ≈ 30 %) runs with **both DCDC rails up even on
  USB**. That is a deliberate exception to the never-power-the-rails-on-USB rule:
  that rule guards trickle charging against a *sustained* ~100 mA, and this is
  half a second once, after which everything powers down anyway.
- LED index 0 is the **hub** and 43 the rim (`r_mm = LED_R_INNER_MM + i·step`), so
  the shutdown wipe runs the edge downward and the wake wipe upward. The soft
  one-LED edge is not decoration: 44 hard steps read as flicker.

**Waking resumes whatever was playing** — file, slideshow or effect — on both
paths, vibration and button. Nothing new was needed for the vibration path: the
effect, the slideshow flag and its interval live in NVS settings, `last_file` in
NVS, and `slideshowActive` additionally in RTC, so `setup()` already restored
them. Transport mode broke it, though: `force_stop_display` is `RTC_DATA_ATTR`
and therefore survives sleep, and blanking the strip before the wipe is done with
exactly that flag — so the wheel came back permanently "stopped". `xport_saved_stop`
carries the pre-shutdown value across and restores it on a confirmed wake, which
also preserves the opposite case: a wheel switched off after Stop stays stopped.

`request_play_flag` is now also raised for an autostarted **effect**, not just a
file, and in both cases only while `!force_stop_display`. Without it a wheel that
slept with a live effect stood dark until someone shook it — nothing was measuring
RPM, so the render threshold was never tested; with it, raising the rails for a
display that Stop has disabled no longer burns the arms for the three seconds it
takes to fall back to `PWR_OFF`.

**IO0 is a strapping pin, and that is the one thing to verify on hardware.**
GPIO0 low at reset selects download boot on ESP32-S3. It is now a wake source for
*both* sleeps (idle EXT1 and transport EXT0), so a wake-by-button holds IO0 LOW
through the reset — if that ever selects download mode instead of running, that
is the reason. Whether a deep-sleep wake re-samples strapping is not settled by
the local headers; the fallback is a timer wake polling the button (~1.5 mA
average — far worse than 10 µA, still far better than waking on every bump).

### Wall Clock

The clock lives in **newlib system time**, not in an `epoch + millis()` pair. System time is anchored to the RTC counter, which keeps running through deep sleep and through a software reset, and ESP-IDF restores it at startup (`esp_set_time_from_rtc`). The old pair could not: `millis()` restarts at zero in a new session, so the time was lost on *every* sleep — which is every 60 s of inactivity, making the Clock effect useless.

`_currentEpoch()` rejects anything before 2023 as "never set", so if the restore ever fails the behaviour degrades to exactly what it was: `??:??:??` in the log and a bare dial until a browser connects. The browser re-syncs from `_logPoll()` whenever the device reports time 0 or drifts more than 5 s, so a power cut fixes itself as soon as any tab is open. Accuracy between syncs is that of the internal RTC RC oscillator — expect drift of seconds per hour of sleep, not milliseconds.

`_time_tz_offset` stays in RTC memory (so local time shows immediately after a wake) but is range-checked on read: it now drives a clock face, not just log lines.

### Upload Progress

`xhr.upload.progress` counts bytes handed to the **socket**, not bytes the device received. A phone's send buffer swallows a whole animation at once, so the bar jumped straight to 100 % while the transfer was still running; desktops have smaller buffers and happened to look right. The browser therefore polls `/upload_progress` every 400 ms and drives the bar from the device's own byte count, keeping the XHR event only as a fallback for firmware without the endpoint.

### Slideshow

The interval only runs while `rendering_active` — while the strip is actually lit. Rotation alone is not the right gate: the wheel can turn below the render threshold, and pictures would cycle with nothing on screen.

While paused, `slideLastSwitch` is **held at the current moment** rather than simply not being checked. Letting it sit still would bank the whole interval during the stop, and the first revolution would then skip straight past the picture the wheel stopped on — the one it is supposed to resume from. `slideCurrentIndex` lives in RTC memory for the same reason, so a deep sleep does not restart the list from the top.

**The slideshow plays a chosen subset of files *and* procedural effects.** `applySlideList(include, names, effectMask)` stores a filename list + a mode flag + a 6-bit effect mask: `include == false` — skip those names, `include == true` — play *only* those; `slideInSlideshow()` treats an empty *include* list as "no files" and an empty *exclude* list as "all files". `effectMask` bit N-1 adds `EffectId` N to the rotation. The advance loop in `loop()` builds a virtual sequence — matching files (in `savedFiles` order) then set effects — and steps `slideCurrentIndex` through it; a file entry sets `pendingFilePath`, an effect entry sets `pending_effect` (the same `fileLoaderTask` path handles both, and `effectsStart` frees the outgoing file buffer / `loadFrameFromFile` calls `effectsStop`). If the sequence is empty it holds the current frame instead of spinning. Persisted in NVS (`slidelist` string, `slidelistmode` + `slideeffmask` bytes), written by the same deferred `flush*()` path as settings and reloaded by `loadSlideList()` in `setup()` — survives sleep and a power cut; not in RTC (`setup()` rebuilds it like `savedFiles`). Set over BLE by the extended `OP_ALBUM` start payload (`POV_FEAT_ALBUM_SEL`, trailing `effMask` byte) or over the web by `/album?action=start&incl=…` / `&excl=…` / `&eff=1,3,5`.

**Nothing outside the Hall ISR may write `last_hall_time`.** The sleep-cancel path used to set it to "now" when USB was connected, as a way of resetting the idle timer. That was a lie about rotation: it zeroed the event age once a minute, the slideshow's rotation check saw a spinning wheel and advanced a file on a device sitting motionless on the charger. Idle timers belong to `last_motion_ms`.

### Trickle-Charge Recovery

Below 3 V the IP2312U drops to trickle charging at **100 mA** — which is almost exactly what an ESP32-S3 draws with Wi-Fi up (the always-on softAP keeps the receiver listening; the datasheet RX figure is 95–100 mA). Nothing reaches the cell, the voltage never climbs past 3 V, and charging deadlocks: escaping trickle mode requires a voltage the charger can never deliver.

The only fix is to remove the load entirely for the duration. Below `BATT_TRICKLE_MV` on USB the device deep-sleeps for `BATT_TRICKLE_WAKE_S`, wakes purely to read the ADC, and sleeps again — ~10 µA asleep and a few hundred ms at ~45 mA per wake, so the duty cycle costs well under 1 mA and the charger's whole 100 mA goes into the cell.

- **`enterTrickleSleep()` is deliberately separate from `enterDeepSleep()`**: the latter flushes settings and Hall calibration to NVS, and this path returns once a minute for hours. Writing flash every cycle would burn its endurance for nothing.
- **Wide hysteresis** (`BATT_TRICKLE_MV` 3050 → `BATT_TRICKLE_EXIT_MV` 3200). The wake-up measurement is unloaded; bringing Wi-Fi back up drops the terminal voltage, and without the margin the device would fall straight back into trickle.
- **Two escape hatches, because a recovering device is unreachable.** A vibration wake (EXT0) and an OTA software reset both skip the check, so shaking the wheel gets you a web window; `loop()` re-enters the sleep after 30 s of web silence, so browsing keeps it awake for as long as you need. Charging pauses while you do that — unavoidable, since access *is* the load.
- `batt_trickle_mode` lives in RTC memory: the flag must survive the sleep it causes.

### Battery State of Charge

`updateBatterySoc()` reconstructs open-circuit voltage before reading the LiPo curve; percentage is computed on-device and served as `soc`, not derived from `vbat` in the browser. Two reasons the naive terminal-voltage reading was useless: a 1S LiPo curve is flat in the middle (3.73–3.87 V spans 20–60 % SoC, so 20 mV is 6 %), and terminal voltage moves with load — the display coming on dropped the reading 15–20 %, plugging in the charger raised it 10–15 %.

```
OCV = Vbat + BATT_BASE_SAG_MV (if DCDC on) + abl_rms · batt_sag_k − BATT_CHG_RISE_MV (if charging)
```

Load current is never measured: `global_abl_rms` is *by construction* the normalised current draw (frame fill × bri/31), so only mV-of-sag-per-unit-RMS is needed. That coefficient **self-calibrates** — SoC cannot change in the instant rendering starts, so the whole voltage step against the last idle reading is sag. The idle reference expires after 120 s, past which real discharge would contaminate it. Output is slew-limited to 1 %/s so residual model error is absorbed smoothly rather than as a jump. `chg == 2` (charger reports done) pins it to 100 %.

**Zero on the scale is the cell's floor — 2.8 V of OCV — not 3.27 V.** (The shutdown threshold deliberately sits higher and is measured on the terminals; `soc` is pinned to 0 when it fires.) That was the whole of the "shows 16 % at 3.6 V, 4 % at 3.5 V, and then runs for ages" complaint: the 3.27–2.80 V stretch the wheel actually uses was being reported as 0 %. The tail below 3.6 V is also given deliberately more of the scale than pure coulomb counting would allow, because the forced power limit at the lower threshold cuts current by roughly an order of magnitude and stretches that stretch of runtime — the gauge predicts *time* left, not amp-hours. Top and middle are untouched (3.85 V is 56 % against 55 % before), so readings on a charged pack look the same as they always did.

`ocv` and `sag` are exposed in `/battery` for calibration: `ocv` should stay put when the display switches on, and `sag` shows where self-calibration settled. Retuning `BATT_OCV_MV[]`/`BATT_OCV_PCT[]` against a real pack is done through `ocv`.

### Deep-Discharge Protection

Two stages, both on **terminal** voltage — that is what the BMS watches (it cuts at 2.7 V) and what sags under load. Judging by reconstructed OCV here would be a mistake: it hides the sag and would walk the cell into the BMS cutoff.

| Vbat | action |
|---|---|
| < 3.20 V | `batt_abl_cap` forced to 5 % |
| < 3.00 V | `batt_cutoff` latched, `force_stop_display` set, power FSM drops to `PWR_OFF` |

- **`batt_abl_cap` is separate from `global_abl_limit` on purpose.** The user's limit lives in NVS and must survive a flat battery; the renderer takes `min()` of the two, and the protection never writes the stored setting.
- **Release only happens when the charger is connected.** Any voltage-based release oscillates: drop the current, the sag disappears, the voltage springs back, the protection lets go, and round it goes — brightness pulsing or a blinking display instead of an honest "battery is flat". Both flags live in RTC memory so a vibration wake cannot light a dead cell.
- Each threshold must hold for `BATT_PROT_HITS` consecutive one-second samples. Erring early is correct: our own shutdown flushes NVS, a BMS cutoff kills the board mid-write.
- `soc` is pinned to 0 once `batt_cutoff` latches — the cutoff is on terminal voltage while the percentage comes from OCV, so the gauge would otherwise still read a few percent at the moment the display goes dark.
- `/battery` reports `abl_cap` and `cutoff`, and the UI shows a badge for each. Without it, a dim or dark strip reads as a fault.

**A missed magnet pass is indistinguishable from a slow revolution, and that used to blank the display.** `rotation_period` is measured between two firings of the *same* sensor, so if one of the six misses its pass the next interval is an exact multiple — 2×, 3× — and the RPM reads half or a third of the truth. The `PWR_FULL → PWR_SPINUP` branch had no debounce at all, so one such sample cut power to arms 2–6 and bought a 2.2 s relight lockout: the display blinked once or twice a second at 130–180 rpm, less often as speed rose (a halved reading only crosses the off-threshold below 2× it), and looked stable above ~40 km/h. Three things now prevent it:

- **The ISR rejects an implausible revolution** — one more than 1.5× the current period is held back until the *next* measurement confirms it. A real slowdown is seen by all six sensors, a miss by one. This does not mask a real stop: `rpmEstimate()` also counts sensor silence, which grows on its own with no events at all.
- **`PWR_FULL → PWR_SPINUP` holds for 400 ms**, mirroring the 700 ms hold on the way up. Longer than the gap between six sensors' events at threshold speed (83 ms at 120 rpm), so one bad sample is always covered by the next good one. Measured cost on a genuine spin-down: blanking moves from ~400 ms to ~800 ms after the threshold is crossed, and `renderingTask` blanks the strip on its own `age_limit` well before that anyway.
- **`micros()` is read inside the same `noInterrupts()` block** as `last_hall_time`. `loop()` runs at priority 1 on Core 1 and is preempted by `renderingTask`; a preemption between the two reads inflated the event age and invented a speed drop.

`hall_rev_skips` counts rejected measurements and `loop()` logs `[HALL] Sensor N missed the magnet` at most once per 5 s. A sensor that keeps appearing there is a mechanical problem — magnet gap on that arm — not a firmware one.

**SK9822s power up with random PWM register contents**, so every rail that comes up lights its arm with garbage until a frame reaches it. `powerRailUpAndBlank()` therefore waits only as long as the TPS631000 needs and then sends blank frames back to back instead of waiting "with margin" and blanking once. The floor is the rail rise time plus two SPI frames (~1.7 ms at 20 MHz) — the part latches only on the *next* start frame, so one frame of zeros is never enough. This is most visible on a vibration wake, where the wheel is stationary and the flash is not smeared by rotation.

`setHallMask()` must be called on every power transition: it clears the timestamps of sensors that were unpowered, otherwise their first post-power-up event yields a bogus "revolution period".

### Procedural Effects

Frames can come from a generator instead of a file — that is the only way `Speed` can follow the wheel as you ride. [src/effects.cpp](src/effects.cpp) computes 360×44 RGB565 into its own pair of PSRAM buffers and publishes a finished frame by swapping `frameBuffer`; `frame_fmt` stays `FRAME_FMT_565`, so `renderingTask` needs no special case. Six effects: `Speed`, `Fire`, `Rainbow`, `Testing`, `Ripples`, `Clock` (`EffectId` in [include/effects.h](include/effects.h) — the web UI sends the raw number, so the two lists must stay in step).

- **`Testing` (formerly `Plasma`) is the one effect whose content is never actually generated in this file.** It exists to answer one question — is a given arm's rendered angle where it should be, relative to the others — and that question can only be answered inside `fillSectorIntoBuffer()` in `main.cpp`, because "which physical arm is rendering *right now*" is exactly the one thing this file's generator never knows: it fills a single world-fixed buffer shared by all six arms, and each arm sweeps through every world angle once a revolution regardless of which arm it is. `renderEffect()` therefore just blanks `EFF_TESTING`'s buffer (unread, but still has to stay valid) and `fillSectorIntoBuffer()` special-cases the whole per-ray body: a hub-to-rim cross at world angles 0°/90°/180°/270°, one sector wide, antialiased over the same `span` normal content uses (so drift reads at the same sharpness as everything else, not artificially crisper or softer). First 5 s of a repeating 10 s cycle draw the cross in one reference colour (blue) from every arm; the next 5 s give each of the six arms its own fixed colour — any arm whose segment of the cross doesn't line up with its neighbours' is off by exactly that many degrees, and *which* colour is off says which arm, without needing a fresh recalibration cycle to find out. This is what the per-arm `rtc_hall_cal` debugging above was ultimately building toward — a way to *see* a misalignment instead of inferring it from timing logs (there is no longer a manual trim to correct one with; a persistent seam here is now a mechanical or calibration issue).

- **Two buffers, not one.** The renderer holds the frame pointer for the duration of one sector fill; writing in place would show a half-updated frame, which on the Speed digits reads as a torn number.
- **An effect and a file cannot be live at once.** `loadFrameFromFile()` calls `effectsStop()` before it swaps, otherwise the generator keeps writing into memory the loader has just freed. `effectsStop()` also hands the PSRAM back — a 480-frame animation wants all of it.
- **`eff_mutex` guards start/stop against the generator.** Without it, the whole of `effectsStop()` fits between "generator read `effect_id`" and "generator started writing".
- **Start and stop wait for the renderer to release the frame** (up to a second in the worst case), so `/effect` only queues `pending_effect` and `fileLoaderTask` does the work — the same reason `/play` defers.
- **Fire is tuned by the spread between sectors, not by looks in one column.** While sparks were born in single sectors the spread sat near 12/255 and the effect read as an even glow; sparks are therefore seeded across an *arc* (a flame tongue is wider than one degree, and the angular blur erases anything narrower), which lifts it to ~45.
- **The Speed mask uses screen coordinates, y down** — the same convention as the browser converter. A "maths" y-up sign flips every digit upside down. The number sits above centre and `km/h` below, clear of the 49 mm hub hole.
- **`effectsStart()` sets `mirror_back_face` `true` for `Speed` and `Clock` only.** `blitMask()` renders the digits / numerals into the frame's world-fixed polar grid so they read from the first-LED side (LED 0–43 face) as drawn. The other face (LED 44–87) is seen from the far side of the wheel, so `fillSectorIntoBuffer()` feeds it `540 - bf` — otherwise the text reads reversed there. `Fire`/`Rainbow`/`Plasma`/`Ripples` have nothing to read, so they stay unmirrored (both faces light the same pixels). The flag is a plain global, so a file loaded afterwards resets it in `loadFrameFromFile()`.
- **The clock dial is built once, not per frame.** Ticks and the twelve numerals never move, so `buildClockBase()` renders them into a PSRAM frame at effect start and each frame is a `memcpy` plus three hands. The numerals use a 3×5 font because a 5×7 "12" would fill most of the ~110 mm of arc that one hour gets at that radius.
- Which direction is "up" for the digits and for 12 o'clock follows from the `angle_offset` calibration — the frame is world-fixed, but where its zero lands on the wheel is what that setting decides.

### Module Breakdown

| File | Role |
|------|------|
| [src/main.cpp](src/main.cpp) | Setup, main loop, Hall/rotor tracking, rendering, power FSM, ADC telemetry, deep sleep |
| [src/povble.cpp](src/povble.cpp) | **BLE GATT control — the default transport.** Command dispatch, telemetry, upload with inflate, OTA |
| [src/network.cpp](src/network.cpp) | WiFi (AP+STA), AsyncWebServer, file upload/playback, OTA, mDNS, web log — **opt-in, off at boot** |
| [src/effects.cpp](src/effects.cpp) | Procedural effects: generator task, frame buffers, the six effects |
| [include/config.h](include/config.h) | Pin map, display geometry, RPM thresholds, globals |
| [include/povble.h](include/povble.h) | BLE protocol: opcodes, packed structs. Mirrored byte-for-byte by `android/…/ble/Proto.kt` |
| [data/index.html](data/index.html) | Web UI served from LittleFS (also does image→polar conversion in-browser) |
| [android/](android/) | Android app (Kotlin/Compose). The primary UI; see `android/README.md` |

### BLE Transport (default), Wi-Fi (opt-in)

**The wheel advertises over BLE at boot and does not start Wi-Fi at all.** Three
reasons, all about the phone rather than the code:

1. The softAP has no route to the internet, and Android hands the whole handset's
   data over to it on connect — mobile data dies while you use the wheel.
2. A phone has one STA interface, so **two wheels cannot be reached at once**.
   Outdoors there is no shared home network to fall back on. BLE holds as many
   links as you like, which is what a bike with two wheels actually needs.
3. An AP's receiver is on continuously and costs ~100 mA — the same current the
   IP2312U delivers in trickle mode, and a large fraction of the display budget.
   BLE wakes only on the connection interval.

Wi-Fi is **not removed**, only deferred. `OP_WIFI` sets `pending_wifi_on`, and
`loop()` raises the radio — but only while `power_state == PWR_OFF`. Bringing it
up blocks for up to 10 s waiting on the STA, and during that stall `loop()` runs
neither the power FSM nor the battery protection; freezing the discharge cutoff
on a spinning wheel is not worth the convenience. Wi-Fi is what you enable to
flash from PlatformIO, i.e. on a stationary wheel. **The flag is deliberately not
persisted** — the next boot is BLE-only again.

- `setupNetwork()` was split. **`setupStorage()` runs unconditionally** and holds
  the one `prefs.begin()` in the project plus the MAC-derived `hostName`/AP SSID.
  Skipping it would silently revert every setting, the Hall calibration and the
  autoplay file to defaults on each boot — `Preferences` getters return the
  default on a closed handle rather than failing. `WiFi.macAddress()` is safe
  there: with the driver in `WIFI_MODE_NULL` it falls back to `esp_read_mac()`,
  so the name is identical to the one BLE derives and existing wheels keep it.
- `loopNetwork()` early-returns unless `wifi_enabled`, and `networkTask` is not
  even created until the radio comes up.
- `server.begin()` moved into `setupNetwork()`. The old comment about AsyncTCP
  accepting a request mid-`setup()` no longer applies: by the time `OP_WIFI`
  arrives, `setup()` has long returned.

**Protocol.** Binary, not JSON — one ATT payload is 244…514 bytes and spending it
on field names buys an extra round trip per exchange. Five characteristics: CMD
(write), RSP (notify), DATA (write-no-response), FLOW (notify), TELE (notify).
Large replies (file list, preview, log) are staged on the device and pulled by
offset with `OP_FRAG`, so a lost fragment cannot pass unnoticed.

**Upload speed** is why the format is what it is. The phone deflates the file and
the device inflates it with `tinfl_decompress` **from ROM** (`0x40000828`) — free
in flash terms, and 3–5× on palette indices, which multiplies the effective rate
by the same factor. On top: MTU 517, write-without-response, LE 2M PHY, DLE 251,
7.5–15 ms interval, and a credit window instead of an ack per packet. CRC32 over
the *decompressed* bytes is checked before the file is kept.

**Flash is written by a separate task, never by the ATT callback.** Erasing a
page takes tens of milliseconds; the NimBLE host task stalling that long drops
the link on supervision timeout. The callback only copies into a PSRAM ring.
Three constraints on that writer, each learned the hard way:

- **`vTaskDelay(1)` every chunk is mandatory.** It sits on Core 0 at priority 1;
  IDLE0 is priority 0 and — unlike IDLE1 — is still watched by the Task WDT.
  With deflate at 3–5× the ring does not empty for minutes at a time, so without
  the yield IDLE0 starves and the board panics mid-upload.
- **`WRITE_CHUNK_MAX` (4096) caps one `write()`.** The ring hands out up to 64 KB
  otherwise, and a flash write that long disables the instruction cache on both
  cores and parks `renderingTask` — the same reason `loadFrameFromFile()` chunks.
- **A stall watchdog (`XFER_STALL_MS`) aborts an abandoned transfer.** A phone
  that goes quiet without disconnecting used to leave `xfer_mode` set forever:
  the file stayed open, `OP_UP_BEGIN` answered `ST_BUSY` for good, and `bleLoop()`
  suppressed telemetry for the duration — the wheel looked dead.

`OP_OTA_BEGIN` calls the same `safeOTAShutdown()` the web path uses. Raising
`ota_in_progress` is not equivalent: that function also drains the DMA
transaction, blanks the strip, drops both DCDC rails and **unmounts LittleFS** —
without which the image is written over a mounted filesystem and takes the
animation library with it.

`bleConnected()` feeds the idle timer the same way an associated softAP station
used to: a connected phone gets the generous 5-minute window, because converting
a long clip on the handset is minutes of legitimate silence.

**NimBLE-Arduino is pinned to `~1.4.3` and that is load-bearing.** 2.x changed
every callback signature (`onWrite` gained `NimBLEConnInfo&`, `onConnect`/
`onDisconnect` lost `ble_gap_conn_desc*`), so the code does not merely warn under
2.x — it stops overriding anything. `CONFIG_BT_NIMBLE_TASK_STACK_SIZE=6144`
because `sendRsp()` puts a 520-byte buffer on the host task's stack.

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
GET  /fs_info           # JSON: {total,used,free,psram_free,frame_size}  (psram_free = PSRAM usable for one animation = total heap − 640 KB reserve, NOT current free; frame_size = FRAME_STRIDE_PAL, what a new upload will cost)
GET  /album             # Slideshow control (action=start|stop&delay=ms[&incl=a.bin,b.bin | &excl=a.bin,b.bin][&eff=1,3,5])
GET  /wifi_scan[?start=1]  # Async scan for visible networks: start=1 begins one, plain GET reports {scanning,nets}
GET  /logs?since=N      # Incremental web log
POST /settime?t=&tz=    # Browser clock sync for log timestamps
GET  /upload_progress   # JSON: {rx,total} — bytes of the current upload actually received
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

- **The network scan is blocking and runs in `networkTask`, not in the HTTP handler.** The async form silently never started: while the STA is trying to reach the stored network — which it does continuously when that network is out of range — `esp_wifi_scan_start` fails with a state error, Arduino returns −2, and the empty result read as "no networks found". `wifiScanRun()` therefore calls `WiFi.disconnect()` first **when not connected**, scans blocking, and copies the results into its own storage so the reply does not depend on when the driver frees its list. An established connection is left alone — the page may well be served over it.
- **`/wifi_scan` reports the driver's return code as `err`.** A failed scan and a genuinely empty neighbourhood need different responses from the user, and collapsing both into an empty list is what made the original bug invisible.
- Scanning still takes the radio away from the softAP for a few seconds, so the browser tolerates missed polls rather than treating them as failure, and `loopNetwork()` holds off its 30 s reconnect while a scan is pending.
- **WiFi credentials** are hardcoded in [src/network.cpp](src/network.cpp). The device always creates its own AP hotspot (`pov-wheel-XXXX`) regardless of STA connection status.
- **No floating point in ISRs** — Xtensa does not save FPU context for interrupt handlers. `hallInterruptHandler` is integer-only; all ω/α math happens in `renderingTask`.
- **Use `spi_device_queue_trans` + `spi_device_get_trans_result`, never `spi_device_polling_start`** — polling holds a global spinlock for ~433 µs and starves lwIP and the watchdog.
- **`SK9822_SPI_HZ` (compile-time, fixed at 20 MHz) sets the angular resolution, and the clock *duty cycle* is why it can't safely go higher.** The SPI clock source is APB 80 MHz with integer dividers (`f = 80 / (pre·n)`), so the achievable ladder is 40 / 26.67 / 20 / 16 / 13.3 MHz — nothing between 20 and 26.67, in particular no 25 MHz. Worse, the high phase is `h = round(duty·n/256)` APB ticks, so **an odd `n` cannot give 50 %**: 20 MHz (n=4) is a clean 25/25 ns, but 26.67 MHz (n=3) is 12.5/25 ns. The first arm is clocked straight from the ESP32 over IO_MUX (IO11/IO12 are the native SPI2 pins) and tolerates the 12.5 ns phase; downstream arms are clocked by an SK9822 CKO output through a connector and 50 mm of trace, where that phase collapses — which is why arms 2–6 break first, and why 20 MHz is the only rate this hardware runs reliably. `SK9822_DUTY_POS` flips which phase is the short one (128 → 12.5 ns high, 170 → 12.5 ns low). Actual clock and phase widths are printed at boot by `initSK9822Device()`. There is no runtime clock setting — an earlier `spi_div` slider was removed.
- **Only ADC1 pins (GPIO1–10)** may be used for analog reads; ADC2 conflicts with WiFi.
- **Idle deep sleep wakes on EXT1 `ANY_LOW`, mask `{IO0, IO15}`** — a shake (the vibration sensor pulses IO15 LOW) *or* a single button click (IO0 LOW). Level-triggered, so `enterDeepSleep()`/`enterTrickleSleep()` first spin ≤2 s for both pins to read HIGH; if the vibration sensor is still LOW (stuck) it is dropped from the mask and only the button wakes. `esp_sleep_get_ext1_wakeup_status()` says which pin fired, for the log. `user_wake` (`xport_wake || wakeup == EXT1`) means a human woke it — used to skip the on-USB trickle re-check. Transport sleep still uses EXT0 on IO0 alone (single-pin, and by design no vibration). Both DCDC enables are `gpio_hold_en`'d LOW before sleeping.
- **A power-on reset (battery reconnect / reset button) with no USB now wakes into normal mode with the wake wave** (`wake_wave` → `transportShowWave(true)`), instead of dropping straight back to deep sleep. The old "cold boot → sleep" guard was only ever on `ESP_RST_POWERON`; that is now treated as a deliberate "wake me". If nothing then happens, the normal 60 s idle timeout sleeps it anyway (one cycle, ~1 mAh), so the shelf-drain concern is bounded. Brownout/WDT resets are unaffected (they already booted through). A software reset (OTA reboot) still boots without the wave.
- **Task WDT** is detached from `loopTask` and Core 1 IDLE — `renderingTask` occupies Core 1 almost continuously while spinning.
- **Flash layout (`pov_16MB.csv`): app0/app1 1472 KB each, littlefs exactly 13.00 MB, last 64 KB unallocated.** The two app slots must be equal — the build checks the firmware against the linked slot, so an oversized slot pair would let a firmware through that then fails to OTA into the smaller one — and each must start on a 64 KB boundary. Exactly 13.00 MB of storage only falls out of 1472 KB slots, hence the 64 KB tail. 13.00 MB and not 12.94 because the web UI counts in binary megabytes and would otherwise read "12.9". Headroom left for the firmware is ~550 KB against the current ~920 KB; for scale, the entire effects module cost 9 KB.
- **Keep `pov_16MB.csv` ASCII-only.** PlatformIO's `checkprogsize` re-reads the CSV with the system codepage rather than UTF-8 and aborts the build on anything else — the failure surfaces as a `UnicodeDecodeError` from `_parse_partitions`, long after the partition binary itself was generated fine.
- **Changing the partition scheme is a USB-only operation and erases the animation library.** OTA never writes the partition table, so a firmware built against a new table runs happily on a device still carrying the old one — it just keeps the old littlefs offset and size, with no benefit until the chip is reflashed over USB. NVS survives any of this as long as its offset and size stay put.
