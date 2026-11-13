package com.dettavo.feature.media;

import com.dettavo.common.exception.ToolFailedException;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.common.process.NativeProcessRunner;
import com.dettavo.common.process.ProcessResult;
import com.dettavo.common.process.ProcessSpecification;
import com.dettavo.common.process.ToolInventory;
import com.dettavo.common.process.ToolLocator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Runs FFmpeg for one output file. The output goes to a .partial file that is renamed only when FFmpeg
 * succeeds; progress comes from the out_time_us= lines of -progress pipe:1, divided by the source duration.
 */
@Component
@RequiredArgsConstructor
public class FfmpegRunner {

    private static final String OUT_TIME = "out_time_us=";
    private static final String PROGRESS_END = "progress=end";

    private final ToolLocator toolLocator;
    private final NativeProcessRunner nativeProcessRunner;

    public Path ffmpeg() {
        return toolPath(toolLocator.inventory().ffmpeg());
    }

    public Path ffprobe() {
        return toolPath(toolLocator.inventory().ffprobe());
    }

    public boolean hasEncoder(String encoder) {
        return Boolean.TRUE.equals(toolLocator.inventory().encoders().get(encoder));
    }

    /** -/filter_complex on FFmpeg 7.0 or newer, otherwise -filter_complex_script (guide 4.6). */
    public String filterGraphFlag() {
        return toolLocator.inventory().filterGraphFlag();
    }

    /**
     * Runs ffmpeg with the given arguments (everything between "-y" and the output) and renames the result
     * to target. Returns false if the run was cancelled, leaving nothing behind.
     */
    public boolean runToFile(List<String> arguments, Path target, long durationMs, Path logFile,
                             CancellationToken cancellationToken, DoubleConsumer onProgress) {
        Path partial = ClipFiles.partialOf(target);
        if (!runToPartial(arguments, partial, durationMs, logFile, cancellationToken, onProgress)) {
            return false;
        }
        ClipFiles.commit(partial, target);
        return true;
    }

    /**
     * Runs ffmpeg into partial and leaves it there, for a caller that checks the result before renaming it (export,
     * guide 7.4). Returns false if the run was cancelled, leaving nothing behind.
     */
    public boolean runToPartial(List<String> arguments, Path partial, long durationMs, Path logFile,
                                CancellationToken cancellationToken, DoubleConsumer onProgress) {
        ClipFiles.createDirectories(partial.getParent());

        List<String> command = new ArrayList<>(List.of(ffmpeg().toString(), "-nostdin", "-hide_banner", "-y"));
        command.addAll(arguments);
        command.addAll(List.of("-progress", "pipe:1", "-nostats", partial.toString()));

        ProcessResult result;
        try {
            result = nativeProcessRunner.run(new ProcessSpecification(command, null, null,
                    progressListener(durationMs, onProgress), null, logFile), cancellationToken);
        } catch (RuntimeException e) {
            ClipFiles.discard(partial);
            throw e;
        }
        if (result.cancelled()) {
            ClipFiles.discard(partial);
            return false;
        }
        return true;
    }

    static Consumer<String> progressListener(long durationMs, DoubleConsumer onProgress) {
        return line -> {
            if (line.startsWith(OUT_TIME) && durationMs > 0) {
                try {
                    long microseconds = Long.parseLong(line.substring(OUT_TIME.length()).trim());
                    onProgress.accept(Math.clamp(microseconds / 1000.0 / durationMs, 0.0, 1.0));
                } catch (NumberFormatException ignored) {
                    // "N/A" before the first frame.
                }
            } else if (line.equals(PROGRESS_END)) {
                onProgress.accept(1.0);
            }
        };
    }

    private static Path toolPath(ToolInventory.Tool tool) {
        if (tool == null || !tool.available() || tool.path() == null) {
            throw new ToolFailedException(tool == null ? "ffmpeg" : tool.name(), "not found; see the Setup screen", null, List.of());
        }
        return Path.of(tool.path());
    }
}
