package com.dettavo.feature.transcription;

import com.dettavo.feature.project.model.Word;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns whisper's tokens into the clip's words (guide 6.3) and joins the chunks (6.1). The text itself is never
 * edited; only non-speech is dropped, and, as a last defence against repetition loops (Step 1), a phrase repeated
 * three or more times in a row keeps only its first copy.
 */
@Component
public class TranscriptNormalizer {

    private static final int MAX_BRACKETED_WORDS = 6;
    private static final int MIN_PHRASE_WORDS = 2;
    private static final int MAX_PHRASE_WORDS = 12;
    private static final int REPEATS_TO_DROP = 3;

    /**
     * The words of one chunk, in clip time, keeping only those that start inside the chunk's own stretch
     * (stitching, 10d). useDtw takes each word's start from its first token's DTW time when there is one.
     */
    public List<TimedWord> chunkWords(List<WhisperJsonParser.Token> tokens, ChunkPlanner.Chunk chunk, boolean useDtw) {
        List<TimedWord> words = new ArrayList<>();
        WordBuilder current = null;
        for (WhisperJsonParser.Token token : tokens) {
            String text = token.text();
            if (text.isEmpty() || text.startsWith("[_") || text.startsWith("<|")) {
                continue;
            }
            boolean startsWord = current == null || token.firstInSegment() || Character.isWhitespace(text.charAt(0));
            String piece = startsWord ? text.strip() : text;
            if (piece.isEmpty()) {
                continue;
            }
            if (startsWord) {
                if (current != null) {
                    words.add(current.build(chunk.startMs()));
                }
                long start = useDtw && token.dtwMs() >= 0 ? token.dtwMs() : token.fromMs();
                current = new WordBuilder(piece, start, token.toMs(), token.probability());
            } else {
                current.append(piece, token.toMs(), token.probability());
            }
        }
        if (current != null) {
            words.add(current.build(chunk.startMs()));
        }
        return withoutNonSpeech(words).stream().filter(word -> chunk.keeps(word.startMs())).toList();
    }

    /** After every chunk is joined: fixed order, a minimum length, and no repetition loops. */
    public List<TimedWord> finish(List<TimedWord> joined) {
        List<TimedWord> ordered = new ArrayList<>(joined.size());
        long previousEnd = Long.MIN_VALUE;
        for (TimedWord word : joined) {
            long start = Math.max(word.startMs(), previousEnd);
            long end = Math.max(word.endMs(), start + TimedWord.MIN_DURATION_MS);
            ordered.add(word.withTimes(start, end));
            previousEnd = end;
        }
        return withoutRepeatedPhrases(ordered);
    }

    /** Ids w00001 upward in order, given only after stitching (6.3, rule 5). */
    public static List<Word> withIds(List<TimedWord> words) {
        List<Word> result = new ArrayList<>(words.size());
        for (int index = 0; index < words.size(); index++) {
            TimedWord word = words.get(index);
            double confidence = Math.round(word.confidence() * 1000) / 1000.0;
            result.add(new Word(String.format("w%05d", index + 1), word.text(), word.startMs(), word.endMs(), confidence));
        }
        return result;
    }

    /** Drops [BLANK_AUDIO], [MUSIC], (laughs) and the like, even when split across words, and words with no letter or digit. */
    static List<TimedWord> withoutNonSpeech(List<TimedWord> words) {
        List<TimedWord> kept = new ArrayList<>(words.size());
        for (int index = 0; index < words.size(); index++) {
            TimedWord word = words.get(index);
            if (opensNonSpeech(word.text())) {
                int close = -1;
                for (int next = index; next < Math.min(words.size(), index + MAX_BRACKETED_WORDS); next++) {
                    if (closesNonSpeech(words.get(next).text())) {
                        close = next;
                        break;
                    }
                }
                if (close >= 0) {
                    index = close;
                    continue;
                }
            }
            if (word.text().codePoints().anyMatch(Character::isLetterOrDigit)) {
                kept.add(word);
            }
        }
        return kept;
    }

    /** Keeps the first copy of a phrase of 2 to 12 words that is repeated 3 or more times in a row. */
    static List<TimedWord> withoutRepeatedPhrases(List<TimedWord> words) {
        int count = words.size();
        List<String> keys = words.stream().map(word -> key(word.text())).toList();
        boolean[] drop = new boolean[count];
        int index = 0;
        while (index < count) {
            int phrase = 0;
            int copies = 0;
            for (int length = MIN_PHRASE_WORDS; length <= MAX_PHRASE_WORDS && index + length * REPEATS_TO_DROP <= count; length++) {
                int found = 1;
                while (index + (found + 1) * length <= count && samePhrase(keys, index, index + found * length, length)) {
                    found++;
                }
                if (found >= REPEATS_TO_DROP) {
                    phrase = length;
                    copies = found;
                    break;
                }
            }
            if (copies >= REPEATS_TO_DROP) {
                for (int repeated = index + phrase; repeated < index + copies * phrase; repeated++) {
                    drop[repeated] = true;
                }
                index += copies * phrase;
            } else {
                index++;
            }
        }
        List<TimedWord> kept = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            if (!drop[position]) {
                kept.add(words.get(position));
            }
        }
        return kept;
    }

    private static boolean samePhrase(List<String> keys, int first, int second, int length) {
        for (int offset = 0; offset < length; offset++) {
            if (!keys.get(first + offset).equals(keys.get(second + offset))) {
                return false;
            }
        }
        return true;
    }

    private static String key(String text) {
        StringBuilder key = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(key::appendCodePoint);
        return key.toString();
    }

    private static boolean opensNonSpeech(String text) {
        return text.startsWith("[") || text.startsWith("(") || text.startsWith("*");
    }

    private static boolean closesNonSpeech(String text) {
        String trimmed = text.replaceAll("[.,!?]+$", "");
        return trimmed.endsWith("]") || trimmed.endsWith(")") || (trimmed.endsWith("*") && trimmed.length() > 1);
    }

    /** One word growing token by token. */
    private static final class WordBuilder {

        private final StringBuilder text;
        private final long startMs;
        private long endMs;
        private double probabilitySum;
        private int tokens;

        WordBuilder(String text, long startMs, long endMs, double probability) {
            this.text = new StringBuilder(text);
            this.startMs = startMs;
            this.endMs = endMs;
            this.probabilitySum = probability;
            this.tokens = 1;
        }

        void append(String piece, long tokenEndMs, double probability) {
            text.append(piece);
            endMs = Math.max(endMs, tokenEndMs);
            probabilitySum += probability;
            tokens++;
        }

        TimedWord build(long chunkStartMs) {
            return new TimedWord(text.toString(), chunkStartMs + startMs, chunkStartMs + endMs, probabilitySum / tokens);
        }
    }
}
