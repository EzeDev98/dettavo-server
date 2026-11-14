package com.dettavo.feature.project;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.common.exception.InsufficientDiskSpaceException;
import com.dettavo.common.file.WorkspacePaths;
import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.job.JobService;
import com.dettavo.feature.project.dto.AddClipRequest;
import com.dettavo.feature.project.model.Clip;
import com.dettavo.feature.project.model.ClipSource;
import com.dettavo.feature.project.model.ClipState;
import com.dettavo.feature.project.model.Edits;
import com.dettavo.feature.project.model.Project;
import com.dettavo.feature.project.model.RangeCut;
import com.dettavo.feature.project.model.SplitPoint;
import com.dettavo.feature.project.model.Trim;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClipService {

    /** Room for the proxy and a share of the export on top of the file itself (guide 5.1). */
    private static final double SPACE_FACTOR = 1.5;

    private final ProjectStore projectStore;
    private final WorkspacePaths workspacePaths;
    private final ApplicationProperties properties;
    private final JobService jobService;

    /** Adds an EMPTY clip with the next id, ready for its upload. It goes to the tray, not the timeline. */
    public Clip add(UUID projectId, AddClipRequest request) {
        requireDiskSpace(request.sizeBytes());
        Project updated = projectStore.update(projectId, project -> {
            String clipId = Clip.idFor(project.nextClipNumber());
            projectStore.createClipDirectory(projectId, clipId);
            Clip clip = Clip.builder()
                    .id(clipId)
                    .state(ClipState.EMPTY)
                    .addedAt(ProjectStore.now())
                    .source(ClipSource.builder().fileName(request.fileName().strip()).bytes(request.sizeBytes()).build())
                    .build();
            List<Clip> clips = new ArrayList<>(project.clips());
            clips.add(clip);
            return project.toBuilder().nextClipNumber(project.nextClipNumber() + 1).clips(clips).build();
        });
        Clip added = updated.clips().getLast();
        log.info("Project {}: added clip {} ({}, {} bytes)", projectId, added.id(), request.fileName(), request.sizeBytes());
        return added;
    }

    /**
     * Cancels the clip's jobs, removes the clip, its timeline entry and its edits, then deletes its folder. Returns
     * the saved project.
     */
    public Project delete(UUID projectId, String clipId) {
        jobService.cancelClipJobs(projectId, clipId);
        Project updated = projectStore.update(projectId, project -> {
            project.findClip(clipId).orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
            return project.toBuilder()
                    .clips(project.clips().stream().filter(clip -> !clip.id().equals(clipId)).toList())
                    .edits(withoutClip(project.edits(), clipId))
                    .build();
        });
        projectStore.deleteRecursively(projectStore.clipDirectory(projectId, clipId));
        log.info("Project {}: deleted clip {}", projectId, clipId);
        return updated;
    }

    private static Edits withoutClip(Edits edits, String clipId) {
        String prefix = clipId + "/";
        List<String> timeline = edits.timeline().stream().filter(id -> !id.equals(clipId)).toList();
        List<String> removed = edits.removed().stream().filter(id -> !id.startsWith(prefix)).toList();
        List<Trim> trims = edits.trims().stream().filter(trim -> !trim.pauseId().startsWith(prefix)).toList();
        List<RangeCut> rangeCuts = edits.rangeCuts().stream().filter(rangeCut -> !rangeCut.clipId().equals(clipId)).toList();
        List<SplitPoint> splits = edits.splits().stream().filter(split -> !split.clipId().equals(clipId)).toList();
        boolean changed = timeline.size() != edits.timeline().size()
                || removed.size() != edits.removed().size()
                || trims.size() != edits.trims().size()
                || rangeCuts.size() != edits.rangeCuts().size()
                || splits.size() != edits.splits().size();
        return changed
                ? new Edits(edits.revision() + 1, timeline, removed, trims, edits.padMs(), rangeCuts, splits)
                : edits;
    }

    private void requireDiskSpace(long sizeBytes) {
        long requiredBytes = (long) Math.ceil(sizeBytes * SPACE_FACTOR) + properties.disk().reserveBytes();
        long availableBytes;
        try {
            availableBytes = Files.getFileStore(workspacePaths.root()).getUsableSpace();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the free space of " + workspacePaths.root(), e);
        }
        if (availableBytes < requiredBytes) {
            throw new InsufficientDiskSpaceException(requiredBytes, availableBytes);
        }
    }
}
