package com.dettavo.feature.export.service;

import com.dettavo.feature.media.service.FfmpegRunner;
import com.dettavo.feature.export.enums.ExportFormat;
import com.dettavo.feature.export.dto.request.ExportPlan;
import com.dettavo.feature.export.enums.ExportQuality;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ExportEncoder {

    static final String VIDEOTOOLBOX = "h264_videotoolbox";
    private static final double FULL_HD_PIXELS = 1920.0 * 1080.0;
    private static final long STANDARD_BITS_PER_SECOND = 8_000_000;
    private static final long HIGH_BITS_PER_SECOND = 16_000_000;

    private final FfmpegRunner ffmpegRunner;

    public List<String> options(ExportFormat format, ExportQuality quality, ExportPlan.Video video) {
        boolean high = quality == ExportQuality.HIGH;
        List<String> options = new ArrayList<>();
        switch (format) {
            case MP4 -> {
                if (ffmpegRunner.hasEncoder(VIDEOTOOLBOX)) {
                    long base = high ? HIGH_BITS_PER_SECOND : STANDARD_BITS_PER_SECOND;
                    long bitsPerSecond = Math.round(base * (video.width() * (double) video.height()) / FULL_HD_PIXELS);
                    options.addAll(List.of("-c:v", VIDEOTOOLBOX, "-b:v", String.valueOf(bitsPerSecond)));
                } else {
                    options.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", high ? "17" : "20"));
                }
                options.addAll(List.of("-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", high ? "256k" : "192k",
                        "-movflags", "+faststart"));
            }
            case MP3 -> options.addAll(high
                    ? List.of("-c:a", "libmp3lame", "-b:a", "320k")
                    : List.of("-c:a", "libmp3lame", "-q:a", "2"));
            case WAV -> options.addAll(List.of("-c:a", high ? "pcm_s24le" : "pcm_s16le"));
        }
        options.addAll(List.of("-f", format.muxer()));
        return options;
    }
}
