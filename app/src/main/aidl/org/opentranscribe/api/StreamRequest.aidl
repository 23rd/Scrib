package org.opentranscribe.api;

parcelable StreamRequest {
    @nullable String languageHint;
    int sampleRate;
    int channels;
}
