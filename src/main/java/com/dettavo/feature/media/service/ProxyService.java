package com.dettavo.feature.media.service;

import com.dettavo.common.process.CancellationToken;
import com.dettavo.config.ApplicationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

@Service
@RequiredArgsConstructor
public class ProxyService {

    static final String VIDEOTOOLBOX = "h264_videotoolbox";
    private static final String KEYFRAME_INTERVAL = "30";

    private final FfmpegRunner ffmpegRunner;
    private final ApplicationProperties properties;

    /** Returns false if cancelled. */
    public boolean build(Path source, Path target, boolean hasVideo, long durationMs, Path logFile,
                         CancellationToken cancellationToken, DoubleConsumer onProgress) {
        List<String> arguments = new ArrayList<>(List.of("-i", source.toString()));
        if (hasVideo) {
            int height = properties.proxy().height();
            arguments.addAll(List.of(
                    "-map", "0:v:0", "-map", "0:a:0",
                    "-vf", "setpts=PTS-STARTPTS,scale=-2:'min(" + height + ",ih)'",
                    "-af", "asetpts=PTS-STARTPTS",
                    "-fps_mode", "passthrough",
                    "-pix_fmt", "yuv420p"));
            arguments.addAll(videoEncoder());
            arguments.addAll(List.of("-g", KEYFRAME_INTERVAL, "-keyint_min", KEYFRAME_INTERVAL));
        } else {
            arguments.addAll(List.of("-map", "0:a:0", "-af", "asetpts=PTS-STARTPTS"));
        }
        arguments.addAll(List.of(
                "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-ar", "48000",
                "-movflags", "+faststart",
                "-f", "mp4"));
        return ffmpegRunner.runToFile(arguments, target, durationMs, logFile, cancellationToken, onProgress);
    }

    private List<String> videoEncoder() {
        if (ffmpegRunner.hasEncoder(VIDEOTOOLBOX)) {
            return List.of("-c:v", VIDEOTOOLBOX, "-b:v", "2500k");
        }
        return List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "26");
    }
}
