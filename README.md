# Scrib

On-device voice transcription for Android. Your audio never leaves your phone.

Scrib turns speech into text entirely on your device, using open
[Whisper](https://github.com/ggml-org/whisper.cpp) speech models. Nothing is
uploaded — the app only goes online to download a model you choose.

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

## Privacy

Transcription runs 100% on device. Your voice messages never leave your phone.
The only network access is downloading a speech model you pick, over HTTPS.

## License

Scrib is free software under the
[GNU General Public License v3.0 or later](LICENSE).

It bundles [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT,
© The ggml authors), which runs the Whisper speech models.
