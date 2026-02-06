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

package com.vdo.frecorder.audio;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import timber.log.Timber;

/**
 * Manages audio input devices including USB audio devices.
 * Provides device enumeration and connection monitoring.
 */
public class AudioDeviceManager {

	public interface AudioDeviceListener {
		void onDevicesChanged(List<AudioDeviceInfo> availableDevices);
	}

	public static final int DEVICE_DEFAULT_MIC = -1;

	private static volatile AudioDeviceManager instance;

	private final AudioManager audioManager;
	private final Handler mainHandler;
	private final List<AudioDeviceListener> listeners;
	private AudioDeviceCallback deviceCallback;

	public static AudioDeviceManager getInstance(Context context) {
		if (instance == null) {
			synchronized (AudioDeviceManager.class) {
				if (instance == null) {
					instance = new AudioDeviceManager(context.getApplicationContext());
				}
			}
		}
		return instance;
	}

	private AudioDeviceManager(Context context) {
		audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
		mainHandler = new Handler(Looper.getMainLooper());
		listeners = new CopyOnWriteArrayList<>();
		registerDeviceCallback();
	}

	private void registerDeviceCallback() {
		deviceCallback = new AudioDeviceCallback() {
			@Override
			public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
				Timber.d("Audio devices added: %d", addedDevices.length);
				notifyDevicesChanged();
			}

			@Override
			public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
				Timber.d("Audio devices removed: %d", removedDevices.length);
				notifyDevicesChanged();
			}
		};
		audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler);
	}

	/**
	 * Get all available audio input devices (excluding default mic).
	 * Returns USB audio devices and other external input devices.
	 */
	public List<AudioDeviceInfo> getAvailableInputDevices() {
		List<AudioDeviceInfo> devices = new ArrayList<>();
		AudioDeviceInfo[] allDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);

		for (AudioDeviceInfo device : allDevices) {
			if (isExternalInputDevice(device)) {
				devices.add(device);
			}
		}
		return devices;
	}

	/**
	 * Check if a device is an external input device (USB, wired headset, etc.)
	 */
	private boolean isExternalInputDevice(AudioDeviceInfo device) {
		int type = device.getType();
		return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
				type == AudioDeviceInfo.TYPE_USB_HEADSET ||
				type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
				type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
	}

	/**
	 * Get an audio device by its ID.
	 * @param deviceId The device ID to look up
	 * @return The AudioDeviceInfo or null if not found
	 */
	public AudioDeviceInfo getDeviceById(int deviceId) {
		if (deviceId == DEVICE_DEFAULT_MIC) {
			return null;
		}

		AudioDeviceInfo[] allDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
		for (AudioDeviceInfo device : allDevices) {
			if (device.getId() == deviceId) {
				return device;
			}
		}
		return null;
	}

	/**
	 * Get a user-friendly display name for an audio device.
	 */
	public static String getDeviceDisplayName(AudioDeviceInfo device) {
		if (device == null) {
			return "Default Microphone";
		}

		String productName = device.getProductName() != null ?
				device.getProductName().toString() : "";

		if (!productName.isEmpty()) {
			return productName;
		}

		// Fallback to type-based names
		switch (device.getType()) {
			case AudioDeviceInfo.TYPE_USB_DEVICE:
				return "USB Audio Device";
			case AudioDeviceInfo.TYPE_USB_HEADSET:
				return "USB Headset";
			case AudioDeviceInfo.TYPE_WIRED_HEADSET:
				return "Wired Headset";
			case AudioDeviceInfo.TYPE_USB_ACCESSORY:
				return "USB Accessory";
			default:
				return "External Audio Device";
		}
	}

	/**
	 * Get the device type as a user-friendly string.
	 */
	public static String getDeviceTypeString(AudioDeviceInfo device) {
		if (device == null) {
			return "Built-in";
		}
		switch (device.getType()) {
			case AudioDeviceInfo.TYPE_USB_DEVICE:
				return "USB";
			case AudioDeviceInfo.TYPE_USB_HEADSET:
				return "USB Headset";
			case AudioDeviceInfo.TYPE_WIRED_HEADSET:
				return "Wired";
			case AudioDeviceInfo.TYPE_USB_ACCESSORY:
				return "USB";
			default:
				return "External";
		}
	}

	public void registerDeviceListener(AudioDeviceListener listener) {
		if (listener != null && !listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public void unregisterDeviceListener(AudioDeviceListener listener) {
		listeners.remove(listener);
	}

	private void notifyDevicesChanged() {
		List<AudioDeviceInfo> devices = getAvailableInputDevices();
		for (AudioDeviceListener listener : listeners) {
			listener.onDevicesChanged(devices);
		}
	}

	public void release() {
		if (deviceCallback != null) {
			audioManager.unregisterAudioDeviceCallback(deviceCallback);
			deviceCallback = null;
		}
		listeners.clear();
	}
}
