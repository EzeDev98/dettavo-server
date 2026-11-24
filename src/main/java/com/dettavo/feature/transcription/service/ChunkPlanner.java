package com.dettavo.feature.transcription.service;

import com.dettavo.config.ApplicationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChunkPlanner {

    private final long chunkMs;
    private final long overlapMs;

    public ChunkPlanner(ApplicationProperties properties) {
        this.chunkMs = properties.transcription().chunkMinutes() * 60_000L;
        this.overlapMs = properties.transcription().overlapSeconds() * 1000L;
    }

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
