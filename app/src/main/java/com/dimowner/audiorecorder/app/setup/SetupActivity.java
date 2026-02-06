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

package com.dimowner.audiorecorder.app.setup;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.dimowner.audiorecorder.ARApplication;
import com.dimowner.audiorecorder.AppConstants;
import com.dimowner.audiorecorder.ColorMap;
import com.dimowner.audiorecorder.R;
import com.dimowner.audiorecorder.app.main.MainActivity;
import com.dimowner.audiorecorder.app.settings.AppSpinnerAdapter;
import com.dimowner.audiorecorder.app.settings.SettingsMapper;
import com.dimowner.audiorecorder.app.widget.SettingView;
import com.dimowner.audiorecorder.audio.AudioDeviceManager;
import com.dimowner.audiorecorder.util.AndroidUtils;
import com.dimowner.audiorecorder.util.FileUtil;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.widget.ArrayAdapter;

import java.util.ArrayList;
import java.util.List;

public class SetupActivity extends Activity implements SetupContract.View, View.OnClickListener {

	private static final int REQ_CODE_RECORD_AUDIO = 501;

	private Spinner nameFormatSelector;
	private Spinner audioSourceSelector;

	private SettingView outputFormatSetting;
	private SettingView bitDepthSetting;
	private SettingView sampleRateSetting;
	private SettingView bitrateSetting;
	private SettingView channelsSetting;

	private SetupContract.UserActionsListener presenter;
	private ColorMap colorMap;
	private ColorMap.OnThemeColorChangeListener onThemeColorChangeListener;

	private List<AudioDeviceInfo> availableAudioDevices = new ArrayList<>();
	private AudioDeviceManager audioDeviceManager;
	private boolean ignoreAudioSourceSelection = false;

	public static Intent getStartIntent(Context context) {
		Intent intent = new Intent(context, SetupActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return intent;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		colorMap = ARApplication.getInjector().provideColorMap(getApplicationContext());
		setTheme(colorMap.getAppThemeResource());
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_setup);

//		getWindow().setFlags(
//				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
//				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
//
//		LinearLayout toolbar = findViewById(R.id.toolbar);
//		toolbar.setPadding(0, AndroidUtils.getStatusBarHeight(getApplicationContext()), 0, 0);

		Button btnApply = findViewById(R.id.btn_apply);
		Button btnReset = findViewById(R.id.btn_reset);
		btnApply.setOnClickListener(this);
		btnReset.setOnClickListener(this);

//		Space space = findViewById(R.id.space);
//		ViewGroup.LayoutParams params = space.getLayoutParams();
//		params.height = AndroidUtils.getNavigationBarHeight(getApplicationContext());
//		space.setLayoutParams(params);

		outputFormatSetting = findViewById(R.id.setting_output_format);
		final String[] outputFormats = new String[] { "WAV", "MP3 320kbps", "FLAC" };
		final String[] outputFormatKeys = new String[] {
				AppConstants.OUTPUT_FORMAT_WAV,
				AppConstants.OUTPUT_FORMAT_MP3,
				AppConstants.OUTPUT_FORMAT_FLAC
		};
		outputFormatSetting.setData(outputFormats, outputFormatKeys);
		outputFormatSetting.setOnChipCheckListener((key, name, checked) -> presenter.setSettingOutputFormat(key));
		outputFormatSetting.setTitle(R.string.output_format);
		outputFormatSetting.setOnInfoClickListener(v -> AndroidUtils.showInfoDialog(SetupActivity.this, R.string.info_output_format));

		bitDepthSetting = findViewById(R.id.setting_bit_depth);
		final String[] bitDepths = new String[] { "16-bit", "24-bit" };
		final String[] bitDepthKeys = new String[] {
				SettingsMapper.BIT_DEPTH_16_KEY,
				SettingsMapper.BIT_DEPTH_24_KEY
		};
		bitDepthSetting.setData(bitDepths, bitDepthKeys);
		bitDepthSetting.setOnChipCheckListener((key, name, checked) -> presenter.setSettingBitDepth(SettingsMapper.keyToBitDepth(key)));
		bitDepthSetting.setTitle(R.string.bit_depth);
		bitDepthSetting.setOnInfoClickListener(v -> AndroidUtils.showInfoDialog(SetupActivity.this, R.string.info_bit_depth));

		sampleRateSetting = findViewById(R.id.setting_frequency);
		final String[] sampleRates = getResources().getStringArray(R.array.sample_rates2);
		final String[] sampleRatesKeys = new String[] {
				SettingsMapper.SAMPLE_RATE_8000,
				SettingsMapper.SAMPLE_RATE_16000,
				SettingsMapper.SAMPLE_RATE_22050,
				SettingsMapper.SAMPLE_RATE_32000,
				SettingsMapper.SAMPLE_RATE_44100,
				SettingsMapper.SAMPLE_RATE_48000,
		};
		sampleRateSetting.setData(sampleRates, sampleRatesKeys);
		sampleRateSetting.setOnChipCheckListener((key, name, checked) -> presenter.setSettingSampleRate(SettingsMapper.keyToSampleRate(key)));
		sampleRateSetting.setTitle(R.string.sample_rate);
		sampleRateSetting.setOnInfoClickListener(v -> AndroidUtils.showInfoDialog(SetupActivity.this, R.string.info_frequency));

		bitrateSetting = findViewById(R.id.setting_bitrate);
		final String[] rates = getResources().getStringArray(R.array.bit_rates2);
		final String[] rateKeys = new String[] {
//				SettingsMapper.BITRATE_24000,
				SettingsMapper.BITRATE_48000,
				SettingsMapper.BITRATE_96000,
				SettingsMapper.BITRATE_128000,
				SettingsMapper.BITRATE_192000,
				SettingsMapper.BITRATE_256000,
				SettingsMapper.BITRATE_288000,
		};
		bitrateSetting.setData(rates, rateKeys);
		bitrateSetting.setOnChipCheckListener((key, name, checked) -> presenter.setSettingRecordingBitrate(SettingsMapper.keyToBitrate(key)));
		bitrateSetting.setTitle(R.string.bitrate);
		bitrateSetting.setOnInfoClickListener(v -> AndroidUtils.showInfoDialog(SetupActivity.this, R.string.info_bitrate));

		channelsSetting = findViewById(R.id.setting_channels);
		final String[] recChannels = getResources().getStringArray(R.array.channels);
		final String[] recChannelsKeys = new String[] {
				SettingsMapper.CHANNEL_COUNT_STEREO,
				SettingsMapper.CHANNEL_COUNT_MONO
		};
		channelsSetting.setData(recChannels, recChannelsKeys);
		channelsSetting.setOnChipCheckListener((key, name, checked) -> presenter.setSettingChannelCount(SettingsMapper.keyToChannelCount(key)));
		channelsSetting.setTitle(R.string.channels);
		channelsSetting.setOnInfoClickListener(v -> AndroidUtils.showInfoDialog(SetupActivity.this, R.string.info_channels));

		presenter = ARApplication.getInjector().provideSetupPresenter(getApplicationContext());
		audioDeviceManager = ARApplication.getInjector().provideAudioDeviceManager(getApplicationContext());

		initThemeColorSelector();
		initNameFormatSelector();
		initAudioSourceSelector();

		// Request recording permission during wizard setup
		requestRecordingPermission();
	}

	private void initThemeColorSelector() {
		Spinner themeColor = findViewById(R.id.themeColor);
		List<AppSpinnerAdapter.ThemeItem> items = new ArrayList<>();
		String[] values = getResources().getStringArray(R.array.theme_colors2);
		int[] colorRes = colorMap.getColorResources();
		for (int i = 0; i < values.length; i++) {
			items.add(new AppSpinnerAdapter.ThemeItem(values[i], getApplicationContext().getResources().getColor(colorRes[i])));
		}
		AppSpinnerAdapter adapter = new AppSpinnerAdapter(SetupActivity.this,
				R.layout.list_item_spinner, R.id.txtItem, items, R.drawable.ic_color_lens);
		themeColor.setAdapter(adapter);

		onThemeColorChangeListener = colorKey -> {
			setTheme(colorMap.getAppThemeResource());
			recreate();
		};
		colorMap.addOnThemeColorChangeListener(onThemeColorChangeListener);

		int selected = SettingsMapper.colorKeyToPosition(colorMap.getSelected());
		if (selected != themeColor.getSelectedItemPosition()) {
			themeColor.setSelection(selected);
		}
		themeColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				String colorKey = SettingsMapper.positionToColorKey(position);
				colorMap.updateColorMap(colorKey);
				presenter.setSettingThemeColor(colorKey);
			}
			@Override public void onNothingSelected(AdapterView<?> parent) { }
		});
	}

	private void initNameFormatSelector() {
		nameFormatSelector = findViewById(R.id.name_format);
		List<AppSpinnerAdapter.ThemeItem> items = new ArrayList<>();
		String[] values = new String[5];
		values[0] = getResources().getString(R.string.naming) + " " + FileUtil.generateRecordNameCounted(1) + ".wav";
		values[1] = getResources().getString(R.string.naming) + " " + FileUtil.generateRecordNameDateVariant() + ".wav";
		values[2] = getResources().getString(R.string.naming) + " " + FileUtil.generateRecordNameDateUS() + ".wav";
		values[3] = getResources().getString(R.string.naming) + " " + FileUtil.generateRecordNameDateISO8601() + ".wav";
		values[4] = getResources().getString(R.string.naming) + " " + FileUtil.generateRecordNameMills() + ".wav";
		for (int i = 0; i < values.length; i++) {
			items.add(new AppSpinnerAdapter.ThemeItem(values[i],
					getApplicationContext().getResources().getColor(colorMap.getPrimaryColorRes())));
		}
		AppSpinnerAdapter adapter = new AppSpinnerAdapter(SetupActivity.this,
				R.layout.list_item_spinner, R.id.txtItem, items, R.drawable.ic_title);
		nameFormatSelector.setAdapter(adapter);

		nameFormatSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				presenter.setSettingNamingFormat(SettingsMapper.positionToNamingFormat(position));
			}
			@Override public void onNothingSelected(AdapterView<?> parent) { }
		});
	}

	private void initAudioSourceSelector() {
		audioSourceSelector = findViewById(R.id.setup_audio_source_selector);
		if (audioSourceSelector == null) return;

		audioSourceSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				if (ignoreAudioSourceSelection) return;
				int deviceId;
				if (position == 0) {
					deviceId = AppConstants.AUDIO_SOURCE_DEFAULT_MIC;
				} else {
					deviceId = availableAudioDevices.get(position - 1).getId();
				}
				presenter.setSettingAudioSource(deviceId);
			}
			@Override public void onNothingSelected(AdapterView<?> parent) { }
		});
		loadAudioDevices();
	}

	private void loadAudioDevices() {
		if (audioSourceSelector == null || audioDeviceManager == null) return;

		availableAudioDevices = audioDeviceManager.getAvailableInputDevices();
		List<String> deviceNames = new ArrayList<>();
		deviceNames.add(getString(R.string.audio_source_default_mic));

		for (AudioDeviceInfo device : availableAudioDevices) {
			deviceNames.add(AudioDeviceManager.getDeviceDisplayName(device) +
					" (" + AudioDeviceManager.getDeviceTypeString(device) + ")");
		}

		ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, deviceNames);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

		ignoreAudioSourceSelection = true;
		audioSourceSelector.setAdapter(adapter);
		audioSourceSelector.setSelection(0);
		ignoreAudioSourceSelection = false;
	}

	private void requestRecordingPermission() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE_RECORD_AUDIO);
			}
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		if (requestCode == REQ_CODE_RECORD_AUDIO) {
			if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
				Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show();
			}
		}
	}

	@Override
	public void onClick(View v) {
		int id = v.getId();
		if (id == R.id.btn_apply) {
			presenter.executeFirstRun();
			startActivity(MainActivity.getStartIntent(getApplicationContext()));
			finish();
		} else if (id == R.id.btn_reset) {
			presenter.resetSettings();
			presenter.loadSettings();
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		presenter.bindView(this);
		presenter.loadSettings();
	}

	@Override
	protected void onStop() {
		super.onStop();
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
	public void onBackPressed() {
		super.onBackPressed();
		ARApplication.getInjector().releaseSetupPresenter();
	}

	@Override
	public void showRecordingBitrate(int bitrate) {
		bitrateSetting.setSelected(SettingsMapper.bitrateToKey(bitrate));
	}

	@Override
	public void showSampleRate(int rate) {
		sampleRateSetting.setSelected(SettingsMapper.sampleRateToKey(rate));
	}

	@Override
	public void showChannelCount(int count) {
		channelsSetting.setSelected(SettingsMapper.channelCountToKey(count));
	}

	@Override
	public void showNamingFormat(String namingKey) {
		nameFormatSelector.setSelection(SettingsMapper.namingFormatToPosition(namingKey));
	}

	@Override
	public void showRecordingFormat(String formatKey) {
		// Recording format is always WAV now, no UI needed
	}

	@Override
	public void showOutputFormat(String outputFormatKey) {
		outputFormatSetting.setSelected(outputFormatKey);
	}

	@Override
	public void showBitDepth(int bitDepth) {
		bitDepthSetting.setSelected(SettingsMapper.bitDepthToKey(bitDepth));
	}

	@Override
	public void showBitrateSelector() {
		bitrateSetting.setVisibility(View.VISIBLE);
	}

	@Override
	public void hideBitrateSelector() {
		bitrateSetting.setVisibility(View.GONE);
	}

	@Override
	public void showInformation(int infoResId) {
	}

	@Override
	public void showSizePerMin(String size) {
	}

	@Override
	public void updateRecordingInfo(String format) {
		// WAV is the only recording format now; all sample rates and channels are available
	}

	@Override
	public void showProgress() {
	}

	@Override
	public void hideProgress() {
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
}
