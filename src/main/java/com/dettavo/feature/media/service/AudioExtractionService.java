package com.dettavo.feature.media.service;

import com.dettavo.common.process.CancellationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;

@Service
@RequiredArgsConstructor
public class AudioExtractionService {

    private final FfmpegRunner ffmpegRunner;

    /** Returns false if cancelled. */
    public boolean extract(Path source, Path target, long durationMs, Path logFile,
                           CancellationToken cancellationToken, DoubleConsumer onProgress) {
        List<String> arguments = List.of(
                "-i", source.toString(),
                "-map", "0:a:0", "-vn",
                "-af", "asetpts=PTS-STARTPTS",
                "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                "-f", "wav");
        return ffmpegRunner.runToFile(arguments, target, durationMs, logFile, cancellationToken, onProgress);
    }
}
