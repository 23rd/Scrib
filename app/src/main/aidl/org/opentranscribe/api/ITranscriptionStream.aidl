package org.opentranscribe.api;

interface ITranscriptionStream {
    void write(in byte[] pcm, int length);
    oneway void endOfStream();
    oneway void cancel();
}
