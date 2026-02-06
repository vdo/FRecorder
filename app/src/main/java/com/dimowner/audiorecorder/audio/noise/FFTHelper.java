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

/**
 * Pure Java radix-2 Cooley-Tukey FFT implementation.
 * Operates on arrays of length that must be a power of 2.
 */
public class FFTHelper {

    /**
     * Compute in-place FFT. Arrays re[] and im[] are modified in place.
     * @param re real parts (length must be power of 2)
     * @param im imaginary parts (same length as re)
     * @param forward true for forward FFT, false for inverse FFT
     */
    public static void fft(double[] re, double[] im, boolean forward) {
        int n = re.length;
        if (n == 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Length must be a power of 2, got " + n);
        }

        // Bit-reversal permutation
        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - bits);
            if (i < j) {
                double tempRe = re[i];
                double tempIm = im[i];
                re[i] = re[j];
                im[i] = im[j];
                re[j] = tempRe;
                im[j] = tempIm;
            }
        }

        // Cooley-Tukey iterative FFT
        for (int size = 2; size <= n; size *= 2) {
            int halfSize = size / 2;
            double angle = 2.0 * Math.PI / size * (forward ? -1 : 1);
            double wRe = Math.cos(angle);
            double wIm = Math.sin(angle);

            for (int i = 0; i < n; i += size) {
                double curRe = 1.0;
                double curIm = 0.0;
                for (int j = 0; j < halfSize; j++) {
                    int evenIdx = i + j;
                    int oddIdx = i + j + halfSize;

                    double tRe = curRe * re[oddIdx] - curIm * im[oddIdx];
                    double tIm = curRe * im[oddIdx] + curIm * re[oddIdx];

                    re[oddIdx] = re[evenIdx] - tRe;
                    im[oddIdx] = im[evenIdx] - tIm;
                    re[evenIdx] = re[evenIdx] + tRe;
                    im[evenIdx] = im[evenIdx] + tIm;

                    double newCurRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newCurRe;
                }
            }
        }

        // Scale for inverse FFT
        if (!forward) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    /**
     * Compute forward FFT.
     */
    public static void forward(double[] re, double[] im) {
        fft(re, im, true);
    }

    /**
     * Compute inverse FFT.
     */
    public static void inverse(double[] re, double[] im) {
        fft(re, im, false);
    }
}
