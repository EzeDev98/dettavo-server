package com.dettavo.feature.system.web;

import com.dettavo.common.process.ToolInventory;
import com.dettavo.common.process.ToolLocator;
import com.dettavo.config.ApiPaths;
import com.dettavo.feature.system.dto.SystemStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "System", description = "Tools, encoders and model")
public class SystemController {

    private final ToolLocator toolLocator;

    @GetMapping(ApiPaths.SYSTEM_STATUS)
    @Operation(summary = "System status", description = "Tool paths and versions, the FFmpeg 7+ flag, encoders, "
            + "the whisper model and what is missing. refresh=true looks for the tools again.")
    public SystemStatusResponse status(@RequestParam(defaultValue = "false") boolean refresh) {
        ToolInventory inventory = refresh ? toolLocator.detect() : toolLocator.inventory();
        return SystemStatusResponse.of(inventory);
    }
}
