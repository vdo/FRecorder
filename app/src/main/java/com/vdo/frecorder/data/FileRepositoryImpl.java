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

package com.vdo.frecorder.data;

import android.content.Context;

import java.io.File;
import java.io.FileNotFoundException;

import timber.log.Timber;

import com.vdo.frecorder.ARApplication;
import com.vdo.frecorder.AppConstants;
import com.vdo.frecorder.exception.CantCreateFileException;
import com.vdo.frecorder.util.FileUtil;

public class FileRepositoryImpl implements FileRepository {

	private File recordDirectory;
	private final Prefs prefs;

	private volatile static FileRepositoryImpl instance;

	private FileRepositoryImpl(Context context, Prefs prefs) {
		updateRecordingDir(context, prefs);
		this.prefs = prefs;
	}

	public static FileRepositoryImpl getInstance(Context context, Prefs prefs) {
		if (instance == null) {
			synchronized (FileRepositoryImpl.class) {
				if (instance == null) {
					instance = new FileRepositoryImpl(context, prefs);
				}
			}
		}
		return instance;
	}

	@Override
	public File provideRecordFile() throws CantCreateFileException {
		prefs.incrementRecordCounter();
		File recordFile;
		String recordName;
		switch (prefs.getSettingNamingFormat()) {
			default:
			case AppConstants.NAME_FORMAT_RECORD:
				recordName = FileUtil.generateRecordNameCounted(prefs.getRecordCounter());
				break;
			case AppConstants.NAME_FORMAT_DATE:
				recordName = FileUtil.generateRecordNameDateVariant();
				break;
			case AppConstants.NAME_FORMAT_DATE_US:
				recordName = FileUtil.generateRecordNameDateUS();
				break;
			case AppConstants.NAME_FORMAT_DATE_ISO8601:
				recordName = FileUtil.generateRecordNameDateISO8601();
				break;
			case AppConstants.NAME_FORMAT_TIMESTAMP:
				recordName = FileUtil.generateRecordNameMills();
				break;
		}
		String outputFormat = prefs.getSettingOutputFormat();
		String extension;
		switch (outputFormat) {
			case AppConstants.OUTPUT_FORMAT_MP3:
				extension = AppConstants.FORMAT_MP3;
				break;
			case AppConstants.OUTPUT_FORMAT_FLAC:
				extension = AppConstants.FORMAT_FLAC;
				break;
			default:
				extension = AppConstants.FORMAT_WAV;
				break;
		}
		recordFile = FileUtil.createFile(recordDirectory, FileUtil.addExtension(recordName, extension));

		if (recordFile != null) {
			return recordFile;
		}
		throw new CantCreateFileException();
	}

	@Override
	public File provideRecordFile(String name) throws CantCreateFileException {
		File recordFile = FileUtil.createFile(recordDirectory, name);
		if (recordFile != null) {
			return recordFile;
		}
		throw new CantCreateFileException();
	}

	@Override
	public File[] getPrivateDirFiles(Context context) {
		try {
			return FileUtil.getPrivateRecordsDir(context).listFiles();
		} catch (FileNotFoundException e) {
			Timber.e(e);
			return new File[] {};
		}
	}

	@Override
	public File[] getPublicDirFiles() {
		File dir = FileUtil.getAppDir();
		if (dir != null) {
			return dir.listFiles();
		} else {
			return new File[] {};
		}
	}

	@Override
	public File getPublicDir() {
		return FileUtil.getAppDir();
	}

	@Override
	public File getPrivateDir(Context context) {
		try {
			return FileUtil.getPrivateRecordsDir(context);
		} catch (FileNotFoundException e) {
			Timber.e(e);
			return null;
		}
	}

//	@Override
//	public File getRecordFileByName(String name, String extension) {
//		File recordFile = new File(recordDirectory.getAbsolutePath() + File.separator + FileUtil.generateRecordNameCounted(prefs.getRecordCounter(), extension));
//		if (recordFile.exists() && recordFile.isFile()) {
//			return recordFile;
//		}
//		Timber.e("File %s was not found", recordFile.getAbsolutePath());
//		return null;
//	}

	@Override
	public File getRecordingDir() {
		return recordDirectory;
	}

	@Override
	public boolean deleteRecordFile(String path) {
		if (path != null) {
			return FileUtil.deleteFile(new File(path));
		}
		return false;
	}

	@Override
	public String markAsTrashRecord(String path) {
		String trashLocation = FileUtil.addExtension(path, AppConstants.TRASH_MARK_EXTENSION);
		if (FileUtil.renameFile(new File(path), new File(trashLocation))) {
			return trashLocation;
		}
		return null;
	}

	@Override
	public String unmarkTrashRecord(String path) {
		String restoredFile = FileUtil.removeFileExtension(path);
		if (FileUtil.renameFile(new File(path), new File(restoredFile))) {
			return restoredFile;
		}
		return null;
	}

	@Override
	public boolean deleteAllRecords() {
//		return FileUtil.deleteFile(recordDirectory);
		return false;
	}

	@Override
	public boolean renameFile(String path, String newName, String extension) {
		return FileUtil.renameFile(new File(path), newName, extension);
	}

	public void updateRecordingDir(Context context, Prefs prefs) {
		if (prefs.isStoreDirPublic()) {
			recordDirectory = FileUtil.getAppDir();
			if (recordDirectory == null) {
				//Try to init private dir
				try {
					recordDirectory = FileUtil.getPrivateRecordsDir(context);
				} catch (FileNotFoundException e) {
					Timber.e(e);
					//If nothing helped then hardcode recording dir
					recordDirectory = new File("/data/data/" + ARApplication.appPackage() + "/files");
				}
			}
		} else {
			try {
				recordDirectory = FileUtil.getPrivateRecordsDir(context);
			} catch (FileNotFoundException e) {
				Timber.e(e);
				//Try to init public dir
				//App dir now is not available.
				//If nothing helped then hardcode recording dir
				recordDirectory = new File("/data/data/" + ARApplication.appPackage() + "/files");
			}
		}
	}

	@Override
	public boolean hasAvailableSpace(Context context) throws IllegalArgumentException {
		long space;
		if (prefs.isStoreDirPublic()) {
//			TODO: deprecated fix this
			space = FileUtil.getAvailableExternalMemorySize();
		} else {
			space = FileUtil.getAvailableInternalMemorySize(context);
		}

		final long time = spaceToTimeSecs(space, prefs.getSettingRecordingFormat(),
				prefs.getSettingSampleRate(), prefs.getSettingBitrate(), prefs.getSettingChannelCount());
		return time > AppConstants.MIN_REMAIN_RECORDING_TIME;
	}

	private long spaceToTimeSecs(long spaceBytes, String recordingFormat, int sampleRate, int bitrate, int channels) {
		// Recording is always WAV internally
		return 1000 * (spaceBytes/(sampleRate * channels * 2));
	}
}
