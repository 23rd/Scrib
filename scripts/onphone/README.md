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

## Opening a tab directly (no bottom-bar taps)

MainActivity reads a string extra naming the tab and lands there on launch — cold start or
singleTop re-delivery to the running instance alike. Unknown names are ignored:

```sh
adb shell am start -W -n org.scrib.transcriber.debug/org.scrib.transcriber.MainActivity \
    --es org.scrib.transcriber.OPEN_TAB settings
```

Names match the bottom tabs: `home`, `record`, `file` (transcription), `keyboard`, `settings`.

One thing to expect on every launch after a finished run: the transcription dialog restores
itself from the persisted transcript and sits on top of whatever tab was requested. Dismiss
it with BACK (the dialog's Close works too) before asserting against the screen.

## Debug command receiver

Debug builds carry an exported receiver answering four commands; every result lands on the
`scrib.state` logcat tag. Broadcasts reach it only when the process is alive — this ColorOS
ROM even blocks shell broadcasts into a background app — so start the activity first and
target the receiver by component:

```sh
adb shell am start -W -n org.scrib.transcriber.debug/org.scrib.transcriber.MainActivity
RCV=org.scrib.transcriber.debug/org.scrib.transcriber.TestCommandsReceiver
adb shell am broadcast -n $RCV -a org.scrib.transcriber.test.STATE
adb logcat -d -s scrib.state
```

- `STATE` — snapshot: package, active model, language, installed models, transcription run
  (file, running, percent, error), history size.
- `OPEN_TAB --es tab file` — same as the MainActivity extra.
- `TRANSCRIBE --es path /android_asset/jfk.wav` — transcribe without any file delivery at
  all (the asset ships in local builds). A plain path must be inside the app's own dirs —
  deliver with `run-as cp` like `send-audio.sh` does. An in-flight run answers "busy".
- `IMPORT_MODEL --es path /data/data/org.scrib.transcriber.debug/files/model.gguf` — copies
  the file into the model dir (off the main thread; watch the tag for done/failed).

Security posture: the receiver ships in debug artifacts only, and from Android 14 on it
serves only adb sessions (shell/root uids). On older devices the sender of a broadcast is
unknowable, so any local app could reach a debug build's receiver — accepted, since a
debuggable APK is already a wide-open local surface (run-as, JDWP) and F-Droid ships only
release builds.

## Watching a run from the outside

The app narrates its lifecycle on the same `scrib.state` tag (debug builds only), so an agent
follows a run without polling the screen:

```sh
adb logcat -s scrib.state
# run started: file=jfk.wav model=base-q5_1
# run 25%: file=jfk.wav        (milestones every 25%)
# run done: file=jfk.wav text=...     — or run failed: file=... error=... / run cancelled
# model active: ggml-tiny-q5_1.bin
```

Plus the engine's own stream for lower-level checks:

```sh
adb logcat -s ScribTranscribe     # engine progress
adb logcat -d | grep -E "FATAL EXCEPTION|Process: org.scrib"   # crash check
```
