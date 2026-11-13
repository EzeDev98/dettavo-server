package com.dettavo.feature.edit;

import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.project.model.Clip;
import com.dettavo.feature.project.model.ClipState;
import com.dettavo.feature.project.model.Edits;
import com.dettavo.feature.project.model.Project;
import com.dettavo.feature.project.model.Trim;
import com.dettavo.feature.project.model.Word;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The edit rules of guide 4.9, numbered 1 to 8. editPlan.js and tokens.js in dettavo-client implement the same rules;
 * a rule change is made in both in the same commit. Export always uses this plan.
 */
@Component
@RequiredArgsConstructor
public class EditPlanner {

    private final ApplicationProperties properties;

    /** One token of a clip: a word, or a pause (the lead-in, a gap between words, or the tail). Clip time. */
    private record Token(String id, boolean word, long startMs, long endMs) {
    }

    /** A cut while it is being planned; merging tiny keep segments changes it. */
    private static final class PlannedCut {
        private long startMs;
        private long endMs;
        private CutKind kind;
        private final List<String> tokenIds;

        private PlannedCut(long startMs, long endMs, CutKind kind, List<String> tokenIds) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.kind = kind;
            this.tokenIds = new ArrayList<>(tokenIds);
        }
    }

    public EditPlan plan(Project project) {
        return plan(project.clips(), project.edits());
    }

    public EditPlan plan(List<Clip> clips, Edits edits) {
        Map<String, Clip> clipsById = clips.stream()
                .collect(Collectors.toMap(Clip::id, Function.identity(), (first, second) -> first));
        Set<String> removed = new HashSet<>(edits.removed());
        Map<String, Integer> trims = new HashMap<>();
        for (Trim trim : edits.trims()) {
            trims.put(trim.pauseId(), trim.keepMs());
        }

        List<Cut> cuts = new ArrayList<>();
        List<KeepSegment> keepSegments = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long timelineMs = 0;
        long editedMs = 0;

        for (String clipId : edits.timeline()) {
            Clip clip = clipsById.get(clipId);
            // Rules 7 and 8: a missing clip is skipped, and a clip counts once.
            if (clip == null || !seen.add(clipId)) {
                continue;
            }
            long durationMs = durationOf(clip);
            // Rule 8: a clip that is not READY has no tokens and is kept whole.
            List<PlannedCut> clipCuts = isReady(clip)
                    ? absorbTinyKeeps(planClipCuts(tokensOf(clip, durationMs), removed, trims, edits.padMs(), durationMs), durationMs)
                    : List.of();

            long cursor = 0;
            for (PlannedCut cut : clipCuts) {
                if (cut.startMs > cursor) {
                    keepSegments.add(new KeepSegment(clipId, cursor, cut.startMs, timelineMs + cursor, editedMs));
                    editedMs += cut.startMs - cursor;
                }
                cuts.add(new Cut(clipId, cut.startMs, cut.endMs, timelineMs + cut.startMs, timelineMs + cut.endMs,
                        cut.kind, cut.tokenIds));
                cursor = cut.endMs;
            }
            if (cursor < durationMs) {
                keepSegments.add(new KeepSegment(clipId, cursor, durationMs, timelineMs + cursor, editedMs));
                editedMs += durationMs - cursor;
            }
            timelineMs += durationMs;
        }

        return new EditPlan(cuts, keepSegments, editedMs, timelineMs - editedMs, timelineMs);
    }

    private static boolean isReady(Clip clip) {
        return clip.state() == ClipState.READY && clip.transcript() != null;
    }

    private static long durationOf(Clip clip) {
        Long durationMs = clip.source() == null ? null : clip.source().durationMs();
        return durationMs == null ? 0 : Math.max(0, durationMs);
    }

    /** The lead-in, then each word and the pause after it; the last pause runs to the clip's end. 2n + 1 tokens. */
    private static List<Token> tokensOf(Clip clip, long durationMs) {
        String clipId = clip.id();
        List<Word> words = clip.transcript().words();
        List<Token> tokens = new ArrayList<>(words.size() * 2 + 1);
        tokens.add(new Token(clipId + "/g:start", false, 0, words.isEmpty() ? durationMs : Math.max(0, words.getFirst().startMs())));
        for (int index = 0; index < words.size(); index++) {
            Word word = words.get(index);
            long nextStartMs = index + 1 < words.size() ? words.get(index + 1).startMs() : durationMs;
            tokens.add(new Token(clipId + "/" + word.id(), true, word.startMs(), word.endMs()));
            tokens.add(new Token(clipId + "/g:" + word.id(), false, word.endMs(), Math.max(word.endMs(), nextStartMs)));
        }
        return tokens;
    }

    /**
     * Rules 2 to 5 for one clip: consecutive removed tokens merge into one cut; a trimmed pause longer than keepMs
     * gets a cut centred in it that never merges; a cut holding a word shrinks by padMs at both ends; cuts are
     * clamped to the clip and dropped when nothing is left of them.
     */
    private static List<PlannedCut> planClipCuts(List<Token> tokens, Set<String> removed, Map<String, Integer> trims,
                                                 int padMs, long durationMs) {
        List<PlannedCut> cuts = new ArrayList<>();
        PlannedCut run = null;
        boolean runHasWord = false;

        for (Token token : tokens) {
            if (removed.contains(token.id())) {
                if (run == null) {
                    run = new PlannedCut(token.startMs(), token.endMs(), CutKind.REMOVE, List.of());
                    runHasWord = false;
                }
                run.endMs = token.endMs();
                runHasWord |= token.word();
                run.tokenIds.add(token.id());
                continue;
            }
            if (run != null) {
                cuts.add(padded(run, runHasWord ? padMs : 0));
                run = null;
            }
            Integer keepMs = token.word() ? null : trims.get(token.id());
            if (keepMs != null && token.endMs() - token.startMs() > keepMs) {
                // keepMs is split between the two sides; an odd millisecond stays after the cut.
                long before = keepMs / 2;
                cuts.add(new PlannedCut(token.startMs() + before, token.endMs() - (keepMs - before), CutKind.TRIM, List.of(token.id())));
            }
        }
        if (run != null) {
            cuts.add(padded(run, runHasWord ? padMs : 0));
        }

        List<PlannedCut> clamped = new ArrayList<>();
        for (PlannedCut cut : cuts) {
            cut.startMs = Math.max(0, cut.startMs);
            cut.endMs = Math.min(durationMs, cut.endMs);
            if (cut.endMs > cut.startMs) {
                clamped.add(cut);
            }
        }
        return clamped;
    }

    private static PlannedCut padded(PlannedCut cut, int padMs) {
        cut.startMs += padMs;
        cut.endMs -= padMs;
        return cut;
    }

    /**
     * Rule 5: a keep segment shorter than app.edit.min-keep-segment-ms is absorbed into the cut next to it. Between
     * two cuts the two become one; at the clip's start or end the cut grows to the edge.
     */
    private List<PlannedCut> absorbTinyKeeps(List<PlannedCut> cuts, long durationMs) {
        int minKeepMs = properties.edit().minKeepSegmentMs();
        List<PlannedCut> sorted = new ArrayList<>(cuts);
        sorted.sort(Comparator.<PlannedCut>comparingLong(cut -> cut.startMs).thenComparingLong(cut -> cut.endMs));

        List<PlannedCut> merged = new ArrayList<>();
        for (PlannedCut cut : sorted) {
            PlannedCut last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && cut.startMs - last.endMs < minKeepMs) {
                last.endMs = Math.max(last.endMs, cut.endMs);
                last.kind = last.kind == CutKind.TRIM && cut.kind == CutKind.TRIM ? CutKind.TRIM : CutKind.REMOVE;
                last.tokenIds.addAll(cut.tokenIds);
            } else {
                merged.add(cut);
            }
        }

        if (!merged.isEmpty()) {
            PlannedCut first = merged.getFirst();
            PlannedCut last = merged.getLast();
            if (first.startMs < minKeepMs) {
                first.startMs = 0;
            }
            if (durationMs - last.endMs < minKeepMs) {
                last.endMs = durationMs;
            }
        }
        return merged;
    }
}
