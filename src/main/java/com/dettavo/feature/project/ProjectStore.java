package com.dettavo.feature.project;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.common.exception.ProjectNotFoundException;
import com.dettavo.common.file.AtomicFileWriter;
import com.dettavo.common.file.WorkspacePaths;
import com.dettavo.feature.project.model.Clip;
import com.dettavo.feature.project.model.Edits;
import com.dettavo.feature.project.model.Project;
import com.dettavo.feature.project.model.TokenIds;
import com.dettavo.feature.project.model.Trim;
import com.dettavo.feature.project.model.Word;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads and writes projects/<id>/project.json (guide 4.4). Every change to one project goes through
 * {@link #update} under that project's lock, and every write is atomic and keeps the previous file as
 * project.json.bak.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectStore {

    public static final String PROJECT_FILE = "project.json";
    public static final String CLIPS_FOLDER = "clips";

    private static final Pattern CLIP_ID = Pattern.compile("c\\d{2,}");

    private final WorkspacePaths workspacePaths;
    private final AtomicFileWriter atomicFileWriter;
    private final JsonMapper jsonMapper;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Times in project.json are kept to the second. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** Writes a new project's folder and project.json. */
    public Project create(Project project) {
        return withLock(project.id(), () -> {
            createDirectories(workspacePaths.projectDirectory(project.id()));
            write(project);
            return project;
        });
    }

    /** Reads one project, without edits that point at clips or words that no longer exist (rules 7 and 8). */
    public Project load(UUID projectId) {
        Path file = projectFile(projectId);
        if (!Files.isRegularFile(file)) {
            throw new ProjectNotFoundException(projectId);
        }
        return withoutOrphans(read(file));
    }

    /** Every project with a readable project.json. A project that cannot be read is skipped and logged. */
    public List<Project> list() {
        List<Project> projects = new ArrayList<>();
        List<Path> folders;
        try (Stream<Path> entries = Files.list(workspacePaths.projectsDirectory())) {
            folders = entries.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + workspacePaths.projectsDirectory(), e);
        }
        for (Path folder : folders) {
            Optional<UUID> projectId = parseId(folder.getFileName().toString());
            if (projectId.isEmpty() || !Files.isRegularFile(projectFile(projectId.get()))) {
                continue;
            }
            try {
                projects.add(load(projectId.get()));
            } catch (RuntimeException e) {
                log.warn("Skipping project {}: {}", projectId.get(), e.getMessage());
            }
        }
        return projects;
    }

    /** Loads, changes and saves one project under its lock, and sets updatedAt. Nothing is written if change throws. */
    public Project update(UUID projectId, UnaryOperator<Project> change) {
        return withLock(projectId, () -> {
            Project changed = change.apply(load(projectId)).toBuilder().updatedAt(now()).build();
            write(changed);
            return changed;
        });
    }

    /** Deletes the project's whole folder: project.json, clips and exports. */
    public void delete(UUID projectId) {
        withLock(projectId, () -> {
            if (!Files.isRegularFile(projectFile(projectId))) {
                throw new ProjectNotFoundException(projectId);
            }
            deleteRecursively(workspacePaths.projectDirectory(projectId));
            return null;
        });
    }

    /** Changes one clip under the project's lock and returns it as saved. 404 if the clip does not exist. */
    public Clip updateClip(UUID projectId, String clipId, UnaryOperator<Clip> change) {
        Project updated = update(projectId, project -> {
            Clip current = project.findClip(clipId).orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
            Clip changed = change.apply(current);
            return project.toBuilder()
                    .clips(project.clips().stream().map(clip -> clip.id().equals(clipId) ? changed : clip).toList())
                    .build();
        });
        return updated.findClip(clipId).orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
    }

    /** The folder of one clip, projects/<id>/clips/<clipId>. Refuses anything that is not a clip id. */
    public Path clipDirectory(UUID projectId, String clipId) {
        if (clipId == null || !CLIP_ID.matcher(clipId).matches()) {
            throw new IllegalArgumentException("Not a clip id: " + clipId);
        }
        return workspacePaths.resolveInside(workspacePaths.projectDirectory(projectId), CLIPS_FOLDER, clipId);
    }

    public Path createClipDirectory(UUID projectId, String clipId) {
        Path folder = clipDirectory(projectId, clipId);
        createDirectories(folder);
        return folder;
    }

    public void deleteRecursively(Path path) {
        try {
            FileSystemUtils.deleteRecursively(workspacePaths.requireInsideWorkspace(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + path, e);
        }
    }

    private Path projectFile(UUID projectId) {
        return workspacePaths.resolveInside(workspacePaths.projectDirectory(projectId), PROJECT_FILE);
    }

    private Project read(Path file) {
        try {
            return jsonMapper.readValue(Files.readAllBytes(file), Project.class);
        } catch (IOException | JacksonException e) {
            Path backup = file.resolveSibling(file.getFileName() + AtomicFileWriter.BACKUP_SUFFIX);
            log.error("Cannot read {} ({}); trying {}", file, e.getMessage(), backup);
            try {
                return jsonMapper.readValue(Files.readAllBytes(backup), Project.class);
            } catch (IOException | JacksonException backupFailure) {
                throw new IllegalStateException("Cannot read " + file + " or its backup", e);
            }
        }
    }

    private void write(Project project) {
        byte[] json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(project);
        atomicFileWriter.write(projectFile(project.id()), json, true);
    }

    private <T> T withLock(UUID projectId, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, id -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static void createDirectories(Path folder) {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + folder, e);
        }
    }

    private static Optional<UUID> parseId(String folderName) {
        try {
            UUID projectId = UUID.fromString(folderName);
            return projectId.toString().equals(folderName) ? Optional.of(projectId) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Rule 7: removed ids and trims whose clip or word no longer exists are dropped, and so are timeline
     * entries for missing clips. Rule 8: each clip appears on the timeline at most once. The edits of a
     * clip that is only in the tray are kept.
     */
    static Project withoutOrphans(Project project) {
        Map<String, Clip> clips = project.clips().stream()
                .collect(Collectors.toMap(Clip::id, Function.identity(), (first, second) -> first));
        Map<String, Set<String>> wordIdsByClip = new HashMap<>();
        Predicate<String> exists = tokenId -> TokenIds.parse(tokenId)
                .filter(token -> clips.containsKey(token.clipId()))
                .filter(token -> token.wordId() == null || wordIdsByClip
                        .computeIfAbsent(token.clipId(), clipId -> wordIdsOf(clips.get(clipId)))
                        .contains(token.wordId()))
                .isPresent();
        Predicate<String> isPause = tokenId -> TokenIds.parse(tokenId).map(TokenIds.TokenId::pause).orElse(false);

        Edits edits = project.edits();
        List<String> timeline = edits.timeline().stream().filter(clips::containsKey).distinct().toList();
        List<String> removed = edits.removed().stream().filter(exists).distinct().toList();
        List<Trim> trims = edits.trims().stream()
                .filter(trim -> isPause.test(trim.pauseId()) && exists.test(trim.pauseId()))
                .toList();

        if (timeline.equals(edits.timeline()) && removed.equals(edits.removed()) && trims.equals(edits.trims())) {
            return project;
        }
        log.info("Project {}: dropped edits that point at missing clips or words", project.id());
        return project.toBuilder()
                .edits(edits.toBuilder().timeline(timeline).removed(removed).trims(trims).build())
                .build();
    }

    private static Set<String> wordIdsOf(Clip clip) {
        if (clip == null || clip.transcript() == null) {
            return Set.of();
        }
        return clip.transcript().words().stream().map(Word::id).collect(Collectors.toSet());
    }
}
