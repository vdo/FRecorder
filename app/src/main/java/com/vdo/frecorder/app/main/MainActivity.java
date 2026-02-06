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

package com.vdo.frecorder.app.main;

import android.Manifest;
import android.animation.Animator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MenuInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.vdo.frecorder.ARApplication;
import com.vdo.frecorder.ColorMap;
import com.vdo.frecorder.IntArrayList;
import com.vdo.frecorder.R;
import com.vdo.frecorder.app.DecodeService;
import com.vdo.frecorder.app.DecodeServiceListener;
import com.vdo.frecorder.app.DownloadService;
import com.vdo.frecorder.app.PlaybackService;
import com.vdo.frecorder.app.RecordingService;
import com.vdo.frecorder.app.info.ActivityInformation;
import com.vdo.frecorder.app.info.RecordInfo;
import com.vdo.frecorder.app.moverecords.MoveRecordsActivity;
import com.vdo.frecorder.app.records.RecordsActivity;
import com.vdo.frecorder.app.settings.SettingsActivity;
import com.vdo.frecorder.app.welcome.WelcomeActivity;
import com.vdo.frecorder.app.widget.RecordingWaveformView;
import com.vdo.frecorder.app.widget.WaveformViewNew;
import com.vdo.frecorder.AppConstants;
import com.vdo.frecorder.audio.AudioDecoder;
import com.vdo.frecorder.audio.player.AudioPlayerNew;
import com.vdo.frecorder.audio.player.PlayerContractNew;
import com.vdo.frecorder.audio.AudioDeviceManager;
import com.vdo.frecorder.audio.monitor.AudioMonitor;
import com.vdo.frecorder.audio.recorder.RecorderContract;
import com.vdo.frecorder.audio.recorder.WavRecorder;
import com.vdo.frecorder.data.FileRepository;
import com.vdo.frecorder.data.Prefs;

import android.media.AudioDeviceInfo;
import com.vdo.frecorder.data.database.Record;
import com.vdo.frecorder.exception.CantCreateFileException;
import com.vdo.frecorder.exception.ErrorParser;
import com.vdo.frecorder.util.AndroidUtils;
import com.vdo.frecorder.util.AnimationUtil;
import com.vdo.frecorder.util.FileUtil;
import com.vdo.frecorder.util.TimeUtils;

import java.io.File;
import java.util.List;

import android.app.AlertDialog;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import timber.log.Timber;

public class MainActivity extends Activity implements MainContract.View, View.OnClickListener {

// TODO: Fix waveform when long record (there is no waveform)
// TODO: Ability to scroll up from the bottom of the list
//	TODO: Bluetooth micro support
//	TODO: Mp3 support
//	TODO: Add Noise gate

	public static final int REQ_CODE_REC_AUDIO_AND_WRITE_EXTERNAL = 101;
	public static final int REQ_CODE_RECORD_AUDIO = 303;
	public static final int REQ_CODE_WRITE_EXTERNAL_STORAGE = 404;
	public static final int REQ_CODE_READ_EXTERNAL_STORAGE_IMPORT = 405;
	public static final int REQ_CODE_READ_EXTERNAL_STORAGE_PLAYBACK = 406;
	public static final int REQ_CODE_READ_EXTERNAL_STORAGE_DOWNLOAD = 407;
	public static final int REQ_CODE_POST_NOTIFICATIONS = 408;
	public static final int REQ_CODE_IMPORT_AUDIO = 11;

	private WaveformViewNew waveformView;
	private RecordingWaveformView recordingWaveformView;
	private TextView txtProgress;
	private TextView txtDuration;
	private TextView txtZeroTime;
	private TextView txtName;
	private TextView txtRecordInfo;
	private ImageButton btnPlay;
	private ImageButton btnStop;
	private Button btnRecord;
	private Button btnRecordingStop;
	private ImageButton btnShare;
	private ImageButton btnImport;
	private ImageButton btnMonitor;
	private ImageButton btnHpf;
	private ImageButton btnLpf;
	private ImageButton btnNoiseGate;
	private ImageButton btnMicBoost;
	private ImageButton btnNoiseReduction;
	private boolean isMonitoringActive = false;
	private ProgressBar progressBar;
	private SeekBar playProgress;
	private LinearLayout pnlImportProgress;
	private LinearLayout pnlRecordProcessing;
	private ImageView ivPlaceholder;
	private AlertDialog noiseReductionDialog;

	private MainContract.UserActionsListener presenter;
	private ColorMap colorMap;
	private FileRepository fileRepository;
	private ColorMap.OnThemeColorChangeListener onThemeColorChangeListener;

	private final ServiceConnection connection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			DecodeService.LocalBinder binder = (DecodeService.LocalBinder) service;
			DecodeService decodeService = binder.getService();
			decodeService.setDecodeListener(new DecodeServiceListener() {
				@Override
				public void onStartProcessing() {
					runOnUiThread(MainActivity.this::showRecordProcessing);
				}

				@Override
				public void onFinishProcessing() {
					runOnUiThread(() -> {
						hideRecordProcessing();
						presenter.loadActiveRecord();
					});
				}
			});
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			hideRecordProcessing();
		}

		@Override
		public void onBindingDied(ComponentName name) {
			hideRecordProcessing();
		}
	};

	private float space = 75;

	public static Intent getStartIntent(Context context) {
		return new Intent(context, MainActivity.class);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		colorMap = ARApplication.getInjector().provideColorMap(getApplicationContext());
		setTheme(colorMap.getAppThemeResource());
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		waveformView = findViewById(R.id.record);
		recordingWaveformView = findViewById(R.id.recording_view);
		txtProgress = findViewById(R.id.txt_progress);
		txtDuration = findViewById(R.id.txt_duration);
		txtZeroTime = findViewById(R.id.txt_zero_time);
		txtName = findViewById(R.id.txt_name);
		txtRecordInfo = findViewById(R.id.txt_record_info);
		btnPlay = findViewById(R.id.btn_play);
		btnRecord = findViewById(R.id.btn_record);
		btnRecordingStop = findViewById(R.id.btn_record_stop);
		btnStop = findViewById(R.id.btn_stop);
		ImageButton btnRecordsList = findViewById(R.id.btn_records_list);
		ImageButton btnSettings = findViewById(R.id.btn_settings);
		btnShare = findViewById(R.id.btn_share);
		btnImport = findViewById(R.id.btn_import);
		btnMonitor = findViewById(R.id.btn_monitor);
		btnHpf = findViewById(R.id.btn_hpf);
		btnLpf = findViewById(R.id.btn_lpf);
		btnNoiseGate = findViewById(R.id.btn_noise_gate);
		btnMicBoost = findViewById(R.id.btn_mic_boost);
		btnNoiseReduction = findViewById(R.id.btn_noise_reduction);
		progressBar = findViewById(R.id.progress);
		playProgress = findViewById(R.id.play_progress);
		pnlImportProgress = findViewById(R.id.pnl_import_progress);
		pnlRecordProcessing = findViewById(R.id.pnl_record_processing);
		ivPlaceholder = findViewById(R.id.placeholder);
		ivPlaceholder.setImageResource(R.drawable.waveform);

		txtProgress.setText(TimeUtils.formatTimeIntervalHourMinSec2(0));

		btnRecordingStop.setVisibility(View.GONE);
		btnRecordingStop.setEnabled(false);

		btnPlay.setOnClickListener(this);
		btnRecord.setOnClickListener(this);
		btnRecordingStop.setOnClickListener(this);
		btnStop.setOnClickListener(this);
		btnRecordsList.setOnClickListener(this);
		btnSettings.setOnClickListener(this);
		btnShare.setOnClickListener(this);
		btnImport.setOnClickListener(this);
		txtName.setOnClickListener(this);
		btnMonitor.setOnClickListener(this);
		btnHpf.setOnClickListener(this);
		btnLpf.setOnClickListener(this);
		btnNoiseGate.setOnClickListener(this);
		btnMicBoost.setOnClickListener(this);
		btnNoiseReduction.setOnClickListener(this);
		isMonitoringActive = AudioMonitor.getInstance().isMonitoring();
		btnMonitor.setAlpha(isMonitoringActive ? 1.0f : 0.5f);
		updateFilterButtonAlpha();
		updateNoiseGateButtonAlpha();
		updateMicBoostButtonAlpha();
		updateNoiseReductionButtonAlpha();
		space = getResources().getDimension(R.dimen.spacing_xnormal);

		playProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					int val = (int)AndroidUtils.dpToPx(progress * waveformView.getWaveformLength() / 1000);
					waveformView.seekPx(val);
					//TODO: Find a better way to convert px to mills here
					presenter.seekPlayback(waveformView.pxToMill(val));
				}
			}

			@Override public void onStartTrackingTouch(SeekBar seekBar) {
				presenter.disablePlaybackProgressListener();
			}

			@Override public void onStopTrackingTouch(SeekBar seekBar) {
				presenter.enablePlaybackProgressListener();
			}
		});

		presenter = ARApplication.getInjector().provideMainPresenter(getApplicationContext());
		fileRepository = ARApplication.getInjector().provideFileRepository(getApplicationContext());

		waveformView.setOnSeekListener(new WaveformViewNew.OnSeekListener() {
			@Override
			public void onStartSeek() {
				presenter.disablePlaybackProgressListener();
			}

			@Override
			public void onSeek(int px, long mills) {
				presenter.enablePlaybackProgressListener();
				//TODO: Find a better way to convert px to mills here
				presenter.seekPlayback(waveformView.pxToMill(px));

				int length = waveformView.getWaveformLength();
				if (length > 0) {
					playProgress.setProgress(1000 * (int) AndroidUtils.pxToDp(px) / length);
				}
				txtProgress.setText(TimeUtils.formatTimeIntervalHourMinSec2(mills));
			}
			@Override
			public void onSeeking(int px, long mills) {
				int length = waveformView.getWaveformLength();
				if (length > 0) {
					playProgress.setProgress(1000 * (int) AndroidUtils.pxToDp(px) / length);
				}
				txtProgress.setText(TimeUtils.formatTimeIntervalHourMinSec2(mills));
			}
		});
		onThemeColorChangeListener = colorKey -> {
			setTheme(colorMap.getAppThemeResource());
			recreate();
		};
		colorMap.addOnThemeColorChangeListener(onThemeColorChangeListener);

		//Check start recording shortcut
		if ("android.intent.action.ACTION_RUN".equals(getIntent().getAction())) {
			if (checkRecordPermission2()) {
				if (checkStoragePermission2()) {
					//Start or stop recording
					startRecordingService();
				}
			}
		}
		checkNotificationPermission();
		checkRecordPermission2();
	}

	@Override
	protected void onStart() {
		super.onStart();
		presenter.bindView(this);
		PlayerContractNew.Player player = ARApplication.getInjector().provideAudioPlayer();
		if (player instanceof AudioPlayerNew) {
			((AudioPlayerNew) player).setContext(getApplicationContext());
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			//This is needed for scoped storage support
			presenter.storeInPrivateDir(getApplicationContext());
//			presenter.checkPublicStorageRecords();
		}
		presenter.checkFirstRun();
		presenter.setAudioRecorder(ARApplication.getInjector().provideAudioRecorder(getApplicationContext()));
		presenter.updateRecordingDir(getApplicationContext());
		presenter.loadActiveRecord();

		// Sync monitor button with actual AudioMonitor state
		isMonitoringActive = AudioMonitor.getInstance().isMonitoring();
		btnMonitor.setAlpha(isMonitoringActive ? 1.0f : 0.5f);

		// Set up noise reduction listener
		RecorderContract.Recorder rec = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		if (rec instanceof WavRecorder) {
			((WavRecorder) rec).setNoiseReductionListener(new WavRecorder.NoiseReductionListener() {
				@Override
				public void onNoiseReductionStart() {
					showNoiseReductionDialog();
				}
				@Override
				public void onNoiseReductionProgress(int percent) {
					updateNoiseReductionDialog(percent);
				}
				@Override
				public void onNoiseReductionEnd(boolean success) {
					dismissNoiseReductionDialog(success);
				}
			});
		}

		Intent intent = new Intent(this, DecodeService.class);
		bindService(intent, connection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onStop() {
		super.onStop();
		unbindService(connection);
		if (presenter != null) {
			presenter.unbindView();
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		colorMap.removeOnThemeColorChangeListener(onThemeColorChangeListener);
	}

	@Override
	public void onClick(View view) {
		int id = view.getId();
		if (id == R.id.btn_play) {
			presenter.onPlaybackClick(getApplicationContext(), checkStoragePermissionPlayback());
		} else if (id == R.id.btn_record) {
			if (checkRecordPermission2()) {
				if (checkStoragePermission2()) {
					//Start or stop recording
					startRecordingService();
					presenter.pauseUnpauseRecording(getApplicationContext());
				}
			}
		} else if (id == R.id.btn_record_stop) {
			presenter.stopRecording();
		} else if (id == R.id.btn_stop) {
			presenter.stopPlayback();
		} else if (id == R.id.btn_records_list) {
			startActivity(RecordsActivity.getStartIntent(getApplicationContext()));
		} else if (id == R.id.btn_settings) {
			startActivity(SettingsActivity.getStartIntent(getApplicationContext()));
		} else if (id == R.id.btn_share) {
			showMenu(view);
		} else if (id == R.id.btn_import) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startFileSelector();
			} else {
				if (checkStoragePermissionImport()) {
					startFileSelector();
				}
			}
		} else if (id == R.id.txt_name) {
			presenter.onRenameRecordClick();
		} else if (id == R.id.btn_monitor) {
			toggleMonitoring();
		} else if (id == R.id.btn_hpf) {
			toggleHpf();
		} else if (id == R.id.btn_lpf) {
			toggleLpf();
		} else if (id == R.id.btn_noise_gate) {
			toggleNoiseGate();
		} else if (id == R.id.btn_mic_boost) {
			toggleMicBoost();
		} else if (id == R.id.btn_noise_reduction) {
			toggleNoiseReduction();
		}
	}

	private void startFileSelector() {
		Intent intent_upload = new Intent();
		intent_upload.setType("audio/*");
		intent_upload.addCategory(Intent.CATEGORY_OPENABLE);
//		intent_upload.setAction(Intent.ACTION_GET_CONTENT);
		intent_upload.setAction(Intent.ACTION_OPEN_DOCUMENT);
		try {
			startActivityForResult(intent_upload, REQ_CODE_IMPORT_AUDIO);
		} catch (ActivityNotFoundException e) {
			Timber.e(e);
			showError(R.string.cant_import_files);
		}
	}

	private void toggleMonitoring() {
		RecorderContract.Recorder recorder = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		if (!(recorder instanceof WavRecorder)) {
			Toast.makeText(this, R.string.monitoring_wav_only, Toast.LENGTH_SHORT).show();
			return;
		}
		WavRecorder wavRecorder = (WavRecorder) recorder;
		AudioMonitor monitor = AudioMonitor.getInstance();
		monitor.setContext(getApplicationContext());

		if (!isMonitoringActive) {
			// About to enable monitoring — check for feedback risk
			Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
			int deviceId = prefs.getSettingAudioSource();
			AudioDeviceInfo audioDevice = null;
			if (deviceId != AppConstants.AUDIO_SOURCE_DEFAULT_MIC) {
				AudioDeviceManager adm = ARApplication.getInjector().provideAudioDeviceManager(getApplicationContext());
				audioDevice = adm.getDeviceById(deviceId);
			}

			if (monitor.hasFeedbackRisk(audioDevice)) {
				new AlertDialog.Builder(this)
						.setTitle(R.string.monitoring_feedback_warning_title)
						.setMessage(R.string.monitoring_feedback_warning)
						.setPositiveButton(R.string.btn_ok, null)
						.show();
				return;
			}

			enableMonitoring(wavRecorder, monitor, prefs, audioDevice);
		} else {
			// Stop monitoring
			if (monitor.isStandalone()) {
				monitor.stopStandalone();
			}
			wavRecorder.setMonitoringEnabled(false);
			isMonitoringActive = false;
		}

		btnMonitor.setAlpha(isMonitoringActive ? 1.0f : 0.5f);
		Toast.makeText(this,
				isMonitoringActive ? R.string.monitoring_on : R.string.monitoring_off,
				Toast.LENGTH_SHORT).show();
	}

	private void enableMonitoring(WavRecorder wavRecorder, AudioMonitor monitor, Prefs prefs, AudioDeviceInfo audioDevice) {
		isMonitoringActive = true;
		monitor.setNoiseGateEnabled(prefs.isNoiseGateEnabled());
		monitor.setHpfMode(prefs.getHpfMode());
		monitor.setLpfMode(prefs.getLpfMode());
		monitor.setGainBoostLevel(prefs.getGainBoostLevel());
		if (wavRecorder.isRecording()) {
			// Recording is active — let recording loop feed the monitor
			wavRecorder.setMonitoringEnabled(true);
		} else {
			// Not recording — start standalone monitoring
			wavRecorder.setMonitoringEnabled(true); // flag for when recording starts
			int sr = prefs.getSettingSampleRate();
			int ch = prefs.getSettingChannelCount();
			monitor.startStandalone(sr, ch, audioDevice);
		}
	}

	private void toggleHpf() {
		RecorderContract.Recorder recorder = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		if (!(recorder instanceof WavRecorder)) {
			Toast.makeText(this, R.string.filter_wav_only, Toast.LENGTH_SHORT).show();
			return;
		}
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		WavRecorder wavRecorder = (WavRecorder) recorder;
		int current = prefs.getHpfMode();
		int next = (current + 1) % 3; // OFF -> 80 -> 120 -> OFF
		prefs.setHpfMode(next);
		wavRecorder.setHpfMode(next);
		AudioMonitor.getInstance().setHpfMode(next);
		updateFilterButtonAlpha();
		int msgRes;
		switch (next) {
			case AppConstants.HPF_80: msgRes = R.string.hpf_80; break;
			case AppConstants.HPF_120: msgRes = R.string.hpf_120; break;
			default: msgRes = R.string.hpf_off; break;
		}
		Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show();
	}

	private void toggleLpf() {
		RecorderContract.Recorder recorder = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		if (!(recorder instanceof WavRecorder)) {
			Toast.makeText(this, R.string.filter_wav_only, Toast.LENGTH_SHORT).show();
			return;
		}
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		WavRecorder wavRecorder = (WavRecorder) recorder;
		int current = prefs.getLpfMode();
		int next = (current + 1) % 3; // OFF -> 9500 -> 15000 -> OFF
		prefs.setLpfMode(next);
		wavRecorder.setLpfMode(next);
		AudioMonitor.getInstance().setLpfMode(next);
		updateFilterButtonAlpha();
		int msgRes;
		switch (next) {
			case AppConstants.LPF_9500: msgRes = R.string.lpf_9500; break;
			case AppConstants.LPF_15000: msgRes = R.string.lpf_15000; break;
			default: msgRes = R.string.lpf_off; break;
		}
		Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show();
	}

	private void updateFilterButtonAlpha() {
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		btnHpf.setAlpha(prefs.getHpfMode() != AppConstants.HPF_OFF ? 1.0f : 0.5f);
		btnLpf.setAlpha(prefs.getLpfMode() != AppConstants.LPF_OFF ? 1.0f : 0.5f);
	}

	private void toggleNoiseGate() {
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		AudioMonitor monitor = AudioMonitor.getInstance();
		boolean enabled = !prefs.isNoiseGateEnabled();
		prefs.setNoiseGateEnabled(enabled);
		monitor.setNoiseGateEnabled(enabled);
		RecorderContract.Recorder rec = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		if (rec instanceof WavRecorder) {
			((WavRecorder) rec).setNoiseGateEnabled(enabled);
		}
		updateNoiseGateButtonAlpha();
		Toast.makeText(this,
				enabled ? R.string.noise_gate_on : R.string.noise_gate_off,
				Toast.LENGTH_SHORT).show();
	}

	private void updateNoiseGateButtonAlpha() {
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		btnNoiseGate.setAlpha(prefs.isNoiseGateEnabled() ? 1.0f : 0.5f);
	}

	private void toggleMicBoost() {
		RecorderContract.Recorder recorder = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		if (!(recorder instanceof WavRecorder)) {
			Toast.makeText(this, R.string.filter_wav_only, Toast.LENGTH_SHORT).show();
			return;
		}
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		WavRecorder wavRecorder = (WavRecorder) recorder;
		int current = prefs.getGainBoostLevel();
		int next = (current + 1) % 3; // OFF -> 6dB -> 12dB -> OFF
		prefs.setGainBoostLevel(next);
		wavRecorder.setGainBoostLevel(next);
		AudioMonitor.getInstance().setGainBoostLevel(next);
		updateMicBoostButtonAlpha();
		int msgRes;
		switch (next) {
			case AppConstants.GAIN_BOOST_6DB: msgRes = R.string.mic_boost_6db; break;
			case AppConstants.GAIN_BOOST_12DB: msgRes = R.string.mic_boost_12db; break;
			default: msgRes = R.string.mic_boost_off; break;
		}
		Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show();
	}

	private void updateMicBoostButtonAlpha() {
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		btnMicBoost.setAlpha(prefs.getGainBoostLevel() != AppConstants.GAIN_BOOST_OFF ? 1.0f : 0.5f);
	}

	private void toggleNoiseReduction() {
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		boolean enabled = !prefs.isNoiseReductionEnabled();
		prefs.setNoiseReductionEnabled(enabled);
		RecorderContract.Recorder rec = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		if (rec instanceof WavRecorder) {
			((WavRecorder) rec).setNoiseReductionEnabled(enabled);
		}
		updateNoiseReductionButtonAlpha();
		Toast.makeText(this,
				enabled ? R.string.noise_reduction_on : R.string.noise_reduction_off,
				Toast.LENGTH_SHORT).show();
	}

	private void updateNoiseReductionButtonAlpha() {
		Prefs prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		btnNoiseReduction.setAlpha(prefs.isNoiseReductionEnabled() ? 1.0f : 0.5f);
	}

	private void showNoiseReductionDialog() {
		if (noiseReductionDialog != null && noiseReductionDialog.isShowing()) return;
		android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
		pb.setIndeterminate(false);
		pb.setMax(100);
		pb.setProgress(0);
		pb.setPadding(48, 24, 48, 24);
		noiseReductionDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.noise_reduction_processing)
				.setView(pb)
				.setCancelable(false)
				.create();
		noiseReductionDialog.show();
	}

	private void updateNoiseReductionDialog(int percent) {
		if (noiseReductionDialog != null && noiseReductionDialog.isShowing()) {
			android.widget.ProgressBar pb = noiseReductionDialog.findViewById(android.R.id.progress);
			if (pb == null) {
				// Find the ProgressBar we set as the view
				View v = noiseReductionDialog.getWindow().getDecorView();
				if (v instanceof android.view.ViewGroup) {
					pb = findProgressBar((android.view.ViewGroup) v);
				}
			}
			if (pb != null) {
				pb.setProgress(percent);
			}
		}
	}

	private android.widget.ProgressBar findProgressBar(android.view.ViewGroup group) {
		for (int i = 0; i < group.getChildCount(); i++) {
			View child = group.getChildAt(i);
			if (child instanceof android.widget.ProgressBar) {
				return (android.widget.ProgressBar) child;
			} else if (child instanceof android.view.ViewGroup) {
				android.widget.ProgressBar result = findProgressBar((android.view.ViewGroup) child);
				if (result != null) return result;
			}
		}
		return null;
	}

	private void dismissNoiseReductionDialog(boolean success) {
		if (noiseReductionDialog != null && noiseReductionDialog.isShowing()) {
			noiseReductionDialog.dismiss();
			noiseReductionDialog = null;
		}
		Toast.makeText(this,
				success ? R.string.noise_reduction_done : R.string.noise_reduction_failed,
				Toast.LENGTH_SHORT).show();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQ_CODE_IMPORT_AUDIO && resultCode == RESULT_OK){
			presenter.importAudioFile(getApplicationContext(), data.getData());
		}
	}

	@Override
	public void keepScreenOn(boolean on) {
		if (on) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
	}

	@Override
	public void showProgress() {
		progressBar.setVisibility(View.VISIBLE);
	}

	@Override
	public void hideProgress() {
		progressBar.setVisibility(View.GONE);
	}

	@Override
	public void showError(String message) {
		Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showError(int resId) {
		Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showMessage(int resId) {
		Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showRecordingStart() {
		txtName.setClickable(false);
		txtName.setFocusable(false);
		txtName.setCompoundDrawables(null, null, null, null);
		txtName.setVisibility(View.VISIBLE);
		txtName.setText(R.string.recording_progress);
		txtZeroTime.setVisibility(View.INVISIBLE);
		txtDuration.setVisibility(View.INVISIBLE);
		btnRecord.setText(R.string.button_stop);
		btnPlay.setEnabled(false);
		btnImport.setEnabled(false);
		btnShare.setEnabled(false);
		btnPlay.setVisibility(View.GONE);
		btnImport.setVisibility(View.GONE);
		btnShare.setVisibility(View.GONE);
		playProgress.setProgress(0);
		playProgress.setEnabled(false);
		txtDuration.setText(R.string.zero_time);
		waveformView.setVisibility(View.GONE);
		recordingWaveformView.setVisibility(View.VISIBLE);
		ivPlaceholder.setVisibility(View.GONE);
	}

	@Override
	public void showRecordingStop() {
		txtName.setClickable(true);
		txtName.setFocusable(true);
//		txtName.setText("");
		txtZeroTime.setVisibility(View.VISIBLE);
		txtDuration.setVisibility(View.VISIBLE);
		txtName.setCompoundDrawablesWithIntrinsicBounds(null, null, getDrawable(R.drawable.ic_pencil_small), null);
		btnRecord.setText(R.string.button_record);
		btnPlay.setEnabled(true);
		btnImport.setEnabled(true);
		btnShare.setEnabled(true);
		btnPlay.setVisibility(View.VISIBLE);
		btnImport.setVisibility(View.VISIBLE);
		btnShare.setVisibility(View.VISIBLE);
		playProgress.setEnabled(true);
		btnRecordingStop.setVisibility(View.GONE);
		btnRecordingStop.setEnabled(false);
		waveformView.setVisibility(View.VISIBLE);
		recordingWaveformView.setVisibility(View.GONE);
		recordingWaveformView.reset();
		txtProgress.setText(TimeUtils.formatTimeIntervalHourMinSec2(0));
		// Sync monitor button with actual state — monitoring may still be running as standalone
		isMonitoringActive = AudioMonitor.getInstance().isMonitoring();
		btnMonitor.setAlpha(isMonitoringActive ? 1.0f : 0.5f);
	}

	@Override
	public void onMonitoringDisabled() {
		runOnUiThread(() -> {
			isMonitoringActive = false;
			btnMonitor.setAlpha(0.5f);
			// Also reset the WavRecorder flag so monitoring doesn't auto-start on next recording
			RecorderContract.Recorder recorder = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
			if (recorder instanceof WavRecorder) {
				((WavRecorder) recorder).setMonitoringEnabled(false);
			}
		});
	}

	@Override
	public void showRecordingPause() {
		txtName.setClickable(false);
		txtName.setFocusable(false);
		txtName.setCompoundDrawables(null, null, null, null);
		txtName.setText(R.string.recording_paused);
		txtName.setVisibility(View.VISIBLE);
		btnPlay.setEnabled(false);
		btnImport.setEnabled(false);
		btnShare.setEnabled(false);
		btnPlay.setVisibility(View.GONE);
		btnImport.setVisibility(View.GONE);
		btnShare.setVisibility(View.GONE);
		btnRecord.setText(R.string.button_resume);
		btnRecordingStop.setVisibility(View.VISIBLE);
		btnRecordingStop.setEnabled(true);
		playProgress.setEnabled(false);
		ivPlaceholder.setVisibility(View.GONE);
		recordingWaveformView.setVisibility(View.VISIBLE);
	}

	@Override
	public void showRecordingResume() {
		txtName.setClickable(false);
		txtName.setFocusable(false);
		txtName.setCompoundDrawables(null, null, null, null);
		txtName.setVisibility(View.VISIBLE);
		txtName.setText(R.string.recording_progress);
		txtZeroTime.setVisibility(View.INVISIBLE);
		txtDuration.setVisibility(View.INVISIBLE);
		btnRecord.setText(R.string.button_stop);
		btnPlay.setEnabled(false);
		btnImport.setEnabled(false);
		btnShare.setEnabled(false);
		btnPlay.setVisibility(View.GONE);
		btnImport.setVisibility(View.GONE);
		btnShare.setVisibility(View.GONE);
		btnRecordingStop.setVisibility(View.GONE);
		btnRecordingStop.setEnabled(false);
		playProgress.setProgress(0);
		playProgress.setEnabled(false);
		txtDuration.setText(R.string.zero_time);
		ivPlaceholder.setVisibility(View.GONE);
	}

	@Override
	public void askRecordingNewName(long id, File file,  boolean showCheckbox) {
		setRecordName(id, file, showCheckbox);
	}

	@Override
	public void onRecordingProgress(long mills, int amp) {
		runOnUiThread(() ->{
			txtProgress.setText(TimeUtils.formatTimeIntervalHourMinSec2(mills));
			recordingWaveformView.addRecordAmp(amp, mills);
		});
	}

	@Override
	public void startWelcomeScreen() {
		startActivity(WelcomeActivity.getStartIntent(getApplicationContext()));
		finish();
	}

	@Override
	public void startRecordingService() {
		try {
			String path = fileRepository.provideRecordFile().getAbsolutePath();
			Intent intent = new Intent(getApplicationContext(), RecordingService.class);
			intent.setAction(RecordingService.ACTION_START_RECORDING_SERVICE);
			intent.putExtra(RecordingService.EXTRAS_KEY_RECORD_PATH, path);
			startService(intent);
		} catch (CantCreateFileException e) {
			showError(ErrorParser.parseException(e));
		}
	}

	@Override
	public void startPlaybackService(final String name) {
		PlaybackService.startServiceForeground(getApplicationContext(), name);
	}

	@Override
	public void showPlayStart(boolean animate) {
		btnRecord.setEnabled(false);
		if (animate) {
			AnimationUtil.viewAnimationX(btnPlay, -space, new Animator.AnimatorListener() {
				@Override public void onAnimationStart(Animator animation) { }
				@Override public void onAnimationEnd(Animator animation) {
					btnStop.setVisibility(View.VISIBLE);
					btnPlay.setImageResource(R.drawable.ic_pause);
				}
				@Override public void onAnimationCancel(Animator animation) { }
				@Override public void onAnimationRepeat(Animator animation) { }
			});
		} else {
			btnPlay.setTranslationX(-space);
			btnStop.setVisibility(View.VISIBLE);
			btnPlay.setImageResource(R.drawable.ic_pause);
		}
	}

	@Override
	public void showPlayPause() {
		btnStop.setVisibility(View.VISIBLE);
		btnPlay.setTranslationX(-space);
		btnPlay.setImageResource(R.drawable.ic_play);
	}

	@Override
	public void showPlayStop() {
		btnPlay.setImageResource(R.drawable.ic_play);
		waveformView.moveToStart();
		btnRecord.setEnabled(true);
		playProgress.setProgress(0);
		txtProgress.setText(TimeUtils.formatTimeIntervalHourMinSec2(0));
		AnimationUtil.viewAnimationX(btnPlay, 0f, new Animator.AnimatorListener() {
			@Override public void onAnimationStart(Animator animation) { }
			@Override public void onAnimationEnd(Animator animation) {
				btnStop.setVisibility(View.GONE);
			}
			@Override public void onAnimationCancel(Animator animation) { }
			@Override public void onAnimationRepeat(Animator animation) { }
		});
	}

	@Override
	public void showWaveForm(int[] waveForm, long duration, long playbackMills) {
		if (waveForm.length > 0) {
			btnPlay.setVisibility(View.VISIBLE);
			txtDuration.setVisibility(View.VISIBLE);
			txtZeroTime.setVisibility(View.VISIBLE);
			ivPlaceholder.setVisibility(View.GONE);
			waveformView.setVisibility(View.VISIBLE);
		} else {
			btnPlay.setVisibility(View.INVISIBLE);
			txtDuration.setVisibility(View.INVISIBLE);
			txtZeroTime.setVisibility(View.INVISIBLE);
			ivPlaceholder.setVisibility(View.VISIBLE);
			waveformView.setVisibility(View.INVISIBLE);
		}
		waveformView.setWaveform(waveForm, duration/1000, playbackMills);
	}

	@Override
	public void waveFormToStart() {
		waveformView.seekPx(0);
	}

	@Override
	public void showDuration(final String duration) {
		txtDuration.setText(duration);
	}

	@Override
	public void showRecordingProgress(String progress) {
		txtProgress.setText(progress);
	}

	@Override
	public void showName(String name) {
		if (name == null || name.isEmpty()) {
			txtName.setVisibility(View.INVISIBLE);
		} else {
			txtName.setVisibility(View.VISIBLE);
		}
		txtName.setText(name);
	}

	@Override
	public void showInformation(String info) {
		runOnUiThread(() -> txtRecordInfo.setText(info));
	}

	@Override
	public void decodeRecord(int id) {
		DecodeService.Companion.startNotification(getApplicationContext(), id);
	}

	@Override
	public void askDeleteRecord(String name) {
		AndroidUtils.showDialogYesNo(
				MainActivity.this,
				R.drawable.ic_delete_forever_dark,
				getString(R.string.warning),
				getString(R.string.delete_record, name),
				v -> presenter.deleteActiveRecord()
		);
	}

	@Override
	public void showRecordInfo(RecordInfo info) {
		startActivity(ActivityInformation.getStartIntent(getApplicationContext(), info));
	}

	@Override
	public void updateRecordingView(IntArrayList data, long durationMills) {
		if (data != null) {
			recordingWaveformView.setRecordingData(data, durationMills);
		}
	}

	@Override
	public void showRecordsLostMessage(List<Record> list) {
		AndroidUtils.showLostRecordsDialog(this, list);
	}

	@Override
	public void shareRecord(Record record) {
		AndroidUtils.shareAudioFile(getApplicationContext(), record.getPath(), record.getName(), record.getFormat());
	}

	@Override
	public void openFile(Record record) {
		AndroidUtils.openAudioFile(getApplicationContext(), record.getPath(), record.getName());
	}

	@Override
	public void downloadRecord(Record record) {
		if (isPublicDir(record.getPath())) {
			if (checkStoragePermissionDownload()) {
				DownloadService.startNotification(getApplicationContext(), record.getPath());
			}
		} else {
			DownloadService.startNotification(getApplicationContext(), record.getPath());
		}
	}

	private boolean isPublicDir(String path) {
		return path.contains(FileUtil.getAppDir().getAbsolutePath());
	}

	@Override
	public void showMigratePublicStorageWarning() {
		AndroidUtils.showDialog(
				this,
				R.drawable.ic_warning_yellow,
				R.string.view_records,
				R.string.later,
				R.string.move_records_needed,
				R.string.move_records_info,
				false,
				v -> {
					startActivity(MoveRecordsActivity.Companion.getStartIntent(getApplicationContext(), false));
				},
				v -> {}
		);
	}

	@Override
	public void showRecordFileNotAvailable(String path) {
		AndroidUtils.showRecordFileNotAvailable(this, path);
	}

	@Override
	public void onPlayProgress(final long mills, int percent) {
		playProgress.setProgress(percent);
		waveformView.setPlayback(mills);
		txtProgress.setText(TimeUtils.formatTimeIntervalHourMinSec2(mills));
	}

	@Override
	public void showImportStart() {
		btnImport.setVisibility(View.INVISIBLE);
		pnlImportProgress.setVisibility(View.VISIBLE);
	}

	@Override
	public void hideImportProgress() {
		pnlImportProgress.setVisibility(View.INVISIBLE);
		btnImport.setVisibility(View.VISIBLE);
	}

	@Override
	public void showOptionsMenu() {
		btnShare.setVisibility(View.VISIBLE);
	}

	@Override
	public void hideOptionsMenu() {
		btnShare.setVisibility(View.INVISIBLE);
	}

	@Override
	public void showRecordProcessing() {
		pnlRecordProcessing.setVisibility(View.VISIBLE);
	}

	@Override
	public void hideRecordProcessing() {
		pnlRecordProcessing.setVisibility(View.INVISIBLE);
	}

	private void showMenu(View v) {
		PopupMenu popup = new PopupMenu(v.getContext(), v);
		popup.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.id.menu_share) {
				presenter.onShareRecordClick();
			} else if (id == R.id.menu_info) {
				presenter.onRecordInfo();
			} else if (id == R.id.menu_rename) {
				presenter.onRenameRecordClick();
			} else if (id == R.id.menu_open_with) {
				presenter.onOpenFileClick();
			} else if (id == R.id.menu_save_as) {
				AndroidUtils.showDialogYesNo(
						MainActivity.this,
						R.drawable.ic_save_alt_dark,
						getString(R.string.save_as),
						getString(R.string.record_will_be_copied_into_downloads),
						view -> presenter.onSaveAsClick()
				);
			} else if (id == R.id.menu_delete) {
				presenter.onDeleteClick();
			}
			return false;
		});
		MenuInflater inflater = popup.getMenuInflater();
		inflater.inflate(R.menu.menu_more, popup.getMenu());
		AndroidUtils.insertMenuItemIcons(v.getContext(), popup);
		popup.show();
	}

	public void setRecordName(final long recordId, File file, boolean showCheckbox) {
		final RecordInfo info = AudioDecoder.readRecordInfo(file);
		AndroidUtils.showRenameDialog(this, info.getName(), showCheckbox, newName -> {
			if (!info.getName().equalsIgnoreCase(newName)) {
				presenter.renameRecord(recordId, newName, info.getFormat());
			}
		}, v -> {}, (buttonView, isChecked) -> presenter.setAskToRename(!isChecked));
	}

	private boolean checkStoragePermissionDownload() {
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
					&& checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(
						new String[]{
								Manifest.permission.WRITE_EXTERNAL_STORAGE,
								Manifest.permission.READ_EXTERNAL_STORAGE},
						REQ_CODE_READ_EXTERNAL_STORAGE_DOWNLOAD);
				return false;
			}
		}
		return true;
	}

	private boolean checkStoragePermissionImport() {
		if (presenter.isStorePublic()) {
			if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
						&& checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
					requestPermissions(
							new String[]{
									Manifest.permission.WRITE_EXTERNAL_STORAGE,
									Manifest.permission.READ_EXTERNAL_STORAGE},
							REQ_CODE_READ_EXTERNAL_STORAGE_IMPORT);
					return false;
				}
			}
		}
		return true;
	}

	private boolean checkStoragePermissionPlayback() {
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
					&& checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(
						new String[]{
								Manifest.permission.WRITE_EXTERNAL_STORAGE,
								Manifest.permission.READ_EXTERNAL_STORAGE},
						REQ_CODE_READ_EXTERNAL_STORAGE_PLAYBACK);
				return false;
			}
		}
		return true;
	}

	private boolean checkRecordPermission2() {
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE_RECORD_AUDIO);
				return false;
			}
		}
		return true;
	}

	private boolean checkNotificationPermission() {
		if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_CODE_POST_NOTIFICATIONS);
				return false;
			}
		}
		return true;
	}

	private boolean checkStoragePermission2() {
		if (presenter.isStorePublic()) {
			if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
					AndroidUtils.showDialog(this, R.string.warning, R.string.need_write_permission,
							v -> requestPermissions(
									new String[]{
											Manifest.permission.WRITE_EXTERNAL_STORAGE,
											Manifest.permission.READ_EXTERNAL_STORAGE},
									REQ_CODE_WRITE_EXTERNAL_STORAGE), null
//							new View.OnClickListener() {
//								@Override
//								public void onClick(View v) {
//									presenter.setStoragePrivate(getApplicationContext());
//									presenter.startRecording();
//								}
//							}
					);
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQ_CODE_REC_AUDIO_AND_WRITE_EXTERNAL && grantResults.length > 0
					&& grantResults[0] == PackageManager.PERMISSION_GRANTED
					&& grantResults[1] == PackageManager.PERMISSION_GRANTED
					&& grantResults[2] == PackageManager.PERMISSION_GRANTED) {
			startRecordingService();
		} else if (requestCode == REQ_CODE_RECORD_AUDIO && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			if (checkStoragePermission2()) {
				startRecordingService();
			}
		} else if (requestCode == REQ_CODE_WRITE_EXTERNAL_STORAGE && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED
				&& grantResults[1] == PackageManager.PERMISSION_GRANTED) {
			if (checkRecordPermission2()) {
				startRecordingService();
			}
		} else if (requestCode == REQ_CODE_READ_EXTERNAL_STORAGE_IMPORT && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED
				&& grantResults[1] == PackageManager.PERMISSION_GRANTED) {
			startFileSelector();
		} else if (requestCode == REQ_CODE_READ_EXTERNAL_STORAGE_DOWNLOAD && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED
				&& grantResults[1] == PackageManager.PERMISSION_GRANTED) {
			presenter.onSaveAsClick();
		} else if (requestCode == REQ_CODE_READ_EXTERNAL_STORAGE_PLAYBACK && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED
				&& grantResults[1] == PackageManager.PERMISSION_GRANTED) {
			presenter.startPlayback();
		} else if (requestCode == REQ_CODE_WRITE_EXTERNAL_STORAGE && grantResults.length > 0
				&& (grantResults[0] == PackageManager.PERMISSION_DENIED
				|| grantResults[1] == PackageManager.PERMISSION_DENIED)) {
			presenter.setStoragePrivate(getApplicationContext());
			startRecordingService();
		} else if (requestCode == REQ_CODE_POST_NOTIFICATIONS && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			//Post notifications permission is granted do nothing
		}
	}
}
