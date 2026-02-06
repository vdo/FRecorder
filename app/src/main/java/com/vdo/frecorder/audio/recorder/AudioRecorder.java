/*
 * Copyright 2018 Dmytro Ponomarenko
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

package com.vdo.frecorder.audio.recorder;

import android.media.AudioDeviceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;

import com.vdo.frecorder.AppConstants;
import com.vdo.frecorder.exception.InvalidOutputFile;
import com.vdo.frecorder.exception.RecorderInitException;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

import static com.vdo.frecorder.AppConstants.RECORDING_VISUALIZATION_INTERVAL;

public class AudioRecorder implements RecorderContract.Recorder {

	private MediaRecorder recorder = null;
	private File recordFile = null;
	private long updateTime = 0;
	private long durationMills = 0;

	private final AtomicBoolean isRecording = new AtomicBoolean(false);
	private final AtomicBoolean isPaused = new AtomicBoolean(false);
	private final Handler handler = new Handler();

	private RecorderContract.RecorderCallback recorderCallback;

	private static class RecorderSingletonHolder {
		private static final AudioRecorder singleton = new AudioRecorder();

		public static AudioRecorder getSingleton() {
			return RecorderSingletonHolder.singleton;
		}
	}

	public static AudioRecorder getInstance() {
		return RecorderSingletonHolder.getSingleton();
	}

	private AudioRecorder() { }

	@Override
	public void setRecorderCallback(RecorderContract.RecorderCallback callback) {
		this.recorderCallback = callback;
	}

	@Override
	public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate) {
		startRecording(outputFile, channelCount, sampleRate, bitrate, null, AppConstants.GAIN_BOOST_OFF);
	}

	@Override
	public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate, AudioDeviceInfo audioDevice) {
		startRecording(outputFile, channelCount, sampleRate, bitrate, audioDevice, AppConstants.GAIN_BOOST_OFF);
	}

	@Override
	public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate, AudioDeviceInfo audioDevice, int gainBoostLevel) {
		// Note: Gain boost is not supported for M4A format (MediaRecorder doesn't expose raw audio samples)
		recordFile = new File(outputFile);
		if (recordFile.exists() && recordFile.isFile()) {
			recorder = new MediaRecorder();
			recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
			// Set preferred audio device if specified (for USB audio devices)
			if (audioDevice != null) {
				boolean deviceSet = recorder.setPreferredDevice(audioDevice);
				Timber.d("Set preferred audio device: %s, success: %b",
						audioDevice.getProductName(), deviceSet);
			}
			recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
			recorder.setAudioChannels(channelCount);
			recorder.setAudioSamplingRate(sampleRate);
			recorder.setAudioEncodingBitRate(bitrate);
			recorder.setMaxDuration(-1); //Duration unlimited or use RECORD_MAX_DURATION
			recorder.setOutputFile(recordFile.getAbsolutePath());
			try {
				recorder.prepare();
				recorder.start();
				updateTime = System.currentTimeMillis();
				isRecording.set(true);
				scheduleRecordingTimeUpdate();
				if (recorderCallback != null) {
					recorderCallback.onStartRecord(recordFile);
				}
				isPaused.set(false);
			} catch (IOException | IllegalStateException e) {
				Timber.e(e, "prepare() failed");
				if (recorderCallback != null) {
					recorderCallback.onError(new RecorderInitException());
				}
			}
		} else {
			if (recorderCallback != null) {
				recorderCallback.onError(new InvalidOutputFile());
			}
		}
	}

	@Override
	public void resumeRecording() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isPaused.get()) {
			try {
				recorder.resume();
				updateTime = System.currentTimeMillis();
				scheduleRecordingTimeUpdate();
				if (recorderCallback != null) {
					recorderCallback.onResumeRecord();
				}
				isPaused.set(false);
			} catch (IllegalStateException e) {
				Timber.e(e, "unpauseRecording() failed");
				if (recorderCallback != null) {
					recorderCallback.onError(new RecorderInitException());
				}
			}
		}
	}

	@Override
	public void pauseRecording() {
		if (isRecording.get()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				if (!isPaused.get()) {
					try {
						recorder.pause();
						durationMills += System.currentTimeMillis() - updateTime;
						pauseRecordingTimer();
						if (recorderCallback != null) {
							recorderCallback.onPauseRecord();
						}
						isPaused.set(true);
					} catch (IllegalStateException e) {
						Timber.e(e, "pauseRecording() failed");
						if (recorderCallback != null) {
							//TODO: Fix exception
							recorderCallback.onError(new RecorderInitException());
						}
					}
				}
			} else {
				stopRecording();
			}
		}
	}

	@Override
	public void stopRecording() {
		if (isRecording.get()) {
			stopRecordingTimer();
			try {
				recorder.stop();
			} catch (RuntimeException e) {
				Timber.e(e, "stopRecording() problems");
			}
			recorder.release();
			if (recorderCallback != null) {
				recorderCallback.onStopRecord(recordFile);
			}
			durationMills = 0;
			recordFile = null;
			isRecording.set(false);
			isPaused.set(false);
			recorder = null;
		} else {
			Timber.e("Recording has already stopped or hasn't started");
		}
	}

	private void scheduleRecordingTimeUpdate() {
		handler.postDelayed(() -> {
			if (recorderCallback != null && recorder != null) {
				try {
					long curTime = System.currentTimeMillis();
					if (updateTime > 0) {
						durationMills += curTime - updateTime;
					}
					updateTime = curTime;
					recorderCallback.onRecordProgress(durationMills, recorder.getMaxAmplitude());
				} catch (IllegalStateException e) {
					Timber.e(e);
				}
				scheduleRecordingTimeUpdate();
			}
		}, RECORDING_VISUALIZATION_INTERVAL);
	}

	private void stopRecordingTimer() {
		handler.removeCallbacksAndMessages(null);
		updateTime = 0;
	}

	private void pauseRecordingTimer() {
		handler.removeCallbacksAndMessages(null);
		updateTime = 0;
	}

	@Override
	public boolean isRecording() {
		return isRecording.get();
	}

	@Override
	public boolean isPaused() {
		return isPaused.get();
	}
}
