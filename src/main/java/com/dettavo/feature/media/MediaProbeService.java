package com.dettavo.feature.media;

import com.dettavo.common.exception.ToolFailedException;
import com.dettavo.common.exception.UnsupportedMediaException;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.common.process.NativeProcessRunner;
import com.dettavo.common.process.ProcessSpecification;
import com.dettavo.feature.project.model.ClipSource;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a source file with ffprobe (guide 5.2). The first audio stream is required. The first video stream,
 * if any, gives codec, size, frame rate and rotation; a video stream that is an attached picture (cover art)
 * does not count as video.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaProbeService {

    public static final String NO_AUDIO = "This file has no audio track";
    public static final String UNREADABLE = "This file can't be read";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final FfmpegRunner ffmpegRunner;
    private final NativeProcessRunner nativeProcessRunner;
    private final JsonMapper jsonMapper;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeOutput(List<ProbeStream> streams, ProbeFormat format) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeFormat(@JsonProperty("format_name") String formatName, String duration) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProbeStream(@JsonProperty("codec_type") String codecType,
                       @JsonProperty("codec_name") String codecName,
                       Integer width,
                       Integer height,
                       @JsonProperty("avg_frame_rate") String averageFrameRate,
                       @JsonProperty("sample_rate") String sampleRate,
                       Integer channels,
                       Map<String, Integer> disposition,
                       Map<String, String> tags,
                       @JsonProperty("side_data_list") List<Map<String, Object>> sideDataList) {

        boolean isAttachedPicture() {
            return disposition != null && Integer.valueOf(1).equals(disposition.get("attached_pic"));
        }
    }

    /** The source fields from ffprobe; fileName and bytes are left for the caller to keep. */
    public ClipSource probe(Path source, Path logFile, CancellationToken cancellationToken) {
        StringBuilder json = new StringBuilder();
        List<String> command = List.of(ffmpegRunner.ffprobe().toString(), "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", source.toString());
        try {
            nativeProcessRunner.run(new ProcessSpecification(command, null, TIMEOUT,
                    line -> json.append(line).append('\n'), null, logFile), cancellationToken);
        } catch (ToolFailedException e) {
            log.warn("ffprobe could not read {}: {}", source, e.getMessage());
            throw new UnsupportedMediaException(UNREADABLE);
        }

        ProbeOutput output;
        try {
            output = jsonMapper.readValue(json.toString(), ProbeOutput.class);
        } catch (JacksonException e) {
            throw new UnsupportedMediaException(UNREADABLE);
        }
        if (output.format() == null || output.format().duration() == null || output.streams() == null) {
            throw new UnsupportedMediaException(UNREADABLE);
        }

        ProbeStream audio = output.streams().stream()
                .filter(stream -> "audio".equals(stream.codecType()))
                .findFirst()
                .orElseThrow(() -> new UnsupportedMediaException(NO_AUDIO));
        Optional<ProbeStream> video = output.streams().stream()
                .filter(stream -> "video".equals(stream.codecType()) && !stream.isAttachedPicture())
                .findFirst();

        ClipSource.ClipSourceBuilder probed = ClipSource.builder()
                .durationMs(durationMs(output.format().duration()))
                .hasVideo(video.isPresent())
                .container(output.format().formatName())
                .audioCodec(audio.codecName())
                .sampleRate(parseInteger(audio.sampleRate()))
                .channels(audio.channels());
        video.ifPresent(stream -> probed
                .videoCodec(stream.codecName())
                .width(stream.width())
                .height(stream.height())
                .fps(frameRate(stream.averageFrameRate()))
                .rotation(rotation(stream)));
        return probed.build();
    }

    private static long durationMs(String seconds) {
        try {
            return Math.round(Double.parseDouble(seconds) * 1000);
        } catch (NumberFormatException e) {
            throw new UnsupportedMediaException(UNREADABLE);
        }
    }

    /** "30000/1001" -> 29.97; "0/0" -> null. */
    private static Double frameRate(String fraction) {
        if (fraction == null || !fraction.contains("/")) {
            return null;
        }
        String[] parts = fraction.split("/");
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = Double.parseDouble(parts[1]);
            return denominator == 0 ? null : Math.round(numerator / denominator * 1000) / 1000.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The display matrix side data, or the older rotate tag; 0 when neither is there. */
    private static int rotation(ProbeStream stream) {
        if (stream.sideDataList() != null) {
            for (Map<String, Object> sideData : stream.sideDataList()) {
                if (sideData.get("rotation") instanceof Number rotation) {
                    return rotation.intValue();
                }
            }
        }
        if (stream.tags() != null && stream.tags().get("rotate") != null) {
            Integer rotate = parseInteger(stream.tags().get("rotate"));
            return rotate == null ? 0 : rotate;
        }
        return 0;
    }

    private static Integer parseInteger(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
