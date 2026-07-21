# Open Transcribe API

A small AIDL contract that lets any Android app hand a **recorded audio file** — or a **live PCM
stream** — to any installed **on-device transcription app** and get text back, without the audio
leaving the device.

The namespace `org.opentranscribe.api` deliberately belongs to no single application, so that
implementing the contract does not mean adopting another project's brand. This app
(`org.scrib.transcriber`) is one implementation; Forkgram is one client.

Contract version: **2**

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

    ITranscriptionStream openStream(
        in StreamRequest request,
        ITranscriptionCallback callback);
}

oneway interface ITranscriptionSession {
    void cancel();
}

interface ITranscriptionStream {
    void write(in byte[] pcm, int length);
    oneway void endOfStream();
    oneway void cancel();
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

## Live audio

`openStream` transcribes audio that is still being recorded. It uses the same callback as a file
request, with the same guarantee of exactly one terminal call, so a client that already handles
files needs no second result path.

```java
ITranscriptionStream stream = service.openStream(request, callback);
while (recording) {
    int n = audioRecord.read(buffer, 0, buffer.length);   // not on the main thread
    stream.write(buffer, n);
}
stream.endOfStream();
```

- `write` takes **signed 16-bit little-endian PCM** at the rate and channel count named in the
  `StreamRequest`; `length` is how much of the array is filled, so one buffer can be reused. Keep
  chunks well under the ~1 MB binder transaction limit — 20–100 ms of audio per call is ample.
- `write` is **synchronous and applies backpressure**: it blocks while the transcriber's backlog is
  full, which is what lets a client push a whole recording as fast as the device can absorb it. Call
  it from a background thread. It throws `IllegalStateException` if the backlog never drains; the
  stream stays usable, and the client may keep writing, `endOfStream`, or `cancel`.
- `endOfStream` transcribes the remaining audio and then delivers `onTranscriptionResult`. Nothing
  written after it is accepted.
- `cancel` stops the work and delivers `onTranscriptionError` with `ErrorType.CANCELLED`.
- The service also stops if the client's callback binder dies, so a crashed client cannot leave a
  phone transcribing forever.

Progress is reported **per utterance**, not per word: a recogniser built on whole-window models has
to wait for a natural break before it can decode. Text still only ever grows, so a client can
display each `onTranscriptionProgress` as-is without reconciling rewrites.

Clients must gate `openStream` on `getCapabilities().streaming` — a service implementing version 1
of this document throws `RemoteException` for the unknown method.

## Data

- `TranscriptionRequest.fileName` — original name, useful for sniffing the container. May be null.
- `TranscriptionRequest.mimeType` — e.g. `audio/ogg`, `video/mp4`. May be null; the service should
  then detect the format itself.
- `TranscriptionRequest.languageHint` — ISO 639-1 code (`ru`, `en`). **Empty or null means
  auto-detect.**
- `StreamRequest.languageHint` — the same, but a stream that auto-detects pins the language it
  settled on for the whole stream rather than re-deciding at every pause.
- `StreamRequest.sampleRate` — of the PCM the client will write; **0 means 16000**. The service
  resamples, so a client should send what it captures rather than converting first.
- `StreamRequest.channels` — **0 means mono**; multi-channel input is downmixed.
- `TranscriberCapabilities.contractVersion` — the version of this document the service implements.
- `TranscriberCapabilities.supportedLanguages` — ISO 639-1 codes, or null when unspecified.
- `TranscriberCapabilities.modelReady` — false when the service still needs to fetch a model, so the
  first request will be slow or may fail with `MODEL_NOT_AVAILABLE`.
- `TranscriberCapabilities.streaming` — whether `openStream` may be called at all.

A file's audio is passed as a `ParcelFileDescriptor`, never as a path: the transcriber runs in its
own sandbox and cannot open the client's private files by name. The **client** owns the descriptor
and closes it once the request settles; the service must not use it afterwards.

## Cancellation

`ITranscriptionSession.cancel()` and `ITranscriptionStream.cancel()` must actually stop the work, not
merely discard the result. After a cancel the service delivers `onTranscriptionError` with
`ErrorType.CANCELLED`. Clients should also cancel when they stop caring about the answer — a long
recording otherwise keeps a phone's CPU busy for minutes.

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

Version 2 added `openStream`, `ITranscriptionStream`, `StreamRequest` and
`TranscriberCapabilities.streaming` by those rules: a version 1 client keeps working against a
version 2 service untouched, and a version 2 client falls back to files when `streaming` is false.

Bump `contractVersion` whenever anything is appended.

## Errors

`ErrorType` is `MODEL_NOT_AVAILABLE`, `DECODE_FAILED`, `UNSUPPORTED_LANGUAGE`, `UNEXPECTED`,
`CANCELLED`. `TranscriptionError` also carries an optional `language` and a human-readable
`message`.
