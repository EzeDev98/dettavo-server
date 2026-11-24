package com.dettavo.feature.transcription.web;

import com.dettavo.config.ApiPaths;
import com.dettavo.feature.job.dto.response.JobResponse;
import com.dettavo.feature.transcription.service.TranscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Transcription", description = "Transcribing a clip")
public class TranscriptionController {

    private final TranscriptionService transcriptionService;

    @PostMapping(ApiPaths.CLIP_TRANSCRIBE)
    @Operation(summary = "Retry a clip's transcription", description = "For a prepared or failed clip; resumes from the chunks "
            + "already finished. 202 with the job; 409 if the clip is already transcribed, not prepared, or has a job.")
    public ResponseEntity<JobResponse> transcribe(@PathVariable UUID projectId, @PathVariable String clipId) {
        return ResponseEntity.accepted().body(transcriptionService.retry(projectId, clipId));
    }
}
