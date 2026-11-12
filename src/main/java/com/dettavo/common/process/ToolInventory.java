package com.dettavo.common.process;

import java.util.List;
import java.util.Map;

/**
 * What ToolLocator found on this Mac: each tool's path and version, the FFmpeg encoders, the
 * whisper model, and anything missing with the command that installs it.
 *
 * @param ffmpegSevenOrNewer FFmpeg 7.0 or newer reads the export graph with -/filter_complex
 * @param filterGraphFlag    the flag the export uses to read its filter graph file
 * @param encoders           encoder name to whether this FFmpeg has it
 */
public record ToolInventory(Tool ffmpeg,
                            Tool ffprobe,
                            Tool whisperCli,
                            boolean ffmpegSevenOrNewer,
                            String filterGraphFlag,
                            Map<String, Boolean> encoders,
                            Model model,
                            List<MissingItem> missing,
                            String checkedAt) {

    /** @param path absolute path, or null when not found; version and majorVersion null when unknown */
    public record Tool(String name, boolean available, String path, String version, Integer majorVersion) {
    }

    public record Model(String path, boolean present, long sizeBytes) {
    }

    /** @param fix what to run to put it right */
    public record MissingItem(String item, String fix) {
    }
}
