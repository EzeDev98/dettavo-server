package com.dettavo.feature.transcription.web;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.common.exception.MediaNotFoundException;
import com.dettavo.config.ApiPaths;
import com.dettavo.feature.project.service.ProjectStore;
import com.dettavo.feature.transcription.service.TranscriptionFiles;
import com.dettavo.feature.transcription.service.WavAudio;
import com.dettavo.feature.transcription.service.ChunkPlanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Profile("dev")
@RestController
@RequiredArgsConstructor
@Tag(name = "Development", description = "Dev profile only")
public class DevelopmentTranscriptionController {

    private final ProjectStore projectStore;
    private final TranscriptionFiles transcriptionFiles;
    private final ChunkPlanner chunkPlanner;

    public record ChunkFile(int number, long startMs, long endMs, long durationMs, String file) {
    }

    @PostMapping(ApiPaths.BASE + "/dev/projects/{projectId}/clips/{clipId}/chunks")
    @Operation(summary = "Write a clip's chunk WAVs", description = "Cuts cache/audio16k.wav into the chunks whisper will get.")
    public List<ChunkFile> chunks(@PathVariable UUID projectId, @PathVariable String clipId) {
        projectStore.load(projectId).findClip(clipId).orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
        Path audio = transcriptionFiles.audio16k(projectId, clipId);
        if (!Files.isRegularFile(audio)) {
            throw new MediaNotFoundException("Clip " + clipId + " has no 16 kHz audio yet; prepare it first");
        }
        WavAudio wav = WavAudio.read(audio);
        return chunkPlanner.plan(wav.durationMs()).stream()
                .map(chunk -> {
                    Path target = transcriptionFiles.chunkAudio(projectId, clipId, chunk);
                    wav.writeSlice(audio, chunk.startMs(), chunk.endMs(), target);
                    return new ChunkFile(chunk.number(), chunk.startMs(), chunk.endMs(), chunk.durationMs(), target.toString());
                })
                .toList();
    }
}
