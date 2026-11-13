package io.github.teoplaydor.semsearch.secretary;

/** The model could not answer (not loaded, interrupted, an answer that is not the asked JSON) — the server's LLMError. */
public class LlmError extends Exception {
    public LlmError(String message) {
        super(message);
    }
}
