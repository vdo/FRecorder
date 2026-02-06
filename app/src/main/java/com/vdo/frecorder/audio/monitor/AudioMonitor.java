/*
 * Copyright 2024 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.vdo.frecorder.audio.monitor;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import timber.log.Timber;

/**
 * AudioMonitor provides real-time audio monitoring during recording.
 *
 * Based on the proven LoopbackLive pattern: feedAudio() is called directly
 * from the recording thread and writes synchronously to AudioTrack using
 * the same buffer. No separate playback thread, no queue, no cloning.
 * This is the simplest and most reliable approach for real-time audio loopback.
 */
public class AudioMonitor {

    private AudioTrack audioTrack;
    private AudioRecord standaloneRecorder;
    private Thread standaloneThread;
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    private final AtomicBoolean isStandalone = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicInteger feedCount = new AtomicInteger(0);
    private final AtomicInteger writeCount = new AtomicInteger(0);

    private int sampleRate = 44100;
    private int channelCount = 1;
    private AudioDeviceInfo inputDevice = null;
    private String lastError = null;
    private Context appContext = null;

    // Noise gate state
    private enum GateState { CLOSED, ATTACK, OPEN, HOLD, RELEASE }
    private volatile boolean noiseGateEnabled = false;
    private GateState gateState = GateState.CLOSED;
    private float gateEnvelope = 0f;
    private long holdCounter = 0;
    private float attackCoeff;
    private float releaseCoeff;
    private long holdSamples;
    private int gateThreshold;
    private int gateHysteresis;

    // Biquad filter state for monitor path
    private volatile int hpfMode = com.vdo.frecorder.AppConstants.HPF_OFF;
    private volatile int lpfMode = com.vdo.frecorder.AppConstants.LPF_OFF;
    private double hpfX1, hpfX2, hpfY1, hpfY2;
    private double lpfX1, lpfX2, lpfY1, lpfY2;
    private double[] hpfCoeffs = null;
    private double[] lpfCoeffs = null;

    // Gain boost for monitor path
    private volatile int gainBoostLevel = com.vdo.frecorder.AppConstants.GAIN_BOOST_OFF;

    private static class AudioMonitorSingletonHolder {
        private static final AudioMonitor singleton = new AudioMonitor();
    }

    public static AudioMonitor getInstance() {
        return AudioMonitorSingletonHolder.singleton;
    }

    private AudioMonitor() {}

    /**
     * Initialize the audio monitor with recording parameters.
     */
    public void initialize(int sampleRate, int channelCount) {
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.lastError = null;
        initNoiseGate();
        initFilters();
        Timber.d("AudioMonitor.initialize: sampleRate=%d, channels=%d", sampleRate, channelCount);
    }

    private void initFilters() {
        hpfX1 = hpfX2 = hpfY1 = hpfY2 = 0;
        lpfX1 = lpfX2 = lpfY1 = lpfY2 = 0;
        hpfCoeffs = computeHpfCoeffs();
        lpfCoeffs = computeLpfCoeffs();
    }

    public void setHpfMode(int mode) {
        this.hpfMode = mode;
        initFilters();
    }

    public void setLpfMode(int mode) {
        this.lpfMode = mode;
        initFilters();
    }

    public int getHpfMode() { return hpfMode; }
    public int getLpfMode() { return lpfMode; }

    public void setGainBoostLevel(int level) {
        this.gainBoostLevel = level;
    }

    public int getGainBoostLevel() { return gainBoostLevel; }

    private float getGainMultiplier() {
        switch (gainBoostLevel) {
            case com.vdo.frecorder.AppConstants.GAIN_BOOST_6DB:
                return com.vdo.frecorder.AppConstants.GAIN_BOOST_MULTIPLIER_6DB;
            case com.vdo.frecorder.AppConstants.GAIN_BOOST_12DB:
                return com.vdo.frecorder.AppConstants.GAIN_BOOST_MULTIPLIER_12DB;
            default:
                return 1.0f;
        }
    }

    private void applyGainBoost(byte[] pcmData, int length) {
        float multiplier = getGainMultiplier();
        if (multiplier <= 1.0f) return;
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            int amplified = (int) (sample * multiplier);
            if (amplified > Short.MAX_VALUE) amplified = Short.MAX_VALUE;
            if (amplified < Short.MIN_VALUE) amplified = Short.MIN_VALUE;
            pcmData[i] = (byte) (amplified & 0xFF);
            pcmData[i + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
    }

    private double[] computeHpfCoeffs() {
        float freq;
        switch (hpfMode) {
            case com.vdo.frecorder.AppConstants.HPF_80: freq = com.vdo.frecorder.AppConstants.HPF_FREQ_80; break;
            case com.vdo.frecorder.AppConstants.HPF_120: freq = com.vdo.frecorder.AppConstants.HPF_FREQ_120; break;
            default: return null;
        }
        return biquadHighPass(freq, sampleRate, 0.7071);
    }

    private double[] computeLpfCoeffs() {
        float freq;
        switch (lpfMode) {
            case com.vdo.frecorder.AppConstants.LPF_9500: freq = com.vdo.frecorder.AppConstants.LPF_FREQ_9500; break;
            case com.vdo.frecorder.AppConstants.LPF_15000: freq = com.vdo.frecorder.AppConstants.LPF_FREQ_15000; break;
            default: return null;
        }
        return biquadLowPass(freq, sampleRate, 0.7071);
    }

    private static double[] biquadHighPass(double fc, double fs, double Q) {
        double w0 = 2.0 * Math.PI * fc / fs;
        double alpha = Math.sin(w0) / (2.0 * Q);
        double cosw0 = Math.cos(w0);
        double b0 = (1.0 + cosw0) / 2.0;
        double b1 = -(1.0 + cosw0);
        double b2 = (1.0 + cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        return new double[]{b0/a0, b1/a0, b2/a0, a1/a0, a2/a0};
    }

    private static double[] biquadLowPass(double fc, double fs, double Q) {
        double w0 = 2.0 * Math.PI * fc / fs;
        double alpha = Math.sin(w0) / (2.0 * Q);
        double cosw0 = Math.cos(w0);
        double b0 = (1.0 - cosw0) / 2.0;
        double b1 = 1.0 - cosw0;
        double b2 = (1.0 - cosw0) / 2.0;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosw0;
        double a2 = 1.0 - alpha;
        return new double[]{b0/a0, b1/a0, b2/a0, a1/a0, a2/a0};
    }

    private void applyFilters(byte[] pcmData, int length) {
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));

            if (hpfCoeffs != null) {
                double x = sample;
                double y = hpfCoeffs[0]*x + hpfCoeffs[1]*hpfX1 + hpfCoeffs[2]*hpfX2 - hpfCoeffs[3]*hpfY1 - hpfCoeffs[4]*hpfY2;
                hpfX2 = hpfX1; hpfX1 = x;
                hpfY2 = hpfY1; hpfY1 = y;
                int clamped = (int) Math.round(y);
                if (clamped > Short.MAX_VALUE) clamped = Short.MAX_VALUE;
                if (clamped < Short.MIN_VALUE) clamped = Short.MIN_VALUE;
                sample = (short) clamped;
            }

            if (lpfCoeffs != null) {
                double x = sample;
                double y = lpfCoeffs[0]*x + lpfCoeffs[1]*lpfX1 + lpfCoeffs[2]*lpfX2 - lpfCoeffs[3]*lpfY1 - lpfCoeffs[4]*lpfY2;
                lpfX2 = lpfX1; lpfX1 = x;
                lpfY2 = lpfY1; lpfY1 = y;
                int clamped = (int) Math.round(y);
                if (clamped > Short.MAX_VALUE) clamped = Short.MAX_VALUE;
                if (clamped < Short.MIN_VALUE) clamped = Short.MIN_VALUE;
                sample = (short) clamped;
            }

            pcmData[i] = (byte) (sample & 0xFF);
            pcmData[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
    }

    private void initNoiseGate() {
        gateState = GateState.CLOSED;
        gateEnvelope = 0f;
        holdCounter = 0;
        gateThreshold = com.vdo.frecorder.AppConstants.NOISE_GATE_THRESHOLD_RMS;
        gateHysteresis = (int) (gateThreshold * com.vdo.frecorder.AppConstants.NOISE_GATE_HYSTERESIS);
        float attackMs = com.vdo.frecorder.AppConstants.NOISE_GATE_ATTACK_MS;
        float releaseMs = com.vdo.frecorder.AppConstants.NOISE_GATE_RELEASE_MS;
        float holdMs = com.vdo.frecorder.AppConstants.NOISE_GATE_HOLD_MS;
        // Coefficients are per-chunk approximation: assume ~40ms chunks
        // attackCoeff = 1.0 / (sampleRate * attackMs / 1000)
        attackCoeff = 1000.0f / (sampleRate * attackMs);
        releaseCoeff = 1000.0f / (sampleRate * releaseMs);
        holdSamples = (long) (sampleRate * holdMs / 1000.0f);
    }

    public void setNoiseGateEnabled(boolean enabled) {
        this.noiseGateEnabled = enabled;
        if (!enabled) {
            gateState = GateState.OPEN;
            gateEnvelope = 1.0f;
        }
    }

    public boolean isNoiseGateEnabled() {
        return noiseGateEnabled;
    }

    private void processNoiseGate(byte[] pcmData, int length) {
        // 1. Compute RMS of chunk
        long sumSquares = 0;
        int sampleCount = length / 2;
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            sumSquares += (long) sample * sample;
        }
        float rms = (float) Math.sqrt((double) sumSquares / sampleCount);

        // 2. Update state machine
        switch (gateState) {
            case CLOSED:
                if (rms > gateThreshold) gateState = GateState.ATTACK;
                break;
            case ATTACK:
                gateEnvelope += attackCoeff * sampleCount;
                if (gateEnvelope >= 1.0f) { gateEnvelope = 1.0f; gateState = GateState.OPEN; }
                break;
            case OPEN:
                if (rms < gateHysteresis) { holdCounter = holdSamples; gateState = GateState.HOLD; }
                break;
            case HOLD:
                holdCounter -= sampleCount;
                if (holdCounter <= 0) gateState = GateState.RELEASE;
                if (rms > gateThreshold) gateState = GateState.OPEN;
                break;
            case RELEASE:
                gateEnvelope -= releaseCoeff * sampleCount;
                if (gateEnvelope <= 0f) { gateEnvelope = 0f; gateState = GateState.CLOSED; }
                if (rms > gateThreshold) gateState = GateState.ATTACK;
                break;
        }

        // 3. Apply envelope to samples (only when not fully open)
        if (gateEnvelope < 1.0f) {
            for (int i = 0; i < length; i += 2) {
                short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
                sample = (short) (sample * gateEnvelope);
                pcmData[i] = (byte) (sample & 0xFF);
                pcmData[i + 1] = (byte) ((sample >> 8) & 0xFF);
            }
        }
    }

    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Start audio monitoring playback.
     * Creates an AudioTrack matching the recording parameters and puts it in play mode.
     * After this, feedAudio() calls will write PCM data directly to the AudioTrack.
     */
    public void start() {
        if (isMonitoring.get()) {
            Timber.w("AudioMonitor.start: already running");
            return;
        }

        // Clean up any previous AudioTrack
        releaseAudioTrack();

        int channelConfig = channelCount == 1 ?
            AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;

        int minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

        if (minBufferSize <= 0) {
            lastError = "Invalid minBufferSize: " + minBufferSize;
            Timber.e(lastError);
            return;
        }

        try {
            audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                lastError = "AudioTrack not initialized, state=" + audioTrack.getState();
                Timber.e(lastError);
                audioTrack.release();
                audioTrack = null;
                return;
            }

            // Route output to Bluetooth or built-in speaker, NOT the USB audio device
            AudioDeviceInfo outputDevice = findNonUsbOutputDevice();
            if (outputDevice != null) {
                boolean set = audioTrack.setPreferredDevice(outputDevice);
                Timber.d("Set preferred output device: %s (type=%d), success=%b",
                        outputDevice.getProductName(), outputDevice.getType(), set);
            }

            audioTrack.play();

            if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                lastError = "AudioTrack not playing, playState=" + audioTrack.getPlayState();
                Timber.e(lastError);
                audioTrack.release();
                audioTrack = null;
                return;
            }

            feedCount.set(0);
            writeCount.set(0);
            isMonitoring.set(true);
            isPaused.set(false);

            Timber.d("AudioMonitor STARTED: sr=%d ch=%d minBuf=%d playState=%d",
                    sampleRate, channelCount, minBufferSize, audioTrack.getPlayState());
        } catch (Exception e) {
            lastError = "start() exception: " + e.getMessage();
            Timber.e(e, "Failed to start AudioMonitor");
            releaseAudioTrack();
        }
    }

    /**
     * Stop audio monitoring.
     */
    public void stop() {
        isMonitoring.set(false);
        isPaused.set(false);
        releaseAudioTrack();
        Timber.d("AudioMonitor stopped. feeds=%d writes=%d", feedCount.get(), writeCount.get());
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.flush();
                audioTrack.release();
            } catch (Exception e) {
                Timber.e(e, "Error releasing AudioTrack");
            }
            audioTrack = null;
        }
    }

    /**
     * Pause monitoring.
     */
    public void pause() {
        if (isMonitoring.get() && !isPaused.get()) {
            isPaused.set(true);
            if (audioTrack != null) {
                try { audioTrack.pause(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Resume monitoring after pause.
     */
    public void resume() {
        if (isMonitoring.get() && isPaused.get()) {
            isPaused.set(false);
            if (audioTrack != null) {
                try { audioTrack.play(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Feed audio data to be played back.
     * Called directly from the recording thread — writes synchronously to AudioTrack.
     * This matches the proven LoopbackLive pattern: same thread, same buffer, no queue.
     * AudioTrack.write() will block if the buffer is full, naturally throttling to real-time.
     */
    public void feedAudio(byte[] pcmData) {
        if (!isMonitoring.get() || isPaused.get() || pcmData == null || audioTrack == null) {
            return;
        }

        // Only apply gain/filters in standalone mode; during recording, WavRecorder already processes before feeding
        if (isStandalone.get()) {
            applyGainBoost(pcmData, pcmData.length);
            if (hpfCoeffs != null || lpfCoeffs != null) {
                applyFilters(pcmData, pcmData.length);
            }
        }

        // Only apply noise gate in standalone mode; during recording, WavRecorder already applies it
        if (noiseGateEnabled && isStandalone.get()) {
            processNoiseGate(pcmData, pcmData.length);
        }

        feedCount.incrementAndGet();
        try {
            int written = audioTrack.write(pcmData, 0, pcmData.length, AudioTrack.WRITE_NON_BLOCKING);
            if (written > 0) {
                writeCount.incrementAndGet();
            } else if (written < 0) {
                Timber.e("AudioTrack.write error: %d", written);
            }
        } catch (Exception e) {
            Timber.e(e, "Error writing to AudioTrack in feedAudio");
        }
    }

    /**
     * Play a short test tone (440Hz, 0.5s) to verify AudioTrack output works.
     * Uses the same deprecated constructor as the main AudioTrack for consistency.
     */
    public String playTestTone() {
        try {
            int sr = 44100;
            int channelCfg = AudioFormat.CHANNEL_OUT_MONO;
            int minBuf = AudioTrack.getMinBufferSize(sr, channelCfg, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return "minBuf error: " + minBuf;

            int durationMs = 500;
            int numSamples = sr * durationMs / 1000;
            short[] samples = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                samples[i] = (short) (Short.MAX_VALUE * Math.sin(2 * Math.PI * 440 * i / sr));
            }

            // Use MODE_STREAM and write on a thread, since MODE_STATIC may fail on some devices
            AudioTrack tone = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(channelCfg)
                    .build())
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

            if (tone.getState() != AudioTrack.STATE_INITIALIZED) {
                String err = "Tone init failed, state=" + tone.getState();
                tone.release();
                return err;
            }

            tone.play();

            // Write and release on a background thread
            new Thread(() -> {
                tone.write(samples, 0, samples.length);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                try { tone.stop(); tone.release(); } catch (Exception ignored) {}
            }).start();

            return "Tone playing (state=" + tone.getPlayState() + ", sr=" + sr + ", minBuf=" + minBuf + ")";
        } catch (Exception e) {
            return "Tone error: " + e.getMessage();
        }
    }

    /**
     * Get debug status string for display in UI.
     */
    public String getDebugStatus() {
        return "mon=" + isMonitoring.get()
                + " sr=" + sampleRate + " ch=" + channelCount
                + " track=" + (audioTrack != null ? "play=" + audioTrack.getPlayState() : "null")
                + " f=" + feedCount.get()
                + " w=" + writeCount.get()
                + (lastError != null ? " ERR=" + lastError : "");
    }

    public void setVolume(float volume) {
        float v = Math.max(0f, Math.min(1f, volume));
        if (audioTrack != null) {
            audioTrack.setStereoVolume(v, v);
        }
    }

    public boolean isMonitoring() {
        return isMonitoring.get();
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    /**
     * Start standalone monitoring (no recording).
     * Creates its own AudioRecord and reads audio in a loop, feeding it to AudioTrack.
     * Call stopStandalone() before starting a recording, then resume after.
     */
    @android.annotation.SuppressLint("MissingPermission")
    public void startStandalone(int sampleRate, int channelCount, AudioDeviceInfo inputDevice) {
        if (isMonitoring.get()) {
            Timber.w("AudioMonitor.startStandalone: already monitoring");
            return;
        }

        this.inputDevice = inputDevice;
        initialize(sampleRate, channelCount);
        start();

        if (!isMonitoring.get()) {
            Timber.e("AudioMonitor.startStandalone: AudioTrack failed to start");
            return;
        }

        int channelIn = channelCount == 1 ?
            AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) {
            lastError = "Standalone AudioRecord minBuf error: " + bufferSize;
            Timber.e(lastError);
            stop();
            return;
        }

        try {
            standaloneRecorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelIn,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (inputDevice != null) {
                standaloneRecorder.setPreferredDevice(inputDevice);
            }

            if (standaloneRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                lastError = "Standalone AudioRecord init failed";
                Timber.e(lastError);
                standaloneRecorder.release();
                standaloneRecorder = null;
                stop();
                return;
            }

            standaloneRecorder.startRecording();
            isStandalone.set(true);

            final int readSize = bufferSize;
            standaloneThread = new Thread(() -> {
                byte[] buffer = new byte[readSize];
                while (isStandalone.get() && isMonitoring.get()) {
                    if (!isPaused.get()) {
                        int read = standaloneRecorder.read(buffer, 0, readSize);
                        if (read > 0) {
                            feedAudio(buffer.clone());
                        }
                    } else {
                        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                    }
                }
            }, "AudioMonitor-Standalone");
            standaloneThread.start();

            Timber.d("Standalone monitoring started: sr=%d ch=%d device=%s",
                    sampleRate, channelCount,
                    inputDevice != null ? inputDevice.getProductName() : "default");
        } catch (Exception e) {
            lastError = "Standalone start error: " + e.getMessage();
            Timber.e(e, lastError);
            releaseStandaloneRecorder();
            stop();
        }
    }

    /**
     * Stop standalone monitoring. Call this before starting a recording.
     */
    public void stopStandalone() {
        isStandalone.set(false);
        if (standaloneThread != null) {
            try { standaloneThread.join(500); } catch (InterruptedException ignored) {}
            standaloneThread = null;
        }
        releaseStandaloneRecorder();
        stop();
        Timber.d("Standalone monitoring stopped");
    }

    private void releaseStandaloneRecorder() {
        if (standaloneRecorder != null) {
            try {
                standaloneRecorder.stop();
                standaloneRecorder.release();
            } catch (Exception e) {
                Timber.e(e, "Error releasing standalone AudioRecord");
            }
            standaloneRecorder = null;
        }
    }

    public boolean isStandalone() {
        return isStandalone.get();
    }

    public AudioDeviceInfo getInputDevice() {
        return inputDevice;
    }

    public void release() {
        stopStandalone();
        stop();
    }

    /**
     * Check if monitoring would cause audio feedback.
     * Feedback occurs when the input is the built-in mic (no external device)
     * and the output would go to the built-in speaker (no headphones/BT connected).
     *
     * @param inputDevice The selected input device, or null for default built-in mic.
     * @return true if there is a feedback risk.
     */
    public boolean hasFeedbackRisk(AudioDeviceInfo inputDevice) {
        if (appContext == null) return false;

        // If an external input device is selected, the mic won't pick up speaker output
        if (inputDevice != null) return false;

        // Check if the only available output is the built-in speaker
        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return false;

        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                // An isolated output device is connected — no feedback risk
                return false;
            }
        }

        // Only built-in speaker available with built-in mic — feedback risk
        return true;
    }

    /**
     * Find a non-USB output device, preferring Bluetooth A2DP, then Bluetooth SCO,
     * then wired headset, then built-in speaker.
     */
    private AudioDeviceInfo findNonUsbOutputDevice() {
        if (appContext == null) return null;

        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return null;

        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo bluetooth = null;
        AudioDeviceInfo wired = null;
        AudioDeviceInfo speaker = null;

        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                bluetooth = device;
            } else if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                if (bluetooth == null) bluetooth = device;
            } else if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                wired = device;
            } else if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                speaker = device;
            }
        }

        if (bluetooth != null) return bluetooth;
        if (wired != null) return wired;
        if (speaker != null) return speaker;
        return null;
    }
}
