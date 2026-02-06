/*
 * Copyright 2020 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.vdo.frecorder.app.settings;

import android.content.Context;
import android.media.AudioDeviceInfo;

import com.vdo.frecorder.AppConstants;
import com.vdo.frecorder.BackgroundQueue;
import com.vdo.frecorder.app.AppRecorder;
import com.vdo.frecorder.app.AppRecorderCallback;
import com.vdo.frecorder.audio.AudioDeviceManager;
import com.vdo.frecorder.data.FileRepository;
import com.vdo.frecorder.data.Prefs;
import com.vdo.frecorder.data.database.LocalRepository;
import com.vdo.frecorder.data.database.Record;
import com.vdo.frecorder.exception.AppException;
import com.vdo.frecorder.util.AndroidUtils;
import com.vdo.frecorder.util.FileUtil;
import com.vdo.frecorder.util.TimeUtils;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

public class SettingsPresenter implements SettingsContract.UserActionsListener {

	private SettingsContract.View view;

	private final DecimalFormat decimalFormat;

	private final BackgroundQueue recordingsTasks;
	private final FileRepository fileRepository;
	private final LocalRepository localRepository;
	private final BackgroundQueue loadingTasks;
	private final Prefs prefs;
	private final SettingsMapper settingsMapper;
	private final AppRecorder appRecorder;
	private final AudioDeviceManager audioDeviceManager;
	private AppRecorderCallback appRecorderCallback;
	private AudioDeviceManager.AudioDeviceListener deviceListener;

	public SettingsPresenter(final LocalRepository localRepository, final FileRepository fileRepository,
									 final BackgroundQueue recordingsTasks, final BackgroundQueue loadingTasks,
									 final Prefs prefs, final SettingsMapper settingsMapper, final AppRecorder appRecorder,
									 final AudioDeviceManager audioDeviceManager) {
		this.localRepository = localRepository;
		this.fileRepository = fileRepository;
		this.recordingsTasks = recordingsTasks;
		this.loadingTasks = loadingTasks;
		this.prefs = prefs;
		this.settingsMapper = settingsMapper;
		this.appRecorder = appRecorder;
		this.audioDeviceManager = audioDeviceManager;

		DecimalFormatSymbols formatSymbols = new DecimalFormatSymbols(Locale.getDefault());
		formatSymbols.setDecimalSeparator('.');
		decimalFormat = new DecimalFormat("#.#", formatSymbols);
	}

	@Override
	public void loadSettings() {
		if (view != null) {
			view.showProgress();
		}
		loadingTasks.postRunnable(() -> {
			final List<Long> durations = localRepository.getRecordsDurations();
			long totalDuration = 0;
			for (int i = 0; i < durations.size(); i++) {
				totalDuration += durations.get(i);
			}
			final long finalTotalDuration = totalDuration;
			AndroidUtils.runOnUIThread(() -> {
				if (view != null) {
					view.showTotalRecordsDuration(TimeUtils.formatTimeIntervalHourMinSec(finalTotalDuration / 1000));
					view.showRecordsCount(durations.size());
					updateAvailableSpace();
					view.hideProgress();
				}
			});

//			Public storage no longer available we can not migrate with target API 30.
//			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//				boolean isPublicStorageMigrated = !prefs.isPublicStorageMigrated()
//						&& localRepository.hasRecordsWithPath(fileRepository.getPublicDir().getAbsolutePath());
//				AndroidUtils.runOnUIThread(() -> {
//					if (view != null) {
//						view.showMigratePublicStorage(isPublicStorageMigrated);
//					}
//				});
//			}
		});
		if (view != null) {
			view.updateRecordingInfo(prefs.getSettingRecordingFormat());

			boolean isPublicDir = prefs.isStoreDirPublic();
			view.showStoreInPublicDir(isPublicDir);
			if (isPublicDir) {
				view.showRecordsLocation(fileRepository.getRecordingDir().getAbsolutePath());
			} else {
				view.hideRecordsLocation();
			}
			view.showAskToRenameAfterRecordingStop(prefs.isAskToRenameAfterStopRecording());
			view.showKeepScreenOn(prefs.isKeepScreenOn());
			view.showChannelCount(prefs.getSettingChannelCount());
			String recordingFormatKey = prefs.getSettingRecordingFormat();
			view.showRecordingFormat(recordingFormatKey);
			updateRecordingFormat(recordingFormatKey);
			view.showNamingFormat(prefs.getSettingNamingFormat());
			view.showRecordingBitrate(prefs.getSettingBitrate());
			view.showRecordingSampleRate(prefs.getSettingSampleRate());
			view.showOutputFormat(prefs.getSettingOutputFormat());
			view.showBitDepth(prefs.getSettingBitDepth());
			//This is needed for scoped storage support
			view.showDirectorySetting(prefs.isShowDirectorySetting());

			// Load audio source setting
			loadAudioSourceSetting();

			// Load gain boost setting
			view.showGainBoostLevel(prefs.getGainBoostLevel());

			// Load noise reduction setting
			view.showNoiseReductionEnabled(prefs.isNoiseReductionEnabled());
		}
	}

	private void loadAudioSourceSetting() {
		if (view != null && audioDeviceManager != null) {
			List<AudioDeviceInfo> devices = audioDeviceManager.getAvailableInputDevices();
			int selectedDevice = prefs.getSettingAudioSource();

			// Verify selected device still exists
			if (selectedDevice != AppConstants.AUDIO_SOURCE_DEFAULT_MIC) {
				AudioDeviceInfo device = audioDeviceManager.getDeviceById(selectedDevice);
				if (device == null) {
					// Device no longer available, reset to default
					selectedDevice = AppConstants.AUDIO_SOURCE_DEFAULT_MIC;
					prefs.setSettingAudioSource(selectedDevice);
				}
			}

			view.showAudioSourceSetting(selectedDevice, devices);
		}
	}

	@Override
	public void storeInPublicDir(Context context, boolean b) {
		prefs.setStoreDirPublic(b);
		fileRepository.updateRecordingDir(context, prefs);
		if (b) {
			view.showRecordsLocation(fileRepository.getRecordingDir().getAbsolutePath());
		} else {
			view.hideRecordsLocation();
		}
	}

	@Override
	public void keepScreenOn(boolean keep) {
		prefs.setKeepScreenOn(keep);
	}

	@Override
	public void askToRenameAfterRecordingStop(boolean b) {
		prefs.setAskToRenameAfterStopRecording(b);
	}

	@Override
	public void setSettingRecordingBitrate(int bitrate) {
		prefs.setSettingBitrate(bitrate);
		updateAvailableSpace();
	}

	@Override
	public void setSettingSampleRate(int rate) {
		prefs.setSettingSampleRate(rate);
		updateAvailableSpace();
	}

	@Override
	public void setSettingChannelCount(int count) {
		prefs.setSettingChannelCount(count);
		updateAvailableSpace();
	}

	@Override
	public void setSettingThemeColor(String colorKey) {
		prefs.setSettingThemeColor(colorKey);
	}

	@Override
	public void setSettingNamingFormat(String namingKey) {
		prefs.setSettingNamingFormat(namingKey);
	}

	@Override
	public void setSettingRecordingFormat(String formatKey) {
		prefs.setSettingRecordingFormat(formatKey);
		if (view != null) {
			view.updateRecordingInfo(formatKey);
			view.showChannelCount(prefs.getSettingChannelCount());
		}
		updateRecordingFormat(formatKey);
		updateAvailableSpace();
	}

	@Override
	public void setSettingOutputFormat(String outputFormatKey) {
		prefs.setSettingOutputFormat(outputFormatKey);
	}

	@Override
	public void setSettingBitDepth(int bitDepth) {
		prefs.setSettingBitDepth(bitDepth);
	}

	@Override
	public void setSettingAudioSource(int deviceId) {
		prefs.setSettingAudioSource(deviceId);
	}

	@Override
	public void setGainBoostLevel(int level) {
		prefs.setGainBoostLevel(level);
	}

	@Override
	public void setNoiseReductionEnabled(boolean enabled) {
		prefs.setNoiseReductionEnabled(enabled);
	}

	@Override
	public void refreshAudioDevices() {
		loadAudioSourceSetting();
	}

	@Override
	public void deleteAllRecords() {
		recordingsTasks.postRunnable(() -> {
//				List<Record> records  = localRepository.getAllRecords();
//				for (int i = 0; i < records.size(); i++) {
//					fileRepository.deleteRecordFile(records.get(i).getPath());
//				}
//				boolean b2 = localRepository.deleteAllRecords();
//				prefs.setActiveRecord(-1);
//				if (b2) {
//					AndroidUtils.runOnUIThread(new Runnable() {
//						@Override
//						public void run() {
//							if (view != null) {
//								view.showAllRecordsDeleted();
//							}
//						}});
//				} else {
//					AndroidUtils.runOnUIThread(new Runnable() {
//						@Override
//						public void run() {
//							if (view != null) {
//								view.showFailDeleteAllRecords();
//							}
//						}});
//				}
		});
	}

	@Override
	public void bindView(final SettingsContract.View view) {
		this.view = view;

		if (appRecorder.isRecording()) {
			view.disableAudioSettings();
			appRecorderCallback = new AppRecorderCallback() {
				@Override public void onRecordingStarted(File file) { }
				@Override public void onRecordingPaused() { }
				@Override public void onRecordingResumed() { }
				@Override public void onRecordingStopped(File file, Record record) {
						view.enableAudioSettings();
				}
				@Override public void onRecordingProgress(long mills, int amp) { }
				@Override public void onError(AppException throwable) { }
			};
			appRecorder.addRecordingCallback(appRecorderCallback);
		} else {
			view.enableAudioSettings();
		}

		// Register device listener for USB device connect/disconnect
		if (audioDeviceManager != null) {
			deviceListener = devices -> AndroidUtils.runOnUIThread(this::loadAudioSourceSetting);
			audioDeviceManager.registerDeviceListener(deviceListener);
		}
	}

	@Override
	public void unbindView() {
		if (view != null) {
			this.view = null;
			appRecorder.removeRecordingCallback(appRecorderCallback);
			if (audioDeviceManager != null && deviceListener != null) {
				audioDeviceManager.unregisterDeviceListener(deviceListener);
				deviceListener = null;
			}
		}
	}

	@Override
	public void clear() {
		if (view != null) {
			unbindView();
		}
	}

	@Override
	public void resetSettings() {
		prefs.resetSettings();
	}

	@Override
	public void onRecordsLocationClick() {
		if (view != null) {
			view.openRecordsLocation(fileRepository.getRecordingDir());
		}
	}

	private void updateAvailableSpace() {
		final long space = FileUtil.getFree(fileRepository.getRecordingDir());
		String format = prefs.getSettingRecordingFormat();
		int sampleRate = prefs.getSettingSampleRate();
		int channelsCount = prefs.getSettingChannelCount();
		final long time = spaceToTimeSecs(space, format, sampleRate, 0, channelsCount);
		if (view != null) {
			view.showAvailableSpace(TimeUtils.formatTimeIntervalHourMinSec(time));
			view.showSizePerMin(decimalFormat.format(sizePerMin(format, sampleRate, 0, channelsCount) / 1000000f));
			view.showInformation(settingsMapper.convertFormatsToString(format) + AppConstants.SEPARATOR
					+ settingsMapper.convertSampleRateToString(sampleRate) + AppConstants.SEPARATOR
					+ settingsMapper.convertChannelsToString(channelsCount));
		}
	}

	private long spaceToTimeSecs(long spaceBytes, String recordingFormat, int sampleRate, int bitrate, int channels) {
		// Recording is always WAV internally
		return 1000 * (spaceBytes/((long) sampleRate * channels * 2));
	}

	private long sizePerMin(String recordingFormat, int sampleRate, int bitrate, int channels) {
		// Recording is always WAV internally
		return 60 * ((long) sampleRate * channels * 2);
	}

	private void updateRecordingFormat(String formatKey) {
		// WAV is the only recording format; bitrate selector is not needed
		view.hideBitrateSelector();
	}
}
