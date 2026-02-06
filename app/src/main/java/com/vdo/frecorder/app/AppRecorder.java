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

package com.vdo.frecorder.app;

import android.media.AudioDeviceInfo;

import com.vdo.frecorder.IntArrayList;
import com.vdo.frecorder.audio.recorder.RecorderContract;

import java.io.File;

public interface AppRecorder {

	void addRecordingCallback(AppRecorderCallback recorderCallback);
	void removeRecordingCallback(AppRecorderCallback recorderCallback);
	void setRecorder(RecorderContract.Recorder recorder);
	void startRecording(String filePath, int channelCount, int sampleRate, int bitrate);
	void startRecording(String filePath, int channelCount, int sampleRate, int bitrate, AudioDeviceInfo audioDevice);
	void startRecording(String filePath, int channelCount, int sampleRate, int bitrate, AudioDeviceInfo audioDevice, int gainBoostLevel);
	void pauseRecording();
	void resumeRecording();
	void stopRecording();
	IntArrayList getRecordingData();
	long getRecordingDuration();
	boolean isRecording();
	boolean isPaused();
	File getRecordFile();
	void release();
}
