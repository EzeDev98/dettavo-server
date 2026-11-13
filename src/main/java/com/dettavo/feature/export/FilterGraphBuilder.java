package com.dettavo.feature.export;

import com.dettavo.config.ApplicationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Writes an export's filter graph (guide 7.2, Build Plan 17b). For every keep segment: the video brought to the output
 * frame rate first (which also fixes variable frame rate), trimmed, fitted inside the output frame with black bars and
 * never cropped; the audio trimmed, resampled to 48 kHz stereo and faded 10 ms at each join, but not at the export's
 * own start and end. An audio clip in an MP4 gets black video. Everything is concatenated in timeline order.
 * Numbers use a dot and 3 decimals whatever the server's locale.
 */
@Component
@RequiredArgsConstructor
public class FilterGraphBuilder {

    private static final int SAMPLE_RATE = 48_000;

    private final ApplicationProperties properties;

    public String build(ExportPlan plan) {
        ExportPlan.Video video = plan.video();
        double fadeSeconds = properties.export().audioFadeMs() / 1000.0;
        List<ExportPlan.Segment> segments = plan.segments();
        int last = segments.size() - 1;
        StringBuilder graph = new StringBuilder();
        StringBuilder concatInputs = new StringBuilder();

        for (int index = 0; index <= last; index++) {
            ExportPlan.Segment segment = segments.get(index);
            ExportPlan.Input input = plan.inputs().get(segment.inputIndex());
            String start = number(segment.startSeconds());
            String end = number(segment.endSeconds());
            double duration = segment.durationSeconds();

            if (video != null) {
                String size = video.width() + ":" + video.height();
                if (input.hasVideo()) {
                    graph.append('[').append(segment.inputIndex()).append(":v:0]")
                            .append("fps=").append(number(video.framesPerSecond()))
                            .append(",trim=start=").append(start).append(":end=").append(end)
                            .append(",setpts=PTS-STARTPTS")
                            .append(",scale=").append(size).append(":force_original_aspect_ratio=decrease")
                            .append(",pad=").append(size).append(":(ow-iw)/2:(oh-ih)/2")
                            .append(",setsar=1,format=yuv420p");
                } else {
                    graph.append("color=c=black:s=").append(video.width()).append('x').append(video.height())
                            .append(":r=").append(number(video.framesPerSecond()))
                            .append(":d=").append(number(duration))
                            .append(",format=yuv420p,setsar=1");
                }
                graph.append("[v").append(index).append("];\n");
                concatInputs.append("[v").append(index).append(']');
            }

            // A segment shorter than two fades gets two halves.
            double fade = Math.min(fadeSeconds, duration / 2);
            graph.append('[').append(segment.inputIndex()).append(":a:0]")
                    .append("atrim=start=").append(start).append(":end=").append(end)
                    .append(",asetpts=PTS-STARTPTS")
                    .append(",aresample=").append(SAMPLE_RATE)
                    .append(",aformat=channel_layouts=stereo");
            if (index > 0) {
                graph.append(",afade=t=in:st=0:d=").append(number(fade));
            }
            if (index < last) {
                graph.append(",afade=t=out:st=").append(number(duration - fade)).append(":d=").append(number(fade));
            }
            graph.append("[a").append(index).append("];\n");
            concatInputs.append("[a").append(index).append(']');
        }

        graph.append(concatInputs).append("concat=n=").append(segments.size())
                .append(":v=").append(video != null ? 1 : 0).append(":a=1")
                .append(video != null ? "[vout][aout]" : "[aout]")
                .append('\n');
        return graph.toString();
    }

    static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
