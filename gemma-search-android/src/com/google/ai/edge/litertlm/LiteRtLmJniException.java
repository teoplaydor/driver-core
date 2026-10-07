package com.google.ai.edge.litertlm;

/** Thrown by the native library ({@code ThrowNew}, so it needs the {@code (String)} constructor). */
public class LiteRtLmJniException extends RuntimeException {
    public LiteRtLmJniException(String message) {
        super(message);
    }
}
