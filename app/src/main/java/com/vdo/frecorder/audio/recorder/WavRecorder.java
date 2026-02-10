/*
 * Copyright 2019 Dmytro Ponomarenko
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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import com.vdo.frecorder.AppConstants;
import com.vdo.frecorder.audio.monitor.AudioMonitor;
import com.vdo.frecorder.exception.InvalidOutputFile;
import com.vdo.frecorder.exception.RecorderInitException;
import com.vdo.frecorder.exception.RecordingException;
import com.vdo.frecorder.util.AndroidUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import timber.log.Timber;
import static com.vdo.frecorder.AppConstants.RECORDING_VISUALIZATION_INTERVAL;
import androidx.annotation.RequiresPermission;
import com.vdo.frecorder.audio.noise.NoiseReducer;
import com.vdo.frecorder.util.AndroidUtils;

public class WavRecorder implements RecorderContract.Recorder {

	private AudioRecord recorder = null;

	private static final int RECORDER_BPP = 16; //bits per sample

	private File recordFile = null;
	private int bufferSize = 0;
	private long updateTime = 0;
	private long durationMills = 0;

	private Thread recordingThread;

	private final AtomicBoolean isRecording = new AtomicBoolean(false);
	private final AtomicBoolean isPaused = new AtomicBoolean(false);
	private final Handler handler = new Handler();

	private int channelCount = 1;

	/** Value for recording used visualisation. */
	private int lastVal = 0;

	private int sampleRate = AppConstants.RECORD_SAMPLE_RATE_44100;
	private int gainBoostLevel = AppConstants.GAIN_BOOST_OFF;
	private volatile boolean monitoringEnabled = false;
	private volatile boolean noiseReductionEnabled = false;
	private volatile float noiseReductionDb = AppConstants.DEFAULT_NOISE_REDUCTION_DB;
	private volatile float noiseReductionSensitivity = AppConstants.DEFAULT_NOISE_REDUCTION_SENSITIVITY;
	private volatile int noiseReductionFreqSmoothing = AppConstants.DEFAULT_NOISE_REDUCTION_FREQ_SMOOTHING;
	private volatile float noiseProfileSeconds = AppConstants.DEFAULT_NOISE_PROFILE_SECONDS;
	private volatile int hpfMode = AppConstants.HPF_OFF;
	private volatile int lpfMode = AppConstants.LPF_OFF;

	// Noise gate state for recording path
	private volatile boolean noiseGateEnabled = false;
	private enum GateState { CLOSED, ATTACK, OPEN, HOLD, RELEASE }
	private GateState gateState = GateState.CLOSED;
	private float gateEnvelope = 0f;
	private long holdCounter = 0;
	private float attackCoeff;
	private float releaseCoeff;
	private long holdSamples;
	private int gateThreshold;
	private int gateHysteresis;

	// Biquad filter state (per channel not needed since we process interleaved mono/stereo as single stream of samples)
	private double hpfX1 = 0, hpfX2 = 0, hpfY1 = 0, hpfY2 = 0;
	private double lpfX1 = 0, lpfX2 = 0, lpfY1 = 0, lpfY2 = 0;
	private double[] hpfCoeffs = null; // {b0, b1, b2, a1, a2}
	private double[] lpfCoeffs = null;

	private final AudioMonitor audioMonitor = AudioMonitor.getInstance();
	private NoiseReductionListener noiseReductionListener;

	private RecorderContract.RecorderCallback recorderCallback;

	private static class WavRecorderSingletonHolder {
		private static final WavRecorder singleton = new WavRecorder();

		public static WavRecorder getSingleton() {
			return WavRecorderSingletonHolder.singleton;
		}
	}

	public static WavRecorder getInstance() {
		return WavRecorderSingletonHolder.getSingleton();
	}

	private WavRecorder() { }

	@Override
	public void setRecorderCallback(RecorderContract.RecorderCallback callback) {
		recorderCallback = callback;
	}

	@Override
	@RequiresPermission(value = "android.permission.RECORD_AUDIO")
	public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate) {
		startRecording(outputFile, channelCount, sampleRate, bitrate, null, AppConstants.GAIN_BOOST_OFF);
	}

	@Override
	@RequiresPermission(value = "android.permission.RECORD_AUDIO")
	public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate, AudioDeviceInfo audioDevice) {
		startRecording(outputFile, channelCount, sampleRate, bitrate, audioDevice, AppConstants.GAIN_BOOST_OFF);
	}

	@Override
	@RequiresPermission(value = "android.permission.RECORD_AUDIO")
	public void startRecording(String outputFile, int channelCount, int sampleRate, int bitrate, AudioDeviceInfo audioDevice, int gainBoostLevel) {
		this.sampleRate = sampleRate;
		this.gainBoostLevel = gainBoostLevel;
//		this.framesPerVisInterval = (int)((VISUALIZATION_INTERVAL/1000f)/(1f/sampleRate));
		this.channelCount = channelCount;
		recordFile = new File(outputFile);
		if (recordFile.exists() && recordFile.isFile()) {
			int channel = channelCount == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
			try {
				bufferSize = AudioRecord.getMinBufferSize(sampleRate,
						channel,
						AudioFormat.ENCODING_PCM_16BIT);
				if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
					bufferSize = AudioRecord.getMinBufferSize(sampleRate,
							channel,
							AudioFormat.ENCODING_PCM_16BIT);
				}
				recorder = new AudioRecord(
						MediaRecorder.AudioSource.MIC,
						sampleRate,
						channel,
						AudioFormat.ENCODING_PCM_16BIT,
						bufferSize
				);
				// Set preferred audio device if specified (for USB audio devices)
				if (audioDevice != null) {
					boolean deviceSet = recorder.setPreferredDevice(audioDevice);
					Timber.d("Set preferred audio device: %s, success: %b",
							audioDevice.getProductName(), deviceSet);
				}
			} catch (IllegalArgumentException e) {
				Timber.e(e, "sampleRate = " + sampleRate + " channel = " + channel + " bufferSize = " + bufferSize);
				if (recorder != null) {
					recorder.release();
				}
			}
			if (recorder != null && recorder.getState() == AudioRecord.STATE_INITIALIZED) {
				// Stop standalone monitoring to release AudioRecord before recording
				if (audioMonitor.isStandalone()) {
					audioMonitor.stopStandalone();
				}

				initFilters();
				recorder.startRecording();
				updateTime = System.currentTimeMillis();
				isRecording.set(true);
				recordingThread = new Thread(this::writeAudioDataToFile, "AudioRecorder Thread");

				// Start audio monitoring if enabled (fed by recording loop)
				if (monitoringEnabled) {
					audioMonitor.initialize(sampleRate, channelCount);
					audioMonitor.start();
				}

				recordingThread.start();
				scheduleRecordingTimeUpdate();
				if (recorderCallback != null) {
					recorderCallback.onStartRecord(recordFile);
				}
				isPaused.set(false);
			} else {
				Timber.e("prepare() failed");
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
		if (recorder != null && recorder.getState() == AudioRecord.STATE_INITIALIZED) {
			if (isPaused.get()) {
				// Stop standalone monitoring before recording resumes (releases AudioRecord)
				if (audioMonitor.isStandalone()) {
					audioMonitor.stopStandalone();
				}

				updateTime = System.currentTimeMillis();
				scheduleRecordingTimeUpdate();
				recorder.startRecording();

				// Restart recording-fed monitoring
				if (monitoringEnabled) {
					if (!audioMonitor.isMonitoring()) {
						audioMonitor.initialize(sampleRate, channelCount);
						audioMonitor.start();
					}
				}

				if (recorderCallback != null) {
					recorderCallback.onResumeRecord();
				}
				isPaused.set(false);
			}
		}
	}

	@Override
	public void pauseRecording() {
		if (isRecording.get()) {
			recorder.stop();
			durationMills += System.currentTimeMillis() - updateTime;
			pauseRecordingTimer();

			// Switch monitoring to standalone so user keeps hearing audio while paused
			if (monitoringEnabled && audioMonitor.isMonitoring()) {
				audioMonitor.stop();
				audioMonitor.startStandalone(sampleRate, channelCount, null);
			}

			isPaused.set(true);
			if (recorderCallback != null) {
				recorderCallback.onPauseRecord();
			}
		}
	}

	@Override
	public void stopRecording() {
		if (recorder != null) {
			isRecording.set(false);
			isPaused.set(false);
			stopRecordingTimer();

			// Stop recording-fed monitoring; will restart as standalone after recording thread finishes
			if (audioMonitor.isMonitoring() && !audioMonitor.isStandalone()) {
				audioMonitor.stop();
			}

			if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
				try {
					recorder.stop();
				} catch (IllegalStateException e) {
					Timber.e(e, "stopRecording() problems");
				}
			}
			durationMills = 0;
			recordingThread.interrupt();

			// Wait for recording thread to finish writing WAV header
			try {
				recordingThread.join(5000);
			} catch (InterruptedException ignored) {}

			recorder.release();

			// Restart monitoring as standalone now that AudioRecord is released
			if (monitoringEnabled && !audioMonitor.isMonitoring()) {
				audioMonitor.startStandalone(sampleRate, channelCount, null);
			}

			// Apply noise reduction if enabled (synchronous, file is now complete)
			if (noiseReductionEnabled && recordFile != null && recordFile.exists()) {
				Timber.d("Starting noise reduction on %s", recordFile.getName());
				if (noiseReductionListener != null) {
					AndroidUtils.runOnUIThread(() -> noiseReductionListener.onNoiseReductionStart());
				}
				final File fileToProcess = recordFile;
				new Thread(() -> {
					boolean success = NoiseReducer.process(
							fileToProcess,
							noiseProfileSeconds,
							noiseReductionDb,
							noiseReductionSensitivity,
							noiseReductionFreqSmoothing,
							progress -> {
								if (noiseReductionListener != null) {
									AndroidUtils.runOnUIThread(() -> noiseReductionListener.onNoiseReductionProgress(progress));
								}
							});
					Timber.d("Noise reduction %s for %s",
							success ? "completed" : "failed", fileToProcess.getName());
					if (noiseReductionListener != null) {
						AndroidUtils.runOnUIThread(() -> noiseReductionListener.onNoiseReductionEnd(success));
					}
					if (recorderCallback != null) {
						AndroidUtils.runOnUIThread(() -> recorderCallback.onStopRecord(fileToProcess));
					}
				}, "NoiseReduction").start();
			} else {
				if (recorderCallback != null) {
					recorderCallback.onStopRecord(recordFile);
				}
			}
		}
	}

	@Override
	public boolean isRecording() {
		return isRecording.get();
	}

	@Override
	public boolean isPaused() {
		return isPaused.get();
	}

	private void writeAudioDataToFile() {
		byte[] data = new byte[bufferSize];
		FileOutputStream fos;
		try {
			fos = new FileOutputStream(recordFile);
		} catch (FileNotFoundException e) {
			Timber.e(e);
			fos = null;
		}
		if (null != fos) {
			writeEmptyHeader(fos);
			int chunksCount = 0;
			ByteBuffer shortBuffer = ByteBuffer.allocate(2);
			shortBuffer.order(ByteOrder.LITTLE_ENDIAN);
			//TODO: Disable loop while pause.
			while (isRecording.get()) {
				if (!isPaused.get()) {
					int bytesRead = recorder.read(data, 0, bufferSize);
					if (bytesRead <= 0) continue;
					// Ensure even number of bytes (16-bit samples)
					bytesRead = bytesRead & ~1;
					chunksCount += bytesRead;
					if (AudioRecord.ERROR_INVALID_OPERATION != chunksCount) {
						long sum = 0;
						for (int i = 0; i < bytesRead; i+=2) {
							//TODO: find a better way to covert bytes into shorts.
							shortBuffer.put(data[i]);
							shortBuffer.put(data[i+1]);
							short sample = shortBuffer.getShort(0);

							// Apply gain boost if enabled
							float multiplier = getGainMultiplier();
							if (multiplier > 1.0f) {
								int amplified = (int) (sample * multiplier);
								if (amplified > Short.MAX_VALUE) amplified = Short.MAX_VALUE;
								if (amplified < Short.MIN_VALUE) amplified = Short.MIN_VALUE;
								sample = (short) amplified;
							}

							// Apply high-pass filter
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

							// Apply low-pass filter
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

							// Write back the processed sample
							data[i] = (byte) (sample & 0xff);
							data[i+1] = (byte) ((sample >> 8) & 0xff);

							sum += Math.abs(sample);
							shortBuffer.clear();
						}
						lastVal = (int)(sum / Math.max(1, bytesRead / 16));

						// Apply noise gate to recording data
						if (noiseGateEnabled) {
							processNoiseGate(data, bytesRead);
						}

						// Feed audio to monitor if enabled (after gain boost is applied)
						if (monitoringEnabled && audioMonitor.isMonitoring()) {
							byte[] monitorData = new byte[bytesRead];
							System.arraycopy(data, 0, monitorData, 0, bytesRead);
							audioMonitor.feedAudio(monitorData);
						}

						try {
							fos.write(data, 0, bytesRead);
						} catch (IOException e) {
							Timber.e(e);
							AndroidUtils.runOnUIThread(() -> {
								recorderCallback.onError(new RecordingException());
								stopRecording();
							});
						}
					}
				} else {
							// Monitoring during pause is handled by standalone mode
					// (started in pauseRecording), nothing to do here
				}
			}

			try {
				fos.flush();
				fos.close();
			} catch (IOException e) {
				Timber.e(e);
			}
			// Trim last 200ms to remove stop transient/glitch
			trimTail(recordFile, channelCount);
			setWaveFileHeader(recordFile, channelCount);
		}
	}

	private void trimTail(File file, int channels) {
		// Remove the last 200ms of audio to eliminate the stop transient
		int bytesPerSample = channels * (RECORDER_BPP / 8);
		int tailBytes = (int) (sampleRate * 0.2) * bytesPerSample;
		long dataLen = file.length() - 44; // subtract WAV header
		if (dataLen > tailBytes) {
			try {
				RandomAccessFile raf = randomAccessFile(file);
				raf.setLength(file.length() - tailBytes);
				raf.close();
			} catch (IOException e) {
				Timber.e(e, "trimTail failed");
			}
		}
	}

	private void setWaveFileHeader(File file, int channels) {
		long fileSize = file.length() - 44;
		long totalSize = fileSize + 36;
		long byteRate = sampleRate * channels * (RECORDER_BPP/8); //2 byte per 1 sample for 1 channel.

		try {
			final RandomAccessFile wavFile = randomAccessFile(file);
			wavFile.seek(0); // to the beginning
			wavFile.write(generateHeader(fileSize, totalSize, sampleRate, channels, byteRate));
			wavFile.close();
		} catch (FileNotFoundException e) {
			Timber.e(e);
		} catch (IOException e) {
			Timber.e(e);
		}
	}

	private void writeEmptyHeader(FileOutputStream fos) {
		try {
			byte[] header = new byte[44];
			fos.write(header);
			fos.flush();
		} catch (IOException e) {
			Timber.e(e);
		}
	}

	private RandomAccessFile randomAccessFile(File file) {
		RandomAccessFile randomAccessFile;
		try {
			randomAccessFile = new RandomAccessFile(file, "rw");
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		}
		return randomAccessFile;
	}

	private byte[] generateHeader(
			long totalAudioLen, long totalDataLen, long longSampleRate, int channels,
			long byteRate) {

		byte[] header = new byte[44];

		header[0] = 'R'; // RIFF/WAVE header
		header[1] = 'I';
		header[2] = 'F';
		header[3] = 'F';
		header[4] = (byte) (totalDataLen & 0xff);
		header[5] = (byte) ((totalDataLen >> 8) & 0xff);
		header[6] = (byte) ((totalDataLen >> 16) & 0xff);
		header[7] = (byte) ((totalDataLen >> 24) & 0xff);
		header[8] = 'W';
		header[9] = 'A';
		header[10] = 'V';
		header[11] = 'E';
		header[12] = 'f'; // 'fmt ' chunk
		header[13] = 'm';
		header[14] = 't';
		header[15] = ' ';
		header[16] = 16; //16 for PCM. 4 bytes: size of 'fmt ' chunk
		header[17] = 0;
		header[18] = 0;
		header[19] = 0;
		header[20] = 1; // format = 1
		header[21] = 0;
		header[22] = (byte) channels;
		header[23] = 0;
		header[24] = (byte) (longSampleRate & 0xff);
		header[25] = (byte) ((longSampleRate >> 8) & 0xff);
		header[26] = (byte) ((longSampleRate >> 16) & 0xff);
		header[27] = (byte) ((longSampleRate >> 24) & 0xff);
		header[28] = (byte) (byteRate & 0xff);
		header[29] = (byte) ((byteRate >> 8) & 0xff);
		header[30] = (byte) ((byteRate >> 16) & 0xff);
		header[31] = (byte) ((byteRate >> 24) & 0xff);
		header[32] = (byte) (channels * (RECORDER_BPP/8)); // block align
		header[33] = 0;
		header[34] = RECORDER_BPP; // bits per sample
		header[35] = 0;
		header[36] = 'd';
		header[37] = 'a';
		header[38] = 't';
		header[39] = 'a';
		header[40] = (byte) (totalAudioLen & 0xff);
		header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
		header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
		header[43] = (byte) ((totalAudioLen >> 24) & 0xff);
		return header;
	}

	private void scheduleRecordingTimeUpdate() {
		handler.postDelayed(() -> {
			if (recorderCallback != null && recorder != null) {
				long curTime = System.currentTimeMillis();
				if (updateTime > 0) {
					durationMills += curTime - updateTime;
				}
				updateTime = curTime;
				recorderCallback.onRecordProgress(durationMills, lastVal);
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

	public void setGainBoostLevel(int level) {
		this.gainBoostLevel = level;
	}

	private float getGainMultiplier() {
		switch (gainBoostLevel) {
			case AppConstants.GAIN_BOOST_6DB:
				return AppConstants.GAIN_BOOST_MULTIPLIER_6DB;
			case AppConstants.GAIN_BOOST_12DB:
				return AppConstants.GAIN_BOOST_MULTIPLIER_12DB;
			default:
				return 1.0f;
		}
	}

	/**
	 * Enable or disable audio monitoring.
	 * When enabled, recorded audio is played back through headphones in real-time.
	 * Should be called before startRecording().
	 */
	public void setMonitoringEnabled(boolean enabled) {
		this.monitoringEnabled = enabled;
		if (enabled && isRecording.get() && !isPaused.get() && !audioMonitor.isMonitoring()) {
			audioMonitor.initialize(sampleRate, channelCount);
			audioMonitor.start();
		} else if (!enabled && audioMonitor.isMonitoring()) {
			audioMonitor.stop();
		}
	}

	public boolean isMonitoringEnabled() {
		return monitoringEnabled;
	}

	public void setNoiseReductionEnabled(boolean enabled) {
		this.noiseReductionEnabled = enabled;
	}

	public boolean isNoiseReductionEnabled() {
		return noiseReductionEnabled;
	}

	public void setNoiseReductionDb(float db) {
		this.noiseReductionDb = db;
	}

	public void setNoiseReductionSensitivity(float sensitivity) {
		this.noiseReductionSensitivity = sensitivity;
	}

	public void setNoiseReductionFreqSmoothing(int bands) {
		this.noiseReductionFreqSmoothing = bands;
	}

	public void setNoiseProfileSeconds(float seconds) {
		this.noiseProfileSeconds = seconds;
	}

	public void setHpfMode(int mode) {
		this.hpfMode = mode;
	}

	public int getHpfMode() {
		return hpfMode;
	}

	public void setLpfMode(int mode) {
		this.lpfMode = mode;
	}

	public int getLpfMode() {
		return lpfMode;
	}

	private void initFilters() {
		hpfX1 = hpfX2 = hpfY1 = hpfY2 = 0;
		lpfX1 = lpfX2 = lpfY1 = lpfY2 = 0;
		hpfCoeffs = computeHpfCoeffs();
		lpfCoeffs = computeLpfCoeffs();
		initNoiseGate();
	}

	private void initNoiseGate() {
		gateState = GateState.CLOSED;
		gateEnvelope = 0f;
		holdCounter = 0;
		gateThreshold = AppConstants.NOISE_GATE_THRESHOLD_RMS;
		gateHysteresis = (int) (gateThreshold * AppConstants.NOISE_GATE_HYSTERESIS);
		float attackMs = AppConstants.NOISE_GATE_ATTACK_MS;
		float releaseMs = AppConstants.NOISE_GATE_RELEASE_MS;
		float holdMs = AppConstants.NOISE_GATE_HOLD_MS;
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
		long sumSquares = 0;
		int sampleCount = length / 2;
		for (int i = 0; i < length; i += 2) {
			short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
			sumSquares += (long) sample * sample;
		}
		float rms = (float) Math.sqrt((double) sumSquares / sampleCount);

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

		if (gateEnvelope < 1.0f) {
			for (int i = 0; i < length; i += 2) {
				short sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
				sample = (short) (sample * gateEnvelope);
				pcmData[i] = (byte) (sample & 0xFF);
				pcmData[i + 1] = (byte) ((sample >> 8) & 0xFF);
			}
		}
	}

	private double[] computeHpfCoeffs() {
		float freq;
		switch (hpfMode) {
			case AppConstants.HPF_80: freq = AppConstants.HPF_FREQ_80; break;
			case AppConstants.HPF_120: freq = AppConstants.HPF_FREQ_120; break;
			default: return null;
		}
		return biquadHighPass(freq, sampleRate, 0.7071);
	}

	private double[] computeLpfCoeffs() {
		float freq;
		switch (lpfMode) {
			case AppConstants.LPF_9500: freq = AppConstants.LPF_FREQ_9500; break;
			case AppConstants.LPF_15000: freq = AppConstants.LPF_FREQ_15000; break;
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

	public void setNoiseReductionListener(NoiseReductionListener listener) {
		this.noiseReductionListener = listener;
	}

	public interface NoiseReductionListener {
		void onNoiseReductionStart();
		void onNoiseReductionProgress(int percent);
		void onNoiseReductionEnd(boolean success);
	}
}
