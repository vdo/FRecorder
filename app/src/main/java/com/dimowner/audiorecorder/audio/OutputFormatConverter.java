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

package com.dimowner.audiorecorder.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.dimowner.audiorecorder.AppConstants;
import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import timber.log.Timber;

/**
 * Converts WAV files to other output formats (MP3, FLAC).
 * Recording is always done in WAV internally; this converter runs after recording stops.
 */
public class OutputFormatConverter {

	public interface ConversionCallback {
		void onProgress(int percent);
		void onComplete(File outputFile);
		void onError(String message);
	}

	/**
	 * Convert a WAV file to the specified output format.
	 * @param wavFile The source WAV file
	 * @param outputFormat One of AppConstants.OUTPUT_FORMAT_WAV, OUTPUT_FORMAT_MP3, OUTPUT_FORMAT_FLAC
	 * @param bitDepth Bit depth for WAV output (16, 24). Ignored for MP3/FLAC.
	 * @param callback Progress and completion callback
	 * @return The output file (may be the same as input for WAV with matching bit depth)
	 */
	public static void convert(File wavFile, String outputFormat, int bitDepth, ConversionCallback callback) {
		if (wavFile == null || !wavFile.exists()) {
			if (callback != null) callback.onError("Source WAV file not found");
			return;
		}

		switch (outputFormat) {
			case AppConstants.OUTPUT_FORMAT_MP3:
				convertToMp3(wavFile, callback);
				break;
			case AppConstants.OUTPUT_FORMAT_FLAC:
				convertToFlac(wavFile, callback);
				break;
			case AppConstants.OUTPUT_FORMAT_WAV:
			default:
				handleWavBitDepth(wavFile, bitDepth, callback);
				break;
		}
	}

	private static void handleWavBitDepth(File wavFile, int bitDepth, ConversionCallback callback) {
		// For 16-bit (default), no conversion needed - the WAV is already 16-bit PCM
		if (bitDepth == AppConstants.BIT_DEPTH_16) {
			if (callback != null) callback.onComplete(wavFile);
			return;
		}

		// For 24-bit, convert the PCM data
		try {
			WavHeader header = readWavHeader(wavFile);
			if (header == null) {
				if (callback != null) callback.onError("Failed to read WAV header");
				return;
			}

			int bytesPerSampleIn = header.bitsPerSample / 8;
			int bytesPerSampleOut = bitDepth / 8;
			int numSamples = (int) (header.dataSize / bytesPerSampleIn);

			File tempFile = new File(wavFile.getParent(), wavFile.getName() + ".tmp");
			FileInputStream fis = new FileInputStream(wavFile);
			FileOutputStream fos = new FileOutputStream(tempFile);

			// Skip original header
			fis.skip(44);

			// Write placeholder header
			byte[] newHeader = new byte[44];
			fos.write(newHeader);

			byte[] inBuffer = new byte[bytesPerSampleIn * header.channels * 1024];
			byte[] outBuffer = new byte[bytesPerSampleOut * header.channels * 1024];
			int samplesProcessed = 0;

			int bytesRead;
			while ((bytesRead = fis.read(inBuffer)) > 0) {
				int samplesInChunk = bytesRead / bytesPerSampleIn;
				for (int i = 0; i < samplesInChunk; i++) {
					// Read 16-bit sample
					short sample = (short) ((inBuffer[i * 2 + 1] << 8) | (inBuffer[i * 2] & 0xFF));

					// Convert 16-bit to 24-bit (shift left by 8)
					int sample24 = sample << 8;
					outBuffer[i * 3] = (byte) (sample24 & 0xFF);
					outBuffer[i * 3 + 1] = (byte) ((sample24 >> 8) & 0xFF);
					outBuffer[i * 3 + 2] = (byte) ((sample24 >> 16) & 0xFF);
				}
				fos.write(outBuffer, 0, samplesInChunk * bytesPerSampleOut);
				samplesProcessed += samplesInChunk;
				if (callback != null && numSamples > 0) {
					callback.onProgress((int) (100L * samplesProcessed / numSamples));
				}
			}

			fis.close();
			fos.flush();
			fos.close();

			// Write proper header
			long dataSize = tempFile.length() - 44;
			writeWavHeader(tempFile, header.sampleRate, header.channels, bitDepth, dataSize);

			// Replace original file
			if (wavFile.delete() && tempFile.renameTo(wavFile)) {
				if (callback != null) callback.onComplete(wavFile);
			} else {
				if (callback != null) callback.onError("Failed to replace WAV file");
			}
		} catch (IOException e) {
			Timber.e(e, "WAV bit depth conversion failed");
			if (callback != null) callback.onError("WAV conversion failed: " + e.getMessage());
		}
	}

	private static void convertToMp3(File wavFile, ConversionCallback callback) {
		try {
			WavHeader header = readWavHeader(wavFile);
			if (header == null) {
				if (callback != null) callback.onError("Failed to read WAV header");
				return;
			}

			String mp3Path = wavFile.getAbsolutePath().replaceAll("\\.wav$", ".mp3");
			File mp3File = new File(mp3Path);

			AndroidLame lame = new LameBuilder()
					.setInSampleRate(header.sampleRate)
					.setOutChannels(header.channels)
					.setOutBitrate(320)
					.setOutSampleRate(header.sampleRate)
					.setQuality(2) // High quality
					.build();

			FileInputStream fis = new FileInputStream(wavFile);
			FileOutputStream fos = new FileOutputStream(mp3File);

			// Skip WAV header
			fis.skip(44);

			int bufferSize = 1024 * header.channels;
			byte[] wavBuffer = new byte[bufferSize * 2]; // 16-bit = 2 bytes per sample
			byte[] mp3Buffer = new byte[(int) (7200 + bufferSize * 1.25)];

			long totalBytes = header.dataSize;
			long processedBytes = 0;

			int bytesRead;
			while ((bytesRead = fis.read(wavBuffer)) > 0) {
				int samplesRead = bytesRead / 2; // 16-bit samples

				// Convert bytes to short array
				short[] pcm = new short[samplesRead];
				ByteBuffer.wrap(wavBuffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);

				int encodedBytes;
				if (header.channels == 1) {
					encodedBytes = lame.encode(pcm, pcm, samplesRead, mp3Buffer);
				} else {
					// For stereo, samples are interleaved
					int framesRead = samplesRead / 2;
					short[] leftChannel = new short[framesRead];
					short[] rightChannel = new short[framesRead];
					for (int i = 0; i < framesRead; i++) {
						leftChannel[i] = pcm[i * 2];
						rightChannel[i] = pcm[i * 2 + 1];
					}
					encodedBytes = lame.encode(leftChannel, rightChannel, framesRead, mp3Buffer);
				}

				if (encodedBytes > 0) {
					fos.write(mp3Buffer, 0, encodedBytes);
				}

				processedBytes += bytesRead;
				if (callback != null && totalBytes > 0) {
					callback.onProgress((int) (100L * processedBytes / totalBytes));
				}
			}

			// Flush remaining MP3 data
			int flushBytes = lame.flush(mp3Buffer);
			if (flushBytes > 0) {
				fos.write(mp3Buffer, 0, flushBytes);
			}

			fis.close();
			fos.flush();
			fos.close();
			lame.close();

			// Delete original WAV file
			wavFile.delete();

			if (callback != null) callback.onComplete(mp3File);
		} catch (Exception e) {
			Timber.e(e, "MP3 conversion failed");
			if (callback != null) callback.onError("MP3 conversion failed: " + e.getMessage());
		}
	}

	private static void convertToFlac(File wavFile, ConversionCallback callback) {
		try {
			WavHeader header = readWavHeader(wavFile);
			if (header == null) {
				if (callback != null) callback.onError("Failed to read WAV header");
				return;
			}

			String flacPath = wavFile.getAbsolutePath().replaceAll("\\.wav$", ".flac");
			File flacFile = new File(flacPath);

			MediaFormat format = MediaFormat.createAudioFormat(
					MediaFormat.MIMETYPE_AUDIO_FLAC,
					header.sampleRate,
					header.channels);
			format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5);
			format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);

			MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC);
			codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

			MediaMuxer muxer = new MediaMuxer(flacFile.getAbsolutePath(),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM);

			codec.start();

			FileInputStream fis = new FileInputStream(wavFile);
			fis.skip(44); // Skip WAV header

			int bufferSize = 4096 * header.channels;
			byte[] readBuffer = new byte[bufferSize];
			boolean inputDone = false;
			boolean outputDone = false;
			int trackIndex = -1;
			boolean muxerStarted = false;

			long totalBytes = header.dataSize;
			long processedBytes = 0;
			long presentationTimeUs = 0;

			while (!outputDone) {
				if (!inputDone) {
					int inputBufferIndex = codec.dequeueInputBuffer(10000);
					if (inputBufferIndex >= 0) {
						ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
						int bytesRead = fis.read(readBuffer);
						if (bytesRead <= 0) {
							codec.queueInputBuffer(inputBufferIndex, 0, 0, 0,
									MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inputDone = true;
						} else {
							inputBuffer.clear();
							inputBuffer.put(readBuffer, 0, bytesRead);
							int samplesInBuffer = bytesRead / (2 * header.channels);
							codec.queueInputBuffer(inputBufferIndex, 0, bytesRead,
									presentationTimeUs, 0);
							presentationTimeUs += (1000000L * samplesInBuffer) / header.sampleRate;
							processedBytes += bytesRead;
							if (callback != null && totalBytes > 0) {
								callback.onProgress((int) (100L * processedBytes / totalBytes));
							}
						}
					}
				}

				MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
				int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
				if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					if (!muxerStarted) {
						trackIndex = muxer.addTrack(codec.getOutputFormat());
						muxer.start();
						muxerStarted = true;
					}
				} else if (outputBufferIndex >= 0) {
					ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferIndex);
					if (muxerStarted && bufferInfo.size > 0) {
						muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
					}
					codec.releaseOutputBuffer(outputBufferIndex, false);
					if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						outputDone = true;
					}
				}
			}

			fis.close();
			codec.stop();
			codec.release();
			if (muxerStarted) {
				muxer.stop();
				muxer.release();
			}

			// Delete original WAV file
			wavFile.delete();

			if (callback != null) callback.onComplete(flacFile);
		} catch (Exception e) {
			Timber.e(e, "FLAC conversion failed");
			if (callback != null) callback.onError("FLAC conversion failed: " + e.getMessage());
		}
	}

	private static WavHeader readWavHeader(File wavFile) {
		try {
			RandomAccessFile raf = new RandomAccessFile(wavFile, "r");
			byte[] header = new byte[44];
			raf.read(header);
			raf.close();

			// Verify RIFF header
			if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
				return null;
			}

			WavHeader wh = new WavHeader();
			wh.channels = header[22] | (header[23] << 8);
			wh.sampleRate = (header[24] & 0xFF) | ((header[25] & 0xFF) << 8) |
					((header[26] & 0xFF) << 16) | ((header[27] & 0xFF) << 24);
			wh.bitsPerSample = header[34] | (header[35] << 8);
			wh.dataSize = (header[40] & 0xFFL) | ((header[41] & 0xFFL) << 8) |
					((header[42] & 0xFFL) << 16) | ((header[43] & 0xFFL) << 24);
			return wh;
		} catch (IOException e) {
			Timber.e(e, "Failed to read WAV header");
			return null;
		}
	}

	private static void writeWavHeader(File file, int sampleRate, int channels, int bitsPerSample, long dataSize) throws IOException {
		long totalSize = dataSize + 36;
		long byteRate = (long) sampleRate * channels * (bitsPerSample / 8);
		int blockAlign = channels * (bitsPerSample / 8);

		// For 32-bit float, use format code 3 (IEEE float); for integer PCM use 1
		int audioFormat = (bitsPerSample == 32) ? 3 : 1;

		byte[] header = new byte[44];
		header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
		header[4] = (byte) (totalSize & 0xff);
		header[5] = (byte) ((totalSize >> 8) & 0xff);
		header[6] = (byte) ((totalSize >> 16) & 0xff);
		header[7] = (byte) ((totalSize >> 24) & 0xff);
		header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
		header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
		header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
		header[20] = (byte) audioFormat; header[21] = 0;
		header[22] = (byte) channels; header[23] = 0;
		header[24] = (byte) (sampleRate & 0xff);
		header[25] = (byte) ((sampleRate >> 8) & 0xff);
		header[26] = (byte) ((sampleRate >> 16) & 0xff);
		header[27] = (byte) ((sampleRate >> 24) & 0xff);
		header[28] = (byte) (byteRate & 0xff);
		header[29] = (byte) ((byteRate >> 8) & 0xff);
		header[30] = (byte) ((byteRate >> 16) & 0xff);
		header[31] = (byte) ((byteRate >> 24) & 0xff);
		header[32] = (byte) blockAlign; header[33] = 0;
		header[34] = (byte) bitsPerSample; header[35] = 0;
		header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
		header[40] = (byte) (dataSize & 0xff);
		header[41] = (byte) ((dataSize >> 8) & 0xff);
		header[42] = (byte) ((dataSize >> 16) & 0xff);
		header[43] = (byte) ((dataSize >> 24) & 0xff);

		RandomAccessFile raf = new RandomAccessFile(file, "rw");
		raf.seek(0);
		raf.write(header);
		raf.close();
	}

	private static class WavHeader {
		int channels;
		int sampleRate;
		int bitsPerSample;
		long dataSize;
	}
}
