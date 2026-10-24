# Testing Scrib on a phone from a script

Entry points that let a test agent (or a person over adb) drive the app without stumbling
through incidental UI — the system file picker, permission dialogs, the share chooser. Each
script below was verified against a real phone; the programmatic hooks they use live in the
app itself and are documented alongside the code that serves them.

## `send-audio.sh` — transcribe a file without the file picker

```sh
scripts/onphone/send-audio.sh transcribe.cpp/samples/jfk.wav
```

Copies the file into the debug app's private storage with `run-as` (works on any debuggable
build, needs no root) and starts transcription via the app's real `ACTION_SEND` entry point
(`MainActivity.maybeTranscribeShared`), targeted at the package with `-p` so no chooser
dialog appears. The transcript lands in `files/history/` inside the app's data dir:

```sh
adb shell run-as org.scrib.transcriber.debug ls -t files/history
adb shell run-as org.scrib.transcriber.debug cat files/history/<id>.json
```

The same file delivery works for anything that accepts a file: `run-as` puts it at
`/data/data/<package>/files/<name>`, readable by the app as
`file:///data/data/<package>/files/<name>` — no storage permissions or SAF involved.

## Navigating by tags (resource-id)

The main window exposes Compose tags as `resource-id` in the accessibility tree, so an agent
can find and tap controls without screenshots or text guessing:

```sh
adb exec-out uiautomator dump /dev/tty | grep -o 'resource-id="scrib[^"]*"'
# tap one: read its bounds="[l,t][r,b]" from the same dump and input tap the centre
```

Scheme: `scrib/tab/<home|record|file|keyboard|settings>` for the bottom navigation,
`scrib/home/record`, `scrib/home/pick_file`, `scrib/home/active_model`,
`scrib/transcribe/pick_file`, `scrib/record/button`, `scrib/appbar/about`.

Dialogs open their own windows where the opt-in flag does not reach; navigate them by their
text (`uiautomator dump` still lists every label). Tags appear without a package prefix —
match `"scrib/..."` exactly, not `<package>:id/scrib/...`.

## Watching a run from the outside

```sh
adb logcat -s ScribTranscribe     # engine progress
adb logcat -d | grep -E "FATAL EXCEPTION|Process: org.scrib"   # crash check
```
