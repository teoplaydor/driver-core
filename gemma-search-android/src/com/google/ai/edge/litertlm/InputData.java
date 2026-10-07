package com.google.ai.edge.litertlm;

/**
 * Model input, as LiteRT-LM's Kotlin {@code sealed class InputData}: the native library looks up
 * {@code InputData$Text.getText()}, {@code InputData$Audio.getBytes()} and {@code InputData$Image.getBytes()}.
 */
public abstract class InputData {
    private InputData() {}

    public static final class Text extends InputData {
        private final String text;

        public Text(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
    }

    /** WAV bytes. */
    public static final class Audio extends InputData {
        private final byte[] bytes;

        public Audio(byte[] bytes) {
            this.bytes = bytes;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }

    /** PNG or JPEG bytes; the engine decodes, resizes and patchifies them itself. */
    public static final class Image extends InputData {
        private final byte[] bytes;

        public Image(byte[] bytes) {
            this.bytes = bytes;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }
}
