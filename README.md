# <a href="https://f-droid.org/packages/org.scrib.transcriber/" title="Scrib on F-Droid"><img src="images/icon.png" width="34" alt="Scrib on F-Droid"></a> <a href="https://github.com/23rd/Scrib/releases/latest" title="Scrib Max on GitHub releases"><img src="images/icon-max.png" width="34" alt="Scrib Max on GitHub releases"></a> Scrib

[![F-Droid](https://img.shields.io/f-droid/v/org.scrib.transcriber.svg)](https://f-droid.org/packages/org.scrib.transcriber/)
[![License](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

On-device voice transcription for Android. Your audio never leaves your phone,
until you choose otherwise.

Scrib turns speech into text on your device, using open
[Whisper](https://github.com/ggml-org/whisper.cpp) speech models. Nothing is
uploaded — the app only goes online to download a model you choose, unless you
move transcription to an endpoint yourself.

## Two ways to use it

- **Transcribe your own audio.** Pick any audio file on your phone and get the
  text, right in the app, fully offline.
- **A transcription engine for other apps.** Scrib also runs in the background.
  Apps that support it — such as Forkgram — can turn a voice message into text
  through Scrib, on your device. In that app's settings, choose Scrib as the
  offline transcriber.

## Models

Scrib transcribes with open Whisper speech models. Download one on the main
screen, or pick your language and Scrib fetches a suitable one. Larger models
are more accurate but slower and bigger to download; keep several and switch any
time. You can also add any whisper.cpp-compatible GGML model by direct link, or
import a `.bin` from your device.

Models are downloaded separately and are not bundled in the app.

## Three builds

Scrib ships in three flavours from this one repository. They differ in what they
can run and in how much they weigh — not in what they promise you.

- **Scrib** (`org.scrib.transcriber`) is the lean build, the one F-Droid ships.
  Whisper models only, small installer, nothing but model downloads over the
  network.
- **Scrib Max** (`org.scrib.transcriber.max`) is the extended build: every model
  family the [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runtime covers
  on top of Whisper — SenseVoice, Canary, streaming Zipformer, Parakeet,
  Moonshine, Dolphin, FireRed — and transcription on a remote endpoint you
  configure. By far the largest of the three.
- **Scrib Remote** (`org.scrib.transcriber.remote`) has no models and no
  inference code at all: it uploads the audio to a server you control. The
  smallest of the three, and the same size on every phone.

Separate application ids, so all three install side by side and none replaces
another. Each is attached to every
[GitHub release](https://github.com/23rd/Scrib/releases/tag/latest) as a
universal apk and once per architecture — `arm64-v8a` for nearly every phone,
`armeabi-v7a` for older 32-bit ones, `x86_64` and `x86` for emulators and
Waydroid. Every one of them carries the same version code, so switching between
them needs no uninstall.

## For developers

Any app can use Scrib as an offline transcriber through the open **Open
Transcribe** contract (`org.opentranscribe.api`) — a small AIDL service, no SDK
and no network. The contract is vendor-neutral, so apps aren't tied to Scrib:
they can bind any compatible transcriber the user has installed. See
[CONTRACT.md](CONTRACT.md).

## Building

The native transcription library is compiled from the bundled `whisper.cpp`
submodule via CMake and the NDK, so clone with submodules:

```
git clone --recurse-submodules <repo-url>
cd scrib
./gradlew :app:assembleRelease
```

Requirements: Android SDK 35, NDK 27, CMake. `minSdk` 26, `arm64-v8a`.

Scrib Max is the same build plus `-PwithSherpa`, which pulls in the extra sources
and the sherpa runtime AAR:

```
scripts/download-sherpa-onnx.sh
./gradlew :app:assembleRelease -PwithSherpa
```

Scrib Remote is `-PwithRemote`: the remote endpoints without whisper.cpp, so no
NDK, no submodule and no AAR.

```
./gradlew :app:assembleRelease -PwithRemote
```

Omit both flags and you get the lean Scrib that F-Droid builds. `-Pabis=` picks
the architectures to compile and `-PabiSplits` adds one apk per architecture next
to the universal one; the release workflow passes both.

## Privacy

By default transcription runs 100% on device: your voice messages never leave
the phone, and the only network access is downloading a model you pick. The
F-Droid build does nothing else.

Scrib Max and Scrib Remote can also transcribe on a server instead — any
OpenAI-compatible endpoint you configure, or Cloudflare Workers AI. While one is
selected, the audio is uploaded there over HTTPS and the app names that address
on screen. Remote has no other engine, so its audio always leaves the phone.

## License

Scrib is free software under the
[GNU General Public License v3.0 or later](LICENSE).

It bundles [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT,
© The ggml authors), which runs the Whisper speech models.
