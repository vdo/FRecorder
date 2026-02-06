package com.dimowner.audiorecorder.app

import com.dimowner.audiorecorder.AppConstants
import com.dimowner.audiorecorder.app.settings.SettingsMapper

fun formatRecordInformation(settingsMapper: SettingsMapper, format: String, sampleRate: Int, size: Long, bitrate: Int = 0): String {
	var info = settingsMapper.formatSize(size).toString() + AppConstants.SEPARATOR +
			settingsMapper.convertFormatsToString(format) + AppConstants.SEPARATOR +
			settingsMapper.convertSampleRateToString(sampleRate)
	if (AppConstants.FORMAT_MP3 == format && bitrate > 0) {
		info += AppConstants.SEPARATOR + settingsMapper.formatBitrate(bitrate / 1000)
	}
	return info
}
