package com.dettavo.common.process;

import com.dettavo.common.exception.ToolFailedException;
import com.dettavo.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds FFmpeg, FFprobe and whisper-cli at startup, reads their versions and FFmpeg's
 * encoders, and checks the whisper model. Missing tools never stop the server; they are
 * listed so the client can show what to install.
 */
@Slf4j
@Component
public class ToolLocator {

    public static final String FFMPEG = "ffmpeg";
    public static final String FFPROBE = "ffprobe";
    public static final String WHISPER_CLI = "whisper-cli";
    public static final List<String> REQUIRED_ENCODERS = List.of("h264_videotoolbox", "libx264", "libmp3lame");

    private static final int FFMPEG_MINIMUM_MAJOR = 7;
    private static final String GRAPH_FLAG_FROM_FILE = "-/filter_complex";
    private static final String GRAPH_FLAG_LEGACY = "-filter_complex_script";
    private static final String INSTALL_FIX = "Run scripts/install-tools.sh in dettavo-server, then restart the server";
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern FFMPEG_VERSION = Pattern.compile("^ff(?:mpeg|probe) version n?(\\S+)");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+)");
    private static final Pattern WHISPER_VERSION = Pattern.compile("(?i)version\\D{0,3}v?(\\d+\\.\\d+(?:\\.\\d+)?)");

    private final NativeProcessRunner nativeProcessRunner;
    private final ExecutablePathResolver executablePathResolver;
    private final ApplicationProperties.Tools tools;

    private volatile ToolInventory inventory;

    public ToolLocator(NativeProcessRunner nativeProcessRunner,
                       ExecutablePathResolver executablePathResolver,
                       ApplicationProperties properties) {
        this.nativeProcessRunner = nativeProcessRunner;
        this.executablePathResolver = executablePathResolver;
        this.tools = properties.tools();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void detectAtStartup() {
        ToolInventory found = detect();
        if (found.missing().isEmpty()) {
            log.info("Tools ready: ffmpeg {}, ffprobe {}, whisper-cli at {}, model {}",
                    found.ffmpeg().version(), found.ffprobe().version(), found.whisperCli().path(), found.model().path());
        } else {
            found.missing().forEach(item -> log.warn("Missing: {}. {}", item.item(), item.fix()));
        }
    }

    /** The last detection, running one first if none has run yet. */
    public ToolInventory inventory() {
        ToolInventory current = inventory;
        return current != null ? current : detect();
    }

    /** Looks for every tool again, for example after running install-tools.sh. */
    public synchronized ToolInventory detect() {
        List<ToolInventory.MissingItem> missing = new ArrayList<>();

        ToolInventory.Tool ffmpeg = ffmpegFamilyTool(FFMPEG, tools.ffmpeg(), missing);
        ToolInventory.Tool ffprobe = ffmpegFamilyTool(FFPROBE, tools.ffprobe(), missing);
        ToolInventory.Tool whisperCli = whisperTool(missing);

        boolean sevenOrNewer = ffmpeg.majorVersion() != null && ffmpeg.majorVersion() >= FFMPEG_MINIMUM_MAJOR;
        if (ffmpeg.available() && !sevenOrNewer) {
            missing.add(new ToolInventory.MissingItem("FFmpeg " + FFMPEG_MINIMUM_MAJOR + " or newer (found " + ffmpeg.version() + ")", INSTALL_FIX));
        }

        Map<String, Boolean> encoders = encoders(ffmpeg);
        encoders.forEach((name, present) -> {
            if (ffmpeg.available() && !present) {
                missing.add(new ToolInventory.MissingItem("FFmpeg encoder " + name, INSTALL_FIX));
            }
        });

        ToolInventory.Model model = model(missing);

        ToolInventory found = new ToolInventory(ffmpeg, ffprobe, whisperCli, sevenOrNewer,
                sevenOrNewer ? GRAPH_FLAG_FROM_FILE : GRAPH_FLAG_LEGACY,
                encoders, model, List.copyOf(missing), Instant.now().toString());
        inventory = found;
        return found;
    }

    private ToolInventory.Tool ffmpegFamilyTool(String name, String configuredPath, List<ToolInventory.MissingItem> missing) {
        Optional<Path> path = executablePathResolver.resolve(configuredPath, name);
        if (path.isEmpty()) {
            missing.add(new ToolInventory.MissingItem(name, INSTALL_FIX));
            return new ToolInventory.Tool(name, false, null, null, null);
        }
        try {
            List<String> output = run(List.of(path.get().toString(), "-version"));
            String version = output.stream()
                    .map(FFMPEG_VERSION::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .findFirst()
                    .orElse(null);
            return new ToolInventory.Tool(name, true, path.get().toString(), version, majorOf(version));
        } catch (ToolFailedException e) {
            log.warn("{} -version failed: {}", name, e.getMessage());
            missing.add(new ToolInventory.MissingItem(name + " (found at " + path.get() + " but it does not run)", INSTALL_FIX));
            return new ToolInventory.Tool(name, false, path.get().toString(), null, null);
        }
    }

    private ToolInventory.Tool whisperTool(List<ToolInventory.MissingItem> missing) {
        Optional<Path> path = executablePathResolver.resolve(tools.whisperCli(), WHISPER_CLI);
        if (path.isEmpty()) {
            missing.add(new ToolInventory.MissingItem(WHISPER_CLI, INSTALL_FIX));
            return new ToolInventory.Tool(WHISPER_CLI, false, null, null, null);
        }
        try {
            List<String> output = run(List.of(path.get().toString(), "--help"));
            String version = output.stream()
                    .map(WHISPER_VERSION::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .findFirst()
                    .orElse(null);
            return new ToolInventory.Tool(WHISPER_CLI, true, path.get().toString(), version, majorOf(version));
        } catch (ToolFailedException e) {
            log.warn("whisper-cli --help failed: {}", e.getMessage());
            missing.add(new ToolInventory.MissingItem(WHISPER_CLI + " (found at " + path.get() + " but it does not run)", INSTALL_FIX));
            return new ToolInventory.Tool(WHISPER_CLI, false, path.get().toString(), null, null);
        }
    }

    private Map<String, Boolean> encoders(ToolInventory.Tool ffmpeg) {
        Map<String, Boolean> encoders = new LinkedHashMap<>();
        REQUIRED_ENCODERS.forEach(name -> encoders.put(name, false));
        if (!ffmpeg.available()) {
            return encoders;
        }
        try {
            for (String line : run(List.of(ffmpeg.path(), "-hide_banner", "-encoders"))) {
                String[] columns = line.trim().split("\\s+");
                if (columns.length >= 2 && encoders.containsKey(columns[1])) {
                    encoders.put(columns[1], true);
                }
            }
        } catch (ToolFailedException e) {
            log.warn("ffmpeg -encoders failed: {}", e.getMessage());
        }
        return encoders;
    }

    private ToolInventory.Model model(List<ToolInventory.MissingItem> missing) {
        Path path = tools.whisperModel().toAbsolutePath().normalize();
        long size = 0;
        boolean present = Files.isRegularFile(path);
        if (present) {
            try {
                size = Files.size(path);
            } catch (IOException e) {
                present = false;
            }
        }
        if (!present) {
            missing.add(new ToolInventory.MissingItem("Whisper model " + path, INSTALL_FIX));
        }
        return new ToolInventory.Model(path.toString(), present, size);
    }

    /** Runs a short check and returns standard output and standard error together. */
    private List<String> run(List<String> command) {
        List<String> lines = new ArrayList<>();
        nativeProcessRunner.run(new ProcessSpecification(command, null, CHECK_TIMEOUT,
                line -> {
                    synchronized (lines) {
                        lines.add(line);
                    }
                },
                line -> {
                    synchronized (lines) {
                        lines.add(line);
                    }
                }, null), CancellationToken.none());
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    private static Integer majorOf(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = LEADING_NUMBER.matcher(version);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
