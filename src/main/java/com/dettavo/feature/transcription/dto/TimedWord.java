package com.dettavo.feature.transcription.dto;

/** A word before it gets its id. Times are milliseconds on the clip's own source timeline. */
public record TimedWord(String text, long startMs, long endMs, double confidence) {

    public static final long MIN_DURATION_MS = 20;

    public TimedWord withTimes(long start, long end) {
        return new TimedWord(text, start, end, confidence);
    }
}
