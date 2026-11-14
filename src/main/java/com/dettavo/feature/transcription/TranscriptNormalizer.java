package com.dettavo.feature.transcription;

import com.dettavo.feature.project.model.Word;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns whisper's tokens into the clip's words (guide 6.3) and joins the chunks (6.1). The text itself is never
 * edited and every spoken word is kept, retakes included: a sentence said five times appears five times, so each take
 * can be cut. Only non-speech is dropped. Repetition loops are prevented in whisper itself (loop-guard, -mc 0).
 */
@Component
public class TranscriptNormalizer {

    private static final int MAX_BRACKETED_WORDS = 6;

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

    /** After every chunk is joined: fixed order and a minimum length. */
    public List<TimedWord> finish(List<TimedWord> joined) {
        List<TimedWord> ordered = new ArrayList<>(joined.size());
        long previousEnd = Long.MIN_VALUE;
        for (TimedWord word : joined) {
            long start = Math.max(word.startMs(), previousEnd);
            long end = Math.max(word.endMs(), start + TimedWord.MIN_DURATION_MS);
            ordered.add(word.withTimes(start, end));
            previousEnd = end;
        }
        return ordered;
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
