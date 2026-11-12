package com.dettavo.feature.system;

import com.dettavo.common.process.ToolInventory;
import com.dettavo.common.process.ToolLocator;
import com.dettavo.common.security.SessionCookieService;
import com.dettavo.config.ApiPaths;
import com.dettavo.feature.system.dto.SystemStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "System", description = "Tools, encoders, model and pairing state")
public class SystemController {

    private final ToolLocator toolLocator;
    private final SessionCookieService sessionCookieService;

    @GetMapping(ApiPaths.SYSTEM_STATUS)
    @Operation(summary = "System status", description = "Open without pairing. Tool paths and versions, the FFmpeg 7+ flag, encoders, "
            + "the whisper model, what is missing, and whether this browser is paired. refresh=true looks for the tools again.")
    public SystemStatusResponse status(@RequestParam(defaultValue = "false") boolean refresh, HttpServletRequest request) {
        ToolInventory inventory = refresh ? toolLocator.detect() : toolLocator.inventory();
        return SystemStatusResponse.of(sessionCookieService.isPaired(request), inventory);
    }
}
