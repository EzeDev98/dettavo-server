package com.dettavo.feature.transcription;

import com.dettavo.config.ApplicationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a clip's audio into chunks for whisper (guide 6.1). Chunk k covers [k x 10 min - 2 s, (k + 1) x 10 min],
 * clamped to the file; the 2 s overlap stops a word cut in half at a boundary from being lost.
 */
@Component
public class ChunkPlanner {

    private final long chunkMs;
    private final long overlapMs;

    public ChunkPlanner(ApplicationProperties properties) {
        this.chunkMs = properties.transcription().chunkMinutes() * 60_000L;
        this.overlapMs = properties.transcription().overlapSeconds() * 1000L;
    }

    /**
     * One chunk, numbered from 1. Its words are kept when they start in [keepFromMs, keepUntilMs): the boundary
     * between two chunks belongs to the later one, so no word is lost or doubled (10d).
     */
    public record Chunk(int number, long startMs, long endMs, long keepFromMs, long keepUntilMs) {

        /** chunk-0001: the name of its WAV and JSON files in cache/whisper/. */
        public String baseName() {
            return String.format("chunk-%04d", number);
        }

        public long durationMs() {
            return endMs - startMs;
        }

        public boolean keeps(long wordStartMs) {
            return wordStartMs >= keepFromMs && wordStartMs < keepUntilMs;
        }
    }

    /** No chunks for empty audio. A 25-minute clip gives 0:00-10:00, 9:58-20:00 and 19:58-25:00. */
    public List<Chunk> plan(long durationMs) {
        int count = (int) ((durationMs + chunkMs - 1) / chunkMs);
        List<Chunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long boundary = index * chunkMs;
            long start = index == 0 ? 0 : Math.max(0, boundary - overlapMs);
            long end = Math.min(durationMs, boundary + chunkMs);
            long keepUntil = index == count - 1 ? Long.MAX_VALUE : boundary + chunkMs;
            chunks.add(new Chunk(index + 1, start, end, boundary, keepUntil));
        }
        return chunks;
    }
}
