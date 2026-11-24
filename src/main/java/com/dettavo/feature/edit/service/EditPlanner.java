package com.dettavo.feature.edit.service;

import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.edit.dto.KeepSegment;
import com.dettavo.feature.edit.dto.Cut;
import com.dettavo.feature.edit.dto.EditPlan;
import com.dettavo.feature.edit.enums.CutKind;
import com.dettavo.feature.project.dto.Clip;
import com.dettavo.feature.project.enums.ClipState;
import com.dettavo.feature.project.dto.Edits;
import com.dettavo.feature.project.dto.Project;
import com.dettavo.feature.project.dto.RangeCut;
import com.dettavo.feature.project.dto.Trim;
import com.dettavo.feature.project.dto.Word;
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

@Component
@RequiredArgsConstructor
public class EditPlanner {

    private final ApplicationProperties properties;

    /** One token of a clip: a word, or a pause (the lead-in, a gap between words, or the tail). Clip time. */
    private record Token(String id, boolean word, long startMs, long endMs) {
    }

    private static final class PlannedCut {
        private long startMs;
        private long endMs;
        private CutKind kind;
        private final List<String> tokenIds;
        private final List<String> rangeCutIds;

        private PlannedCut(long startMs, long endMs, CutKind kind, List<String> tokenIds) {
            this(startMs, endMs, kind, tokenIds, List.of());
        }

        private PlannedCut(long startMs, long endMs, CutKind kind, List<String> tokenIds, List<String> rangeCutIds) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.kind = kind;
            this.tokenIds = new ArrayList<>(tokenIds);
            this.rangeCutIds = new ArrayList<>(rangeCutIds);
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
        Map<String, List<RangeCut>> rangeCutsByClip = edits.rangeCuts().stream()
                .collect(Collectors.groupingBy(RangeCut::clipId));

        List<Cut> cuts = new ArrayList<>();
        List<KeepSegment> keepSegments = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        long timelineMs = 0;
        long editedMs = 0;

        for (String clipId : edits.timeline()) {
            Clip clip = clipsById.get(clipId);
            if (clip == null || !seen.add(clipId)) {
                continue;
            }
            long durationMs = durationOf(clip);
            List<Token> tokens = isReady(clip) ? tokensOf(clip, durationMs) : List.of();
            List<PlannedCut> planned = new ArrayList<>(planClipCuts(tokens, removed, trims, edits.padMs(), durationMs));
            planned.addAll(rangeCutsOf(rangeCutsByClip.getOrDefault(clipId, List.of()), tokens, durationMs));
            List<PlannedCut> clipCuts = absorbTinyKeeps(planned, durationMs);

            long cursor = 0;
            for (PlannedCut cut : clipCuts) {
                if (cut.startMs > cursor) {
                    keepSegments.add(new KeepSegment(clipId, cursor, cut.startMs, timelineMs + cursor, editedMs));
                    editedMs += cut.startMs - cursor;
                }
                cuts.add(new Cut(clipId, cut.startMs, cut.endMs, timelineMs + cut.startMs, timelineMs + cut.endMs,
                        cut.kind, cut.tokenIds, cut.rangeCutIds));
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

    private static List<PlannedCut> planClipCuts(List<Token> tokens, Set<String> removed, Map<String, Integer> trims,
                                                 int padMs, long durationMs) {
        List<PlannedCut> cuts = new ArrayList<>();
        PlannedCut run = null;
        boolean runHasWord = false;
        boolean firstIsWord = false;
        boolean lastIsWord = false;
        Token before = null;
        Token after = null;

        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (isRemoved(tokens, index, removed)) {
                if (run == null) {
                    run = new PlannedCut(token.startMs(), token.endMs(), CutKind.REMOVE, List.of());
                    runHasWord = false;
                    firstIsWord = token.word();
                    before = index > 0 ? tokens.get(index - 1) : null;
                }
                run.endMs = token.endMs();
                runHasWord |= token.word();
                lastIsWord = token.word();
                after = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
                run.tokenIds.add(token.id());
                continue;
            }
            if (run != null) {
                cuts.add(padded(run, runHasWord, padMs, firstIsWord ? before : null, lastIsWord ? after : null));
                run = null;
            }
            Integer keepMs = token.word() ? null : trims.get(token.id());
            if (keepMs != null && token.endMs() - token.startMs() > keepMs) {
                // keepMs is split between the two sides; an odd millisecond stays after the cut.
                long keepBefore = keepMs / 2;
                cuts.add(new PlannedCut(token.startMs() + keepBefore, token.endMs() - (keepMs - keepBefore), CutKind.TRIM, List.of(token.id())));
            }
        }
        if (run != null) {
            cuts.add(padded(run, runHasWord, padMs, firstIsWord ? before : null, lastIsWord ? after : null));
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

    private static List<PlannedCut> rangeCutsOf(List<RangeCut> rangeCuts, List<Token> tokens, long durationMs) {
        List<PlannedCut> cuts = new ArrayList<>();
        for (RangeCut rangeCut : rangeCuts) {
            long startMs = Math.max(0, rangeCut.startMs());
            long endMs = Math.min(durationMs, rangeCut.endMs());
            if (endMs <= startMs) {
                continue;
            }
            List<String> covered = tokens.stream()
                    .filter(token -> token.startMs() >= startMs && token.endMs() <= endMs)
                    .map(Token::id)
                    .toList();
            cuts.add(new PlannedCut(startMs, endMs, CutKind.valueOf(rangeCut.kind().name()), covered, List.of(rangeCut.id())));
        }
        return cuts;
    }

    private static boolean isRemoved(List<Token> tokens, int index, Set<String> removed) {
        Token token = tokens.get(index);
        if (removed.contains(token.id())) {
            return true;
        }
        return !token.word() && index > 0 && index < tokens.size() - 1
                && removed.contains(tokens.get(index - 1).id()) && removed.contains(tokens.get(index + 1).id());
    }

    private static PlannedCut padded(PlannedCut cut, boolean hasWord, int padMs, Token outwardBefore, Token outwardAfter) {
        if (!hasWord) {
            return cut;
        }
        cut.startMs = outwardBefore != null ? Math.max(midpoint(outwardBefore), cut.startMs - padMs) : cut.startMs + padMs;
        cut.endMs = outwardAfter != null ? Math.min(midpoint(outwardAfter), cut.endMs + padMs) : cut.endMs - padMs;
        return cut;
    }

    /** The middle of a pause: the furthest a cut's padding may reach into a kept pause, so it never touches a kept word. */
    private static long midpoint(Token token) {
        return token.startMs() + (token.endMs() - token.startMs()) / 2;
    }

    private List<PlannedCut> absorbTinyKeeps(List<PlannedCut> cuts, long durationMs) {
        int minKeepMs = properties.edit().minKeepSegmentMs();
        List<PlannedCut> sorted = new ArrayList<>(cuts);
        sorted.sort(Comparator.<PlannedCut>comparingLong(cut -> cut.startMs).thenComparingLong(cut -> cut.endMs));

        List<PlannedCut> merged = new ArrayList<>();
        for (PlannedCut cut : sorted) {
            PlannedCut last = merged.isEmpty() ? null : merged.getLast();
            if (last != null && cut.startMs - last.endMs < minKeepMs) {
                last.endMs = Math.max(last.endMs, cut.endMs);
                last.kind = last.kind == cut.kind ? last.kind : CutKind.REMOVE;
                last.tokenIds.addAll(cut.tokenIds);
                last.rangeCutIds.addAll(cut.rangeCutIds);
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
