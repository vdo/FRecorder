/*
 * Copyright 2018 Dmytro Ponomarenko
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

package com.vdo.frecorder;

import android.annotation.SuppressLint;
import android.content.Context;

import com.vdo.frecorder.app.AppRecorder;
import com.vdo.frecorder.app.AppRecorderImpl;
import com.vdo.frecorder.app.browser.FileBrowserContract;
import com.vdo.frecorder.app.browser.FileBrowserPresenter;
import com.vdo.frecorder.app.lostrecords.LostRecordsContract;
import com.vdo.frecorder.app.lostrecords.LostRecordsPresenter;
import com.vdo.frecorder.app.moverecords.MoveRecordsViewModel;
import com.vdo.frecorder.app.settings.SettingsMapper;
import com.vdo.frecorder.app.setup.SetupContract;
import com.vdo.frecorder.app.setup.SetupPresenter;
import com.vdo.frecorder.app.trash.TrashContract;
import com.vdo.frecorder.app.trash.TrashPresenter;
import com.vdo.frecorder.audio.AudioDeviceManager;
import com.vdo.frecorder.audio.AudioWaveformVisualization;
import com.vdo.frecorder.audio.player.AudioPlayerNew;
import com.vdo.frecorder.audio.player.PlayerContractNew;
import com.vdo.frecorder.audio.recorder.AudioRecorder;
import com.vdo.frecorder.audio.recorder.ThreeGpRecorder;
import com.vdo.frecorder.audio.recorder.RecorderContract;
import com.vdo.frecorder.audio.recorder.WavRecorder;
import com.vdo.frecorder.data.RecordDataSource;
import com.vdo.frecorder.data.FileRepository;
import com.vdo.frecorder.data.FileRepositoryImpl;
import com.vdo.frecorder.data.Prefs;
import com.vdo.frecorder.data.PrefsImpl;
import com.vdo.frecorder.data.database.LocalRepository;
import com.vdo.frecorder.data.database.LocalRepositoryImpl;
import com.vdo.frecorder.data.database.RecordsDataSource;
import com.vdo.frecorder.app.main.MainContract;
import com.vdo.frecorder.app.main.MainPresenter;
import com.vdo.frecorder.app.records.RecordsContract;
import com.vdo.frecorder.app.records.RecordsPresenter;
import com.vdo.frecorder.app.settings.SettingsContract;
import com.vdo.frecorder.app.settings.SettingsPresenter;
import com.vdo.frecorder.data.database.TrashDataSource;

public class Injector {

	private BackgroundQueue loadingTasks;
	private BackgroundQueue recordingTasks;
	private BackgroundQueue importTasks;
	private BackgroundQueue processingTasks;
	private BackgroundQueue copyTasks;

	private MainContract.UserActionsListener mainPresenter;
	private RecordDataSource recordDataSource;
	private RecordsContract.UserActionsListener recordsPresenter;
	private SettingsContract.UserActionsListener settingsPresenter;
	private LostRecordsContract.UserActionsListener lostRecordsPresenter;
	private FileBrowserContract.UserActionsListener fileBrowserPresenter;
	private TrashContract.UserActionsListener trashPresenter;
	private SetupContract.UserActionsListener setupPresenter;

	private MoveRecordsViewModel moveRecordsViewModel;

	private AudioPlayerNew audioPlayer = null;

	public Prefs providePrefs(Context context) {
		return PrefsImpl.getInstance(context);
	}

	public RecordsDataSource provideRecordsDataSource(Context context) {
		return RecordsDataSource.getInstance(context);
	}

	public TrashDataSource provideTrashDataSource(Context context) {
		return TrashDataSource.getInstance(context);
	}

	public FileRepository provideFileRepository(Context context) {
		return FileRepositoryImpl.getInstance(context, providePrefs(context));
	}

	public LocalRepository provideLocalRepository(Context context) {
		return LocalRepositoryImpl.getInstance(provideRecordsDataSource(context), provideTrashDataSource(context), provideFileRepository(context), providePrefs(context));
	}

	public AppRecorder provideAppRecorder(Context context) {
		return AppRecorderImpl.getInstance(provideAudioRecorder(context), provideLocalRepository(context),
				provideLoadingTasksQueue(), provideRecordDataSource(context), providePrefs(context));
	}

	public AudioWaveformVisualization provideAudioWaveformVisualization() {
		return new AudioWaveformVisualization(provideProcessingTasksQueue());
	}

	public BackgroundQueue provideLoadingTasksQueue() {
		if (loadingTasks == null) {
			loadingTasks = new BackgroundQueue("LoadingTasks");
		}
		return loadingTasks;
	}

	public BackgroundQueue provideRecordingTasksQueue() {
		if (recordingTasks == null) {
			recordingTasks = new BackgroundQueue("RecordingTasks");
		}
		return recordingTasks;
	}

	public BackgroundQueue provideImportTasksQueue() {
		if (importTasks == null) {
			importTasks = new BackgroundQueue("ImportTasks");
		}
		return importTasks;
	}

	public BackgroundQueue provideProcessingTasksQueue() {
		if (processingTasks == null) {
			processingTasks = new BackgroundQueue("ProcessingTasks");
		}
		return processingTasks;
	}

	public BackgroundQueue provideCopyTasksQueue() {
		if (copyTasks == null) {
			copyTasks = new BackgroundQueue("CopyTasks");
		}
		return copyTasks;
	}

	public ColorMap provideColorMap(Context context) {
		return ColorMap.getInstance(providePrefs(context));
	}

	public SettingsMapper provideSettingsMapper(Context context) {
		return SettingsMapper.getInstance(context);
	}

	public PlayerContractNew.Player provideAudioPlayer() {
		if (audioPlayer == null) {
			synchronized (PlayerContractNew.Player.class) {
				if (audioPlayer == null) {
					audioPlayer = new AudioPlayerNew();
				}
			}
		}
		return audioPlayer;
	}

	public RecorderContract.Recorder provideAudioRecorder(Context context) {
		return WavRecorder.getInstance();
	}

	public AudioDeviceManager provideAudioDeviceManager(Context context) {
		return AudioDeviceManager.getInstance(context);
	}

	public RecordDataSource provideRecordDataSource(Context context) {
		if (recordDataSource == null) {
			recordDataSource = new RecordDataSource(
					provideLocalRepository(context),
					providePrefs(context)
			);
		}
		return recordDataSource;
	}

	public MainContract.UserActionsListener provideMainPresenter(Context context) {
		if (mainPresenter == null) {
			mainPresenter = new MainPresenter(providePrefs(context), provideFileRepository(context),
					provideLocalRepository(context), provideAudioPlayer(), provideAppRecorder(context),
					provideRecordingTasksQueue(), provideLoadingTasksQueue(), provideProcessingTasksQueue(),
					provideImportTasksQueue(), provideSettingsMapper(context), provideRecordDataSource(context));
		}
		return mainPresenter;
	}

	public RecordsContract.UserActionsListener provideRecordsPresenter(Context context) {
		if (recordsPresenter == null) {
			recordsPresenter = new RecordsPresenter(provideLocalRepository(context), provideFileRepository(context),
					provideLoadingTasksQueue(), provideRecordingTasksQueue(),
					provideAudioPlayer(), provideAppRecorder(context), providePrefs(context));
		}
		return recordsPresenter;
	}

	public SettingsContract.UserActionsListener provideSettingsPresenter(Context context) {
		if (settingsPresenter == null) {
			settingsPresenter = new SettingsPresenter(provideLocalRepository(context), provideFileRepository(context),
					provideRecordingTasksQueue(), provideLoadingTasksQueue(), providePrefs(context),
					provideSettingsMapper(context), provideAppRecorder(context), provideAudioDeviceManager(context));
		}
		return settingsPresenter;
	}

	public TrashContract.UserActionsListener provideTrashPresenter(Context context) {
		if (trashPresenter == null) {
			trashPresenter = new TrashPresenter(provideLoadingTasksQueue(), provideRecordingTasksQueue(),
					provideFileRepository(context), provideLocalRepository(context));
		}
		return trashPresenter;
	}

	public SetupContract.UserActionsListener provideSetupPresenter(Context context) {
		if (setupPresenter == null) {
			setupPresenter = new SetupPresenter(providePrefs(context));
		}
		return setupPresenter;
	}

	@SuppressLint("UnsafeOptInUsageWarning")
	public MoveRecordsViewModel provideMoveRecordsViewModel(Context context) {
		if (moveRecordsViewModel == null) {
			moveRecordsViewModel = new MoveRecordsViewModel(
					provideLoadingTasksQueue(),
					provideLocalRepository(context),
					provideFileRepository(context),
					provideSettingsMapper(context),
					provideAudioPlayer(),
					provideAppRecorder(context),
					providePrefs(context)
			);
		}
		return moveRecordsViewModel;
	}

	public LostRecordsContract.UserActionsListener provideLostRecordsPresenter(Context context) {
		if (lostRecordsPresenter == null) {
			lostRecordsPresenter = new LostRecordsPresenter(provideLoadingTasksQueue(), provideRecordingTasksQueue(),
					provideLocalRepository(context), providePrefs(context));
		}
		return lostRecordsPresenter;
	}

	public FileBrowserContract.UserActionsListener provideFileBrowserPresenter(Context context) {
		if (fileBrowserPresenter == null) {
			fileBrowserPresenter = new FileBrowserPresenter(providePrefs(context), provideAppRecorder(context), provideImportTasksQueue(),
					provideLoadingTasksQueue(), provideRecordingTasksQueue(),
					provideLocalRepository(context), provideFileRepository(context));
		}
		return fileBrowserPresenter;
	}

	public void releaseTrashPresenter() {
		if (trashPresenter != null) {
			trashPresenter.clear();
			trashPresenter = null;
		}
	}

	public void releaseLostRecordsPresenter() {
		if (lostRecordsPresenter != null) {
			lostRecordsPresenter.clear();
			lostRecordsPresenter = null;
		}
	}

	public void releaseFileBrowserPresenter() {
		if (fileBrowserPresenter != null) {
			fileBrowserPresenter.clear();
			fileBrowserPresenter = null;
		}
	}

	public void releaseRecordsPresenter() {
		if (recordsPresenter != null) {
			recordsPresenter.clear();
			recordsPresenter = null;
		}
	}

	public void releaseMainPresenter() {
		if (mainPresenter != null) {
			mainPresenter.clear();
			mainPresenter = null;
		}
	}

	public void releaseSettingsPresenter() {
		if (settingsPresenter != null) {
			settingsPresenter.clear();
			settingsPresenter = null;
		}
	}

	public void releaseSetupPresenter() {
		if (setupPresenter != null) {
			setupPresenter.clear();
			setupPresenter = null;
		}
	}

	@SuppressLint("UnsafeOptInUsageWarning")
	public void releaseMoveRecordsViewModel() {
		if (moveRecordsViewModel != null) {
			moveRecordsViewModel.clear();
			moveRecordsViewModel = null;
		}
	}

	public void closeTasks() {
		loadingTasks.cleanupQueue();
		loadingTasks.close();
		importTasks.cleanupQueue();
		importTasks.close();
		processingTasks.cleanupQueue();
		processingTasks.close();
		recordingTasks.cleanupQueue();
		recordingTasks.close();
	}
}
