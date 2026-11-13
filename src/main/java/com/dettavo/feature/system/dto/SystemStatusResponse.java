package com.dettavo.feature.system.dto;

import com.dettavo.common.process.ToolInventory;

import java.util.List;
import java.util.Map;

/**
 * The answer to GET /system/status: every tool, encoder and model the server needs, with
 * anything missing listed with its fix. ready is true when nothing is missing.
 */
public record SystemStatusResponse(boolean ready,
                                   ToolInventory.Tool ffmpeg,
                                   ToolInventory.Tool ffprobe,
                                   ToolInventory.Tool whisperCli,
                                   boolean ffmpegSevenOrNewer,
                                   String filterGraphFlag,
                                   Map<String, Boolean> encoders,
                                   ToolInventory.Model model,
                                   List<ToolInventory.MissingItem> missing,
                                   String checkedAt) {

    public static SystemStatusResponse of(ToolInventory inventory) {
        return new SystemStatusResponse(inventory.missing().isEmpty(),
                inventory.ffmpeg(), inventory.ffprobe(), inventory.whisperCli(),
                inventory.ffmpegSevenOrNewer(), inventory.filterGraphFlag(), inventory.encoders(),
                inventory.model(), inventory.missing(), inventory.checkedAt());
    }
}
