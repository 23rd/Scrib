package org.scrib.transcriber;

import org.scrib.transcriber.TranscriptionError;

oneway interface ITranscriptionCallback {
    void onTranscriptionProgress(String text);
    void onTranscriptionResult(String text);
    void onTranscriptionError(in TranscriptionError error);
}
