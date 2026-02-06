/*
 * Copyright 2024 Dimowner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.dimowner.audiorecorder.audio.noise;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import timber.log.Timber;

/**
 * Spectral-gating noise reduction inspired by Audacity's NoiseReduction effect.
 *
 * Algorithm:
 * 1. Read the first N seconds of audio as a noise profile
 * 2. Compute per-frequency-bin statistics (mean + std of magnitude)
 * 3. For each frame of the full recording, compute a soft gain mask
 *    based on how much each bin exceeds the noise threshold
 * 4. Apply temporal smoothing (attack/release) and optional frequency smoothing
 * 5. Multiply spectrum by mask, inverse FFT, overlap-add to reconstruct
 * 6. Write the result back to the WAV file
 */
public class NoiseReducer {

    private static final String TAG = "NoiseReducer";

    // FFT frame size — must be power of 2
    private static final int FFT_SIZE = 2048;
    private static final int HOP_SIZE = FFT_SIZE / 2; // 50% overlap

    // Temporal smoothing constants
    private static final double ATTACK_TIME_SEC = 0.02;  // 20ms
    private static final double RELEASE_TIME_SEC = 0.10;  // 100ms

    // WAV header size
    private static final int WAV_HEADER_SIZE = 44;

    /**
     * Listener for progress updates during noise reduction.
     */
    public interface ProgressListener {
        /**
         * Called with progress percentage (0-100).
         */
        void onProgress(int percent);
    }

    /**
     * Apply noise reduction to a WAV file in-place.
     *
     * @param wavFile             The WAV file to process
     * @param noiseProfileSeconds Duration of noise profile from start of recording
     * @param reductionDb         Noise reduction amount in dB (0-24)
     * @param sensitivity         How aggressively to classify bins as noise (0-24)
     * @param freqSmoothingBands  Number of neighboring bands for frequency smoothing (0-6)
     * @param listener            Optional progress listener (may be null)
     * @return true if processing succeeded
     */
    public static boolean process(File wavFile, float noiseProfileSeconds,
                                  float reductionDb, float sensitivity,
                                  int freqSmoothingBands, ProgressListener listener) {
        try {
            Timber.d("%s: Starting noise reduction on %s", TAG, wavFile.getName());
            Timber.d("%s: params: reduction=%.1fdB sensitivity=%.1f freqSmooth=%d profileSec=%.1f",
                    TAG, reductionDb, sensitivity, freqSmoothingBands, noiseProfileSeconds);

            // Read WAV header
            WavInfo wavInfo = readWavHeader(wavFile);
            if (wavInfo == null) {
                Timber.e("%s: Failed to read WAV header", TAG);
                return false;
            }

            Timber.d("%s: WAV: sr=%d ch=%d bps=%d dataSize=%d",
                    TAG, wavInfo.sampleRate, wavInfo.channels, wavInfo.bitsPerSample, wavInfo.dataSize);

            if (wavInfo.bitsPerSample != 16) {
                Timber.e("%s: Only 16-bit PCM is supported, got %d", TAG, wavInfo.bitsPerSample);
                return false;
            }

            // Read all samples (interleaved channels → mono mix for processing)
            double[] samples = readSamples(wavFile, wavInfo);
            if (samples == null || samples.length == 0) {
                Timber.e("%s: Failed to read samples", TAG);
                return false;
            }

            int totalSamples = samples.length;
            Timber.d("%s: Read %d mono samples (%.2f seconds)",
                    TAG, totalSamples, (double) totalSamples / wavInfo.sampleRate);

            // Step 1: Build noise profile from first N seconds
            int profileSamples = Math.min(
                    (int) (noiseProfileSeconds * wavInfo.sampleRate),
                    totalSamples);
            if (profileSamples < FFT_SIZE) {
                Timber.e("%s: Not enough samples for noise profile (%d < %d)",
                        TAG, profileSamples, FFT_SIZE);
                return false;
            }

            double[] noiseMean = new double[FFT_SIZE / 2 + 1];
            double[] noiseStd = new double[FFT_SIZE / 2 + 1];
            buildNoiseProfile(samples, profileSamples, noiseMean, noiseStd);

            // Compute threshold per bin
            // Higher sensitivity = lower threshold = more bins classified as noise
            // sensitivity 0 → threshold = mean + 3*std (conservative)
            // sensitivity 24 → threshold = mean + 0*std (aggressive)
            double sensitivityScale = (24.0 - sensitivity) / 24.0 * 3.0;
            double[] threshold = new double[FFT_SIZE / 2 + 1];
            for (int i = 0; i < threshold.length; i++) {
                threshold[i] = noiseMean[i] + sensitivityScale * noiseStd[i];
            }

            // Reduction strength: how much of the noise to subtract
            // reductionDb 0 → subtract nothing, reductionDb 24 → subtract 2x the threshold
            double reductionStrength = reductionDb / 12.0; // 6dB → 0.5, 12dB → 1.0, 24dB → 2.0

            Timber.d("%s: sensitivityScale=%.2f reductionStrength=%.2f", TAG, sensitivityScale, reductionStrength);

            // Temporal smoothing coefficients
            double hopDuration = (double) HOP_SIZE / wavInfo.sampleRate;
            double attackCoeff = Math.exp(-hopDuration / ATTACK_TIME_SEC);
            double releaseCoeff = Math.exp(-hopDuration / RELEASE_TIME_SEC);

            // Step 2: Process the full signal
            HannWindow window = new HannWindow(FFT_SIZE);
            double[] output = new double[totalSamples];
            double[] windowSum = new double[totalSamples]; // for normalization

            // Previous gain mask for temporal smoothing
            double[] prevGain = new double[FFT_SIZE / 2 + 1];
            for (int i = 0; i < prevGain.length; i++) {
                prevGain[i] = 1.0;
            }

            int numFrames = (totalSamples - FFT_SIZE) / HOP_SIZE + 1;
            int frameCount = 0;

            double[] re = new double[FFT_SIZE];
            double[] im = new double[FFT_SIZE];

            for (int pos = 0; pos + FFT_SIZE <= totalSamples; pos += HOP_SIZE) {
                // Extract and window the frame
                for (int i = 0; i < FFT_SIZE; i++) {
                    re[i] = samples[pos + i] * window.get(i);
                    im[i] = 0.0;
                }

                // Forward FFT
                FFTHelper.forward(re, im);

                // Compute magnitude and phase, build gain mask
                double[] magnitude = new double[FFT_SIZE / 2 + 1];
                double[] phase = new double[FFT_SIZE / 2 + 1];
                double[] gainMask = new double[FFT_SIZE / 2 + 1];

                for (int i = 0; i <= FFT_SIZE / 2; i++) {
                    magnitude[i] = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
                    phase[i] = Math.atan2(im[i], re[i]);

                    // Spectral subtraction: subtract scaled noise floor from magnitude
                    double noiseEstimate = threshold[i] * reductionStrength;
                    double reduced = magnitude[i] - noiseEstimate;
                    if (reduced < 0) reduced = 0;
                    // Gain is the ratio of reduced to original magnitude
                    if (magnitude[i] > 1e-10) {
                        gainMask[i] = reduced / magnitude[i];
                    } else {
                        gainMask[i] = 0.0;
                    }
                }

                // Frequency smoothing
                if (freqSmoothingBands > 0) {
                    gainMask = smoothFrequency(gainMask, freqSmoothingBands);
                }

                // Temporal smoothing
                for (int i = 0; i <= FFT_SIZE / 2; i++) {
                    if (gainMask[i] < prevGain[i]) {
                        // Attack (gain decreasing = noise gate closing)
                        gainMask[i] = attackCoeff * prevGain[i] + (1.0 - attackCoeff) * gainMask[i];
                    } else {
                        // Release (gain increasing = noise gate opening)
                        gainMask[i] = releaseCoeff * prevGain[i] + (1.0 - releaseCoeff) * gainMask[i];
                    }
                    prevGain[i] = gainMask[i];
                }

                // Apply gain mask to spectrum
                for (int i = 0; i <= FFT_SIZE / 2; i++) {
                    re[i] = magnitude[i] * gainMask[i] * Math.cos(phase[i]);
                    im[i] = magnitude[i] * gainMask[i] * Math.sin(phase[i]);
                }
                // Mirror for negative frequencies
                for (int i = 1; i < FFT_SIZE / 2; i++) {
                    re[FFT_SIZE - i] = re[i];
                    im[FFT_SIZE - i] = -im[i];
                }

                // Inverse FFT
                FFTHelper.inverse(re, im);

                // Overlap-add with window
                for (int i = 0; i < FFT_SIZE; i++) {
                    int idx = pos + i;
                    if (idx < totalSamples) {
                        output[idx] += re[i] * window.get(i);
                        windowSum[idx] += window.get(i) * window.get(i);
                    }
                }

                frameCount++;
                if (listener != null && frameCount % 50 == 0) {
                    listener.onProgress((int) (100.0 * frameCount / numFrames));
                }
            }

            // Normalize by window sum (avoid division by zero)
            for (int i = 0; i < totalSamples; i++) {
                if (windowSum[i] > 1e-8) {
                    output[i] /= windowSum[i];
                }
            }

            if (listener != null) {
                listener.onProgress(95);
            }

            // Step 3: Write processed samples back to WAV file
            writeSamples(wavFile, wavInfo, output);

            if (listener != null) {
                listener.onProgress(100);
            }

            Timber.d("%s: Noise reduction complete, processed %d frames", TAG, frameCount);
            return true;

        } catch (Exception e) {
            Timber.e(e, "%s: Noise reduction failed", TAG);
            return false;
        }
    }

    /**
     * Build noise profile from the first profileSamples of the signal.
     */
    private static void buildNoiseProfile(double[] samples, int profileSamples,
                                           double[] mean, double[] std) {
        HannWindow window = new HannWindow(FFT_SIZE);
        int numBins = FFT_SIZE / 2 + 1;
        int frameCount = 0;

        double[] sumMag = new double[numBins];
        double[] sumMagSq = new double[numBins];

        double[] re = new double[FFT_SIZE];
        double[] im = new double[FFT_SIZE];

        for (int pos = 0; pos + FFT_SIZE <= profileSamples; pos += HOP_SIZE) {
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = samples[pos + i] * window.get(i);
                im[i] = 0.0;
            }

            FFTHelper.forward(re, im);

            for (int i = 0; i < numBins; i++) {
                double mag = Math.sqrt(re[i] * re[i] + im[i] * im[i]);
                sumMag[i] += mag;
                sumMagSq[i] += mag * mag;
            }
            frameCount++;
        }

        if (frameCount > 0) {
            for (int i = 0; i < numBins; i++) {
                mean[i] = sumMag[i] / frameCount;
                double variance = (sumMagSq[i] / frameCount) - (mean[i] * mean[i]);
                std[i] = Math.sqrt(Math.max(0, variance));
            }
        }

        Timber.d("%s: Built noise profile from %d frames", TAG, frameCount);
    }

    /**
     * Smooth the gain mask across frequency by averaging with neighboring bands.
     */
    private static double[] smoothFrequency(double[] mask, int bands) {
        double[] smoothed = new double[mask.length];
        for (int i = 0; i < mask.length; i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - bands); j <= Math.min(mask.length - 1, i + bands); j++) {
                sum += mask[j];
                count++;
            }
            smoothed[i] = sum / count;
        }
        return smoothed;
    }

    /**
     * Read WAV file header and return info.
     */
    private static WavInfo readWavHeader(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            if (raf.length() < WAV_HEADER_SIZE) return null;

            byte[] header = new byte[WAV_HEADER_SIZE];
            raf.readFully(header);

            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

            // Verify RIFF header
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                return null;
            }
            // Verify WAVE format
            if (header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') {
                return null;
            }

            WavInfo info = new WavInfo();
            info.channels = bb.getShort(22) & 0xFFFF;
            info.sampleRate = bb.getInt(24);
            info.bitsPerSample = bb.getShort(34) & 0xFFFF;
            info.dataSize = bb.getInt(40);
            return info;
        } finally {
            raf.close();
        }
    }

    /**
     * Read all PCM samples from WAV file, mix to mono as doubles in [-1, 1].
     */
    private static double[] readSamples(File file, WavInfo info) throws IOException {
        int bytesPerSample = info.bitsPerSample / 8;
        int totalFrames = info.dataSize / (bytesPerSample * info.channels);
        double[] mono = new double[totalFrames];

        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(WAV_HEADER_SIZE);
            byte[] buffer = new byte[Math.min(info.dataSize, 65536)];
            int sampleIdx = 0;
            int remaining = info.dataSize;

            while (remaining > 0 && sampleIdx < totalFrames) {
                int toRead = Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, toRead);
                if (read <= 0) break;
                remaining -= read;

                ByteBuffer bb = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN);
                int frameBytes = bytesPerSample * info.channels;

                while (bb.remaining() >= frameBytes && sampleIdx < totalFrames) {
                    double sum = 0;
                    for (int ch = 0; ch < info.channels; ch++) {
                        short s = bb.getShort();
                        sum += s / 32768.0;
                    }
                    mono[sampleIdx++] = sum / info.channels;
                }
            }
            return mono;
        } finally {
            raf.close();
        }
    }

    /**
     * Write processed mono samples back to WAV file.
     * For multi-channel files, the same processed mono signal is written to all channels.
     */
    private static void writeSamples(File file, WavInfo info, double[] mono) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.seek(WAV_HEADER_SIZE);

            int bytesPerFrame = (info.bitsPerSample / 8) * info.channels;
            byte[] buffer = new byte[Math.min(65536, mono.length * bytesPerFrame)];
            ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

            int sampleIdx = 0;
            while (sampleIdx < mono.length) {
                bb.clear();
                while (bb.remaining() >= bytesPerFrame && sampleIdx < mono.length) {
                    // Clamp to [-1, 1] and convert to 16-bit
                    double val = Math.max(-1.0, Math.min(1.0, mono[sampleIdx]));
                    short s = (short) (val * 32767.0);
                    for (int ch = 0; ch < info.channels; ch++) {
                        bb.putShort(s);
                    }
                    sampleIdx++;
                }
                raf.write(buffer, 0, bb.position());
            }
        } finally {
            raf.close();
        }
    }

    private static class WavInfo {
        int channels;
        int sampleRate;
        int bitsPerSample;
        int dataSize;
    }
}
