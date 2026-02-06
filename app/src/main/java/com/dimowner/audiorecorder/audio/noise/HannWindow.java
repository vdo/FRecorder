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
 * Precomputed Hann window for use in spectral analysis.
 */
public class HannWindow {

    private final double[] window;

    public HannWindow(int size) {
        window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
    }

    /**
     * Apply the Hann window to the given samples in-place.
     */
    public void apply(double[] samples) {
        int len = Math.min(samples.length, window.length);
        for (int i = 0; i < len; i++) {
            samples[i] *= window[i];
        }
    }

    public int size() {
        return window.length;
    }

    public double get(int index) {
        return window[index];
    }
}
