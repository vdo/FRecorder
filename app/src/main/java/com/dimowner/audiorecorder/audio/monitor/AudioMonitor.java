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

package com.dimowner.audiorecorder.audio.monitor;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

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
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicInteger feedCount = new AtomicInteger(0);
    private final AtomicInteger writeCount = new AtomicInteger(0);

    private int sampleRate = 44100;
    private int channelCount = 1;
    private String lastError = null;
    private Context appContext = null;

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
        Timber.d("AudioMonitor.initialize: sampleRate=%d, channels=%d", sampleRate, channelCount);
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

    public void release() {
        stop();
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
