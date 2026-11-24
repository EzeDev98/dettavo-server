package com.dettavo.feature.media.service;

import com.dettavo.common.process.CancellationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ThumbnailService {

    private static final int WIDTH = 320;
    private static final double POSITION = 0.10;
    private static final long LATEST_MS = 5_000;

    private final FfmpegRunner ffmpegRunner;

    /** Returns false if cancelled. */
    public boolean capture(Path source, Path target, long durationMs, Path logFile, CancellationToken cancellationToken) {
        double seconds = Math.min(Math.round(durationMs * POSITION), LATEST_MS) / 1000.0;
        List<String> arguments = List.of(
                "-ss", String.format(Locale.ROOT, "%.3f", seconds),
                "-i", source.toString(),
                "-map", "0:v:0", "-frames:v", "1",
                "-vf", "scale=" + WIDTH + ":-2",
                "-q:v", "4",
                "-f", "image2");
        return ffmpegRunner.runToFile(arguments, target, durationMs, logFile, cancellationToken, fraction -> {
        });
    }
}
