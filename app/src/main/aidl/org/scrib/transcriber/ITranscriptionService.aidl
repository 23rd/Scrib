package org.scrib.transcriber;

import org.scrib.transcriber.ITranscriptionCallback;

interface ITranscriptionService {
    void transcribe(in ParcelFileDescriptor audio, String fileName, String languageHint, ITranscriptionCallback callback);
}
