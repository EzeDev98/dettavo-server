package com.dettavo.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;

/**
 * Every {@code app.*} setting, bound once at startup. Each value can be overridden with
 * the environment variable named in application.properties, or in the git-ignored
 * application-local.properties.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record ApplicationProperties(
        @NotNull Path workspaceDir,
        @Valid @NotNull Tools tools,
        @Valid @NotNull Transcription transcription,
        @Valid @NotNull Peaks peaks,
        @Valid @NotNull Proxy proxy,
        @Valid @NotNull Edit edit,
        @Valid @NotNull Export export,
        @Valid @NotNull Jobs jobs,
        @Valid @NotNull Disk disk,
        @Valid @NotNull Security security) {

    /** Tool paths; a blank path means "find it in {@code searchPath}". */
    public record Tools(String ffmpeg,
                        String ffprobe,
                        String whisperCli,
                        @NotNull Path whisperModel,
                        @NotEmpty List<String> searchPath) {
    }

    /**
     * whisper-cli and the steps after it (guide 6). dtw and loopGuard come from checks 1d and 1j; the rest tune
     * pause refinement (6.4) and paragraphs (6.5).
     */
    public record Transcription(@Min(1) int chunkMinutes,
                                @Min(0) int overlapSeconds,
                                @Min(1) int threads,
                                @NotBlank String language,
                                boolean dtw,
                                @NotNull LoopGuard loopGuard,
                                @NotNull Path vadModel,
                                @Min(1) @Max(99) int noisePercentile,
                                @DecimalMin("1.0") double speechFactor,
                                @Min(0) int hangoverMs,
                                @Min(0) int leadInMs,
                                @Min(0) int maxEdgeShiftMs,
                                @Min(1) int paragraphPauseMs,
                                @Min(1) int paragraphMaxWords) {

        /** What stops whisper repeating one phrase: nothing, -mc 0, or voice activity detection. */
        public enum LoopGuard {
            NONE,
            MAX_CONTEXT_ZERO,
            VAD
        }
    }

    public record Peaks(@Min(1) int perSecond) {
    }

    public record Proxy(@Min(144) int height) {
    }

    public record Edit(@Min(0) int defaultPaddingMs,
                       @Min(0) int minKeepSegmentMs) {
    }

    public record Export(@Min(0) int audioFadeMs) {
    }

    public record Jobs(@Min(1) int maxConcurrentHeavy) {
    }

    public record Disk(@Min(0) long reserveBytes) {
    }

    /** Host names accepted in the Host header, and the client origins allowed to call the API. */
    public record Security(@NotEmpty List<String> allowedHosts,
                           @NotEmpty List<String> allowedOrigins) {
    }
}
