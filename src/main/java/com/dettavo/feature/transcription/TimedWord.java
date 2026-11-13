package com.dettavo.feature.transcription;

/** A word before it gets its id. Times are milliseconds on the clip's own source timeline. */
public record TimedWord(String text, long startMs, long endMs, double confidence) {

    /** Guide 6.3: a word lasts at least 20 ms. */
    public static final long MIN_DURATION_MS = 20;

    public TimedWord withTimes(long start, long end) {
        return new TimedWord(text, start, end, confidence);
    }
}
EOFcat > src/main/java/com/dettavo/feature/transcription/TimedWord.java <<'EOF'
package com.dettavo.feature.transcription;

/** A word before it gets its id. Times are milliseconds on the clip's own source timeline. */
public record TimedWord(String text, long startMs, long endMs, double confidence) {

    /** Guide 6.3: a word lasts at least 20 ms. */
    public static final long MIN_DURATION_MS = 20;

    public TimedWord withTimes(long start, long end) {
        return new TimedWord(text, start, end, confidence);
    }
}
