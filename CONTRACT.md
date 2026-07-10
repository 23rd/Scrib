# Open Transcribe API

A small AIDL contract that lets any Android app hand a **recorded audio file** to any installed
**on-device transcription app** and get text back, without the audio leaving the device.

The namespace `org.opentranscribe.api` deliberately belongs to no single application, so that
implementing the contract does not mean adopting another project's brand. This app
(`org.scrib.transcriber`) is one implementation; Forkgram is one client.

Contract version: **1**

## Why this exists

Android's own `android.speech.RecognitionService` can, since API 33, be fed a file through
`RecognizerIntent.EXTRA_AUDIO_SOURCE`. That path was measured and rejected:

- the extra takes **raw PCM**, so every client must decode its own audio first;
- the framework requires `RECORD_AUDIO` from *every node* of the caller's `AttributionSource`, so a
  transcriber that never opens a microphone must still hold a microphone permission, or
  `#startListening` is refused before `onStartListening` ever runs;
- no FOSS recognizer honours the extra anyway — they capture the microphone directly, so handing one
  a voice note silently records the room instead.

This contract accepts an encoded file, needs no dangerous permissions, and lets the transcriber
decode whatever container it was given.

## Discovery

Services declare an intent filter on the action:

```
org.opentranscribe.api.ITranscriptionService
```

Clients enumerate implementations with `PackageManager.queryIntentServices()` and must add a
`<queries>` entry for that action in their manifest.

**Security: never auto-bind to whichever app happens to claim the action.** The client hands over a
file descriptor to the user's audio. Present the discovered apps and let the user choose one
explicitly.

## Interfaces

```aidl
interface ITranscriptionService {
    TranscriberCapabilities getCapabilities();

    ITranscriptionSession transcribe(
        in ParcelFileDescriptor audio,
        in TranscriptionRequest request,
        ITranscriptionCallback callback);
}

oneway interface ITranscriptionSession {
    void cancel();
}

oneway interface ITranscriptionCallback {
    void onTranscriptionProgress(String text);
    void onTranscriptionResult(String text);
    void onTranscriptionError(in TranscriptionError error);
}
```

`transcribe` returns immediately with a session handle; the work happens off the binder thread and
reports through the callback. Exactly one terminal callback is delivered per request — either
`onTranscriptionResult` or `onTranscriptionError`.

`onTranscriptionProgress` carries the **cumulative** text recognised so far, not a delta. It is
optional: an implementation that cannot stream simply never calls it.

## Data

- `TranscriptionRequest.fileName` — original name, useful for sniffing the container. May be null.
- `TranscriptionRequest.mimeType` — e.g. `audio/ogg`, `video/mp4`. May be null; the service should
  then detect the format itself.
- `TranscriptionRequest.languageHint` — ISO 639-1 code (`ru`, `en`). **Empty or null means
  auto-detect.**
- `TranscriberCapabilities.contractVersion` — the version of this document the service implements.
- `TranscriberCapabilities.supportedLanguages` — ISO 639-1 codes, or null when unspecified.
- `TranscriberCapabilities.modelReady` — false when the service still needs to fetch a model, so the
  first request will be slow or may fail with `MODEL_NOT_AVAILABLE`.

The audio is passed as a `ParcelFileDescriptor`, never as a path: the transcriber runs in its own
sandbox and cannot open the client's private files by name. The **client** owns the descriptor and
closes it once the request settles; the service must not use it afterwards.

## Cancellation

`ITranscriptionSession.cancel()` must actually stop the work, not merely discard the result. After a
cancel the service delivers `onTranscriptionError` with `ErrorType.CANCELLED`. Clients should also
cancel when they stop caring about the answer — a long recording otherwise keeps a phone's CPU busy
for minutes.

Clients must not block forever waiting for a slow service. Use an **idle** timeout measured from the
last callback, not a total deadline: a fifteen-minute recording legitimately takes many minutes, and
a fixed deadline silently kills successful work.

## Evolving the contract without breaking implementations

Structured AIDL parcelables are written with a size prefix, and the generated `readFromParcel` stops
when it runs out of bytes. Therefore:

- **Parcelable fields may only be appended at the end.** Never reorder, retype, or remove one.
- **Enum constants may only be appended.** Existing ordinals must not shift.
- **Adding a method to `ITranscriptionCallback` is safe.** The client implements it; an older service
  simply never calls it, and the interface is `oneway`, so a newer service calling it on an older
  client is silently dropped rather than throwing.
- **Adding a method to `ITranscriptionService` is not safe** for services built against an older
  copy — the call raises `RemoteException`. Gate any new method on
  `getCapabilities().contractVersion` and be ready to catch.

Bump `contractVersion` whenever anything is appended.

## Errors

`ErrorType` is `MODEL_NOT_AVAILABLE`, `DECODE_FAILED`, `UNSUPPORTED_LANGUAGE`, `UNEXPECTED`,
`CANCELLED`. `TranscriptionError` also carries an optional `language` and a human-readable
`message`.
