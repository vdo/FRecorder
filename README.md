# FRecorder

**FRecorder** is a fast, no-nonsense field audio recorder for Android — built for real-world production use. Whether you're on a film set capturing location sound through a USB audio interface, recording interviews, or just need a reliable pocket recorder, FRecorder gets out of your way and lets you hit record.

Fork of [Dimowner/AudioRecorder](https://github.com/Dimowner/AudioRecorder) with significant additions for professional field recording workflows.

## Features

- **USB Audio Input** — Record from external USB microphones, audio interfaces, and other USB audio devices. Automatically detects connected devices and lets you select them as the recording source.
- **Live Monitoring** — Listen to what's being recorded in real-time through Bluetooth headphones or the built-in speaker. Intelligently routes playback to the correct output device when using external USB audio. Toggle on/off during recording.
- **Gain Boost** — Adjustable input gain (+6 dB / +12 dB) to amplify quiet sources. Applied in real-time with clipping protection.
- **Noise Reduction** — Optional spectral noise reduction applied on save (WAV only).
- **High/Low-Pass Filters** — Configurable HPF (80/120 Hz) and LPF (9.5/15 kHz) for cleaning up recordings.
- **Noise Gate** — Monitor-only noise gate to cut background noise during live monitoring.
- **Flexible Formats** — M4A (AAC), WAV (uncompressed), and 3GP. Configurable sample rate (8–48 kHz), bitrate, and mono/stereo.
- **Fast Startup** — Optimized to launch quickly so you never miss a take.
- **Visual Waveform** — Real-time waveform display during recording and playback.
- **File Management** — Rename, share, import, bookmark, trash/restore recordings. Built-in file browser.
- **Themes** — Multiple color themes to personalize the app.

## Building

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebugConfigDebug
```

## Credits

FRecorder is based on [Audio Recorder](https://github.com/Dimowner/AudioRecorder) by Dmytro Ponomarenko.

## License

```
Copyright 2019 Dmytro Ponomarenko
Copyright 2025 vdo

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
