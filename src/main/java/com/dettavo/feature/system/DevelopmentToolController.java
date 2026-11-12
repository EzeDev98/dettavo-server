package com.dettavo.feature.system;

import com.dettavo.common.exception.ToolFailedException;
import com.dettavo.common.file.WorkspacePaths;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.common.process.ExecutablePathResolver;
import com.dettavo.common.process.NativeProcessRunner;
import com.dettavo.common.process.ProcessResult;
import com.dettavo.common.process.ProcessSpecification;
import com.dettavo.config.ApiPaths;
import com.dettavo.config.ApplicationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Development-only checks of the process runner. Absent unless the dev profile is active. */
@Profile("dev")
@RestController
@RequiredArgsConstructor
@Tag(name = "Development", description = "Dev profile only")
public class DevelopmentToolController {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final NativeProcessRunner nativeProcessRunner;
    private final ExecutablePathResolver executablePathResolver;
    private final WorkspacePaths workspacePaths;
    private final ApplicationProperties properties;

    public record ProcessCheckResponse(int exitCode, long elapsedMilliseconds, String firstLine, String logFile) {
    }

    @GetMapping(ApiPaths.BASE + "/dev/ffmpeg-version")
    @Operation(summary = "Run ffmpeg -version through the process runner", description = "Writes the command and output to ~/Dettavo/logs/dev-ffmpeg-version-<time>.log")
    public ProcessCheckResponse ffmpegVersion() {
        Path ffmpeg = executablePathResolver.resolve(properties.tools().ffmpeg(), "ffmpeg")
                .orElseThrow(() -> new ToolFailedException("ffmpeg", "not found in " + executablePathResolver.searchPathValue(), null, List.of()));

        Path logFile = workspacePaths.logsDirectory()
                .resolve("dev-ffmpeg-version-" + LocalDateTime.now().format(LOG_TIMESTAMP) + ".log");
        AtomicReference<String> firstLine = new AtomicReference<>();

        ProcessResult result = nativeProcessRunner.run(new ProcessSpecification(
                List.of(ffmpeg.toString(), "-version"), null, TIMEOUT,
                line -> firstLine.compareAndSet(null, line), null, logFile), CancellationToken.none());

        return new ProcessCheckResponse(result.exitCode(), result.elapsed().toMillis(), firstLine.get(), logFile.toString());
    }
}
