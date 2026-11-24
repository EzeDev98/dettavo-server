package com.dettavo.feature.transcription.service;

import com.dettavo.feature.media.service.ClipFiles;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TranscriptionFiles {

    public static final String WHISPER_FOLDER = ClipFiles.CACHE_FOLDER + "/whisper";

    private final ClipFiles clipFiles;

    public Path audio16k(UUID projectId, String clipId) {
        return clipFiles.artifact(projectId, clipId, ClipFiles.AUDIO_16K);
    }

    public Path folder(UUID projectId, String clipId) {
        return clipFiles.artifact(projectId, clipId, WHISPER_FOLDER);
    }

    public Path chunkAudio(UUID projectId, String clipId, ChunkPlanner.Chunk chunk) {
        return folder(projectId, clipId).resolve(chunk.baseName() + ".wav");
    }

    public Path chunkJson(UUID projectId, String clipId, ChunkPlanner.Chunk chunk) {
        return folder(projectId, clipId).resolve(chunk.baseName() + ".json");
    }
}
