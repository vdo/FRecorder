# FRecorder Audio FX & Noise Reduction

All audio processing in FRecorder is applied to **WAV recordings only**. Effects are applied in real-time during recording (filters, gate, boost) or as post-processing after the recording stops (noise reduction).

---

## Signal Chain (recording order)

```
Mic → Gain Boost → High-Pass Filter → Low-Pass Filter → Noise Gate → WAV file → Noise Reduction
```

Real-time effects are applied sample-by-sample as audio is captured. Noise reduction runs on the finished WAV file.

---

## 1. Input Gain Boost

Amplifies the raw microphone signal before any other processing.

| Setting | Multiplier | Use case |
|---------|-----------|----------|
| Off | 1× | Default — no amplification |
| +6 dB | 2× | Quiet sources at moderate distance |
| +12 dB | 4× | Very quiet sources, distant speakers |

**How it works:** Each PCM sample is multiplied by the gain factor and clamped to 16-bit range (±32767) to prevent digital clipping.

**Recommendations:**
- Start with Off. Only increase if the waveform is visibly too small.
- +12 dB amplifies noise by 4× as well — combine with the noise gate or noise reduction to compensate.
- If you hear distortion or see the waveform hitting the rails, reduce the boost.

---

## 2. High-Pass Filter (HPF)

Removes low-frequency content below a cutoff frequency. Implemented as a second-order Butterworth (biquad) filter applied in real-time.

| Setting | Cutoff | Slope |
|---------|--------|-------|
| Off | — | — |
| HPF 80 Hz | 80 Hz | 12 dB/octave |
| HPF 120 Hz | 120 Hz | 12 dB/octave |

**How it works:** A biquad high-pass filter (Q = 0.7071, Butterworth) attenuates frequencies below the cutoff at 12 dB per octave. The filter state is maintained across audio buffers for seamless operation.

**Recommendations:**
- **80 Hz** — Removes deep rumble (HVAC, traffic, wind) while preserving bass in music and speech.
- **120 Hz** — More aggressive. Good for speech-only recordings where bass content is unwanted. Removes handling noise and proximity effect from close-miked vocals.
- Use HPF in almost all field recording situations. Low-frequency rumble is rarely useful and wastes dynamic range.

---

## 3. Low-Pass Filter (LPF)

Removes high-frequency content above a cutoff frequency. Same biquad implementation as the HPF.

| Setting | Cutoff | Slope |
|---------|--------|-------|
| Off | — | — |
| LPF 9.5 kHz | 9,500 Hz | 12 dB/octave |
| LPF 15 kHz | 15,000 Hz | 12 dB/octave |

**How it works:** A biquad low-pass filter (Q = 0.7071, Butterworth) attenuates frequencies above the cutoff.

**Recommendations:**
- **15 kHz** — Gentle rolloff. Removes ultrasonic noise and aliasing artifacts from cheap microphones while keeping all audible content.
- **9.5 kHz** — Aggressive. Useful for speech-only recordings (intelligibility lives below 8 kHz). Removes hiss, high-frequency interference, and sibilance.
- Leave Off for music or any recording where high-frequency detail matters.

---

## 4. Noise Gate

Silences the audio when the signal level drops below a threshold. Prevents recording room tone, hiss, or background noise during pauses in speech.

| Parameter | Value | Description |
|-----------|-------|-------------|
| Threshold | 400 RMS | Signal level that opens the gate |
| Hysteresis | 50% of threshold | Lower level that triggers gate closing (prevents chatter) |
| Attack | 0.1 ms | Time to fully open (nearly instant) |
| Hold | 300 ms | Time the gate stays open after signal drops below threshold |
| Release | 500 ms | Fade-out time from open to closed |

**How it works:** The gate operates as a state machine: CLOSED → ATTACK → OPEN → HOLD → RELEASE → CLOSED. When the RMS level of an audio chunk exceeds the threshold, the gate opens. When it drops below the hysteresis level, the gate enters a hold period before fading out. The envelope multiplier (0.0–1.0) is applied to every sample.

**Recommendations:**
- Best for **speech recordings** in quiet environments where you want dead silence between phrases.
- **Not recommended** for music, ambient recordings, or environments with continuous background sound — the gate will chop the audio unnaturally.
- Works well combined with HPF (remove rumble first, then gate on the cleaner signal).
- The fixed threshold (400 RMS) is tuned for typical phone microphone levels. Very quiet sources may never open the gate — use gain boost to compensate.

---

## 5. Noise Reduction (post-processing)

Spectral-gating noise reduction inspired by Audacity's Noise Reduction effect. Applied **after recording stops**, processing the entire WAV file. A progress dialog is shown during processing.

### How it works

1. **Noise profile** — The first N seconds of the recording are analyzed to build a per-frequency-bin noise profile (mean + standard deviation of spectral magnitude).
2. **Threshold** — For each frequency bin, a threshold is computed: `mean + scale × std`. The sensitivity parameter controls the scale factor.
3. **Spectral subtraction** — For each FFT frame of the full recording, bins whose magnitude is below the noise threshold are attenuated. The reduction amount is controlled by the reduction dB parameter.
4. **Frequency smoothing** — The gain mask is averaged across neighboring frequency bins to avoid musical noise (isolated tonal artifacts).
5. **Temporal smoothing** — The gain mask is smoothed over time with attack (20 ms) and release (100 ms) constants to prevent abrupt transitions.
6. **Overlap-add reconstruction** — Processed frames (2048-sample Hann-windowed, 50% overlap) are combined back into a continuous signal and written to the WAV file in-place.

### Configurable parameters

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| Reduction (dB) | 0–24 | 12.0 | How much noise to remove. Higher = more aggressive removal. |
| Sensitivity | 0–24 | 12.0 | How aggressively bins are classified as noise. Higher = more bins treated as noise. |
| Freq smoothing | 0–6 bands | 3 | Number of neighboring frequency bands averaged. Reduces musical noise artifacts. |
| Noise profile | 0.5–5.0 s | 1.0 | Duration of audio from the start used to learn the noise signature. |

### Parameter details

**Reduction (dB):**
- 0 dB — No reduction (pass-through).
- 6 dB — Light cleanup. Subtle hiss removal.
- 12 dB — Moderate reduction. Good general-purpose setting.
- 18–24 dB — Heavy reduction. May introduce artifacts on transients.

The value maps to a reduction strength multiplier: `strength = dB / 12.0`. At 12 dB, the full estimated noise floor is subtracted. At 24 dB, twice the noise floor is subtracted.

**Sensitivity:**
- 0 — Conservative. Only the most obvious noise bins are affected. Threshold = `mean + 3×std`.
- 12 — Balanced. Good default.
- 24 — Aggressive. Everything near the noise floor is treated as noise. Threshold = `mean + 0×std`. Risk of removing quiet signal content.

**Frequency smoothing:**
- 0 — No smoothing. Each bin is treated independently. May produce "musical noise" (random tonal blips).
- 3 — Default. Averages the gain mask across 3 neighboring bins on each side. Good balance.
- 6 — Maximum smoothing. Very smooth but may blur frequency detail.

**Noise profile duration:**
- The algorithm assumes the **first N seconds of the recording contain only noise** (no speech or signal). Record a moment of silence at the start.
- 1.0 s — Default. Sufficient for stationary noise (fan, hiss, hum).
- 2.0–5.0 s — Better for non-stationary noise. Captures more variation in the noise floor.
- 0.5 s — Minimum useful. Use when you can't afford a long silence at the start.

### Recommendations

- **Always record 1–2 seconds of silence** at the beginning before speaking. This gives the algorithm a clean noise profile.
- Start with defaults (12 dB / 12 sensitivity / 3 bands / 1.0 s). Adjust only if results are unsatisfactory.
- If you hear "musical noise" (watery, bubbly artifacts), increase frequency smoothing or decrease sensitivity.
- If speech sounds muffled or thin, reduce the reduction dB or sensitivity.
- Noise reduction works best on **stationary noise** (constant hiss, fan, hum). It struggles with intermittent noise (traffic, people talking nearby).
- Combine with HPF: remove low-frequency rumble with the filter during recording, then clean up remaining hiss with noise reduction after.
- Currently only supports **16-bit WAV** files. 24-bit recordings are not processed.

---

## Recommended setups

### Speech / interview in a quiet room
- HPF: 120 Hz
- LPF: Off
- Noise gate: On
- Gain boost: Off
- Noise reduction: Off (or 6 dB if there's audible hiss)

### Speech in a noisy environment (street, café)
- HPF: 80 Hz
- LPF: 15 kHz
- Noise gate: Off (continuous noise would cause choppy gating)
- Gain boost: Off or +6 dB
- Noise reduction: On (12 dB / 12 sensitivity / 3 bands / 1.0 s)

### Quiet source at distance
- HPF: 80 Hz
- LPF: Off
- Noise gate: Off
- Gain boost: +6 dB or +12 dB
- Noise reduction: On (12–18 dB / 12 sensitivity / 3 bands / 1.0 s)

### Music / ambient recording
- HPF: Off (or 80 Hz if there's rumble)
- LPF: Off
- Noise gate: Off
- Gain boost: Off
- Noise reduction: Off (or very light: 6 dB / 6 sensitivity)
