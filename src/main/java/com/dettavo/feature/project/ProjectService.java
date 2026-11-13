package com.dettavo.feature.project;

import com.dettavo.common.exception.RevisionConflictException;
import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.project.dto.ProjectSummaryResponse;
import com.dettavo.feature.project.dto.SaveEditsRequest;
import com.dettavo.feature.project.model.Clip;
import com.dettavo.feature.project.model.Edits;
import com.dettavo.feature.project.model.Project;
import com.dettavo.feature.project.model.TokenIds;
import com.dettavo.feature.project.model.Trim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectStore projectStore;
    private final ApplicationProperties properties;

    /** Home page cards, most recently changed first. */
    public List<ProjectSummaryResponse> list() {
        return projectStore.list().stream()
                .map(ProjectSummaryResponse::from)
                .sorted(Comparator.comparing(ProjectSummaryResponse::updatedAt).reversed())
                .toList();
    }

    /** An empty project: no clips, nothing on the timeline, the default padding. */
    public Project create(String name) {
        Instant now = ProjectStore.now();
        Project project = Project.builder()
                .schemaVersion(Project.SCHEMA_VERSION)
                .id(UUID.randomUUID())
                .name(name.strip())
                .createdAt(now)
                .updatedAt(now)
                .nextClipNumber(1)
                .clips(List.of())
                .edits(Edits.initial(properties.edit().defaultPaddingMs()))
                .jobs(List.of())
                .build();
        log.info("Created project {} ({})", project.id(), project.name());
        return projectStore.create(project);
    }

    public Project get(UUID projectId) {
        return projectStore.load(projectId);
    }

    public Project rename(UUID projectId, String name) {
        return projectStore.update(projectId, project -> project.toBuilder().name(name.strip()).build());
    }

    public void delete(UUID projectId) {
        projectStore.delete(projectId);
        log.info("Deleted project {}", projectId);
    }

    /** Saves the whole edit state if expectedRevision is still the latest, and returns the project at revision + 1. */
    public Project saveEdits(UUID projectId, long expectedRevision, SaveEditsRequest request) {
        return projectStore.update(projectId, project -> {
            long currentRevision = project.edits().revision();
            if (currentRevision != expectedRevision) {
                throw new RevisionConflictException(expectedRevision, currentRevision);
            }
            return project.toBuilder().edits(validatedEdits(project, request, currentRevision + 1)).build();
        });
    }

    /**
     * Refuses a save that names a clip not in the project, puts a clip on the timeline twice, holds a
     * malformed token id, trims something that is not a pause, or both removes and trims one pause (rule 3).
     */
    private static Edits validatedEdits(Project project, SaveEditsRequest request, long revision) {
        Set<String> clipIds = project.clips().stream().map(Clip::id).collect(Collectors.toSet());

        Set<String> onTimeline = new HashSet<>();
        for (String clipId : request.timeline()) {
            if (!clipIds.contains(clipId)) {
                throw new IllegalArgumentException("The timeline names clip " + clipId + ", which is not in this project");
            }
            if (!onTimeline.add(clipId)) {
                throw new IllegalArgumentException("Clip " + clipId + " is on the timeline twice; each clip may appear once");
            }
        }

        Set<String> removed = new LinkedHashSet<>();
        for (String tokenId : request.removed()) {
            requireToken(tokenId, clipIds, false);
            removed.add(tokenId);
        }

        Set<String> trimmed = new HashSet<>();
        for (Trim trim : request.trims()) {
            requireToken(trim.pauseId(), clipIds, true);
            if (!trimmed.add(trim.pauseId())) {
                throw new IllegalArgumentException("Pause " + trim.pauseId() + " is trimmed twice");
            }
            if (removed.contains(trim.pauseId())) {
                throw new IllegalArgumentException("Pause " + trim.pauseId() + " is both removed and trimmed; it can be only one");
            }
        }

        return new Edits(revision, request.timeline(), List.copyOf(removed), request.trims(), request.padMs());
    }

    private static void requireToken(String tokenId, Set<String> clipIds, boolean pauseOnly) {
        TokenIds.TokenId token = TokenIds.parse(tokenId)
                .orElseThrow(() -> new IllegalArgumentException("Not a token id: " + tokenId));
        if (!clipIds.contains(token.clipId())) {
            throw new IllegalArgumentException("Token " + tokenId + " names clip " + token.clipId() + ", which is not in this project");
        }
        if (pauseOnly && !token.pause()) {
            throw new IllegalArgumentException("Only pauses can be trimmed, not " + tokenId);
        }
    }
}
