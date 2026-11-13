package com.dettavo.common.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * On startup, deletes the *.tmp, *.partial and *.filtergraph files an interrupted save, upload or export left in
 * project folders (guide 4.4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceCleanup {

    public static final String PARTIAL_SUFFIX = ".partial";
    public static final String FILTER_GRAPH_SUFFIX = ".filtergraph";

    private final WorkspacePaths workspacePaths;

    @EventListener(ApplicationStartedEvent.class)
    public void removeLeftovers() {
        List<Path> leftovers;
        try (Stream<Path> files = Files.walk(workspacePaths.projectsDirectory())) {
            leftovers = files.filter(Files::isRegularFile).filter(WorkspaceCleanup::isLeftover).toList();
        } catch (IOException e) {
            log.warn("Could not look for leftover files in {}: {}", workspacePaths.projectsDirectory(), e.getMessage());
            return;
        }
        for (Path file : leftovers) {
            try {
                Files.deleteIfExists(file);
                log.info("Removed leftover {}", file);
            } catch (IOException e) {
                log.warn("Could not remove leftover {}: {}", file, e.getMessage());
            }
        }
    }

    private static boolean isLeftover(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(AtomicFileWriter.TEMPORARY_SUFFIX) || name.endsWith(PARTIAL_SUFFIX) || name.endsWith(FILTER_GRAPH_SUFFIX);
    }
}
