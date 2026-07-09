package org.scrib.transcriber;

import org.scrib.transcriber.ErrorType;

parcelable TranscriptionError {
    ErrorType type;
    @nullable String language;
    @nullable String message;
}
