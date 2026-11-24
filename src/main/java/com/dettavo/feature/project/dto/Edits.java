package com.dettavo.feature.project.dto;

import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record Edits(long revision, List<String> timeline, List<String> removed, List<Trim> trims, int padMs,
                    List<RangeCut> rangeCuts, List<SplitPoint> splits) {

    public Edits {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        removed = removed == null ? List.of() : List.copyOf(removed);
        trims = trims == null ? List.of() : List.copyOf(trims);
        rangeCuts = rangeCuts == null ? List.of() : List.copyOf(rangeCuts);
        splits = splits == null ? List.of() : List.copyOf(splits);
    }

    /** A new project's edits: revision 0, nothing on the timeline, nothing cut. */
    public static Edits initial(int padMs) {
        return new Edits(0, List.of(), List.of(), List.of(), padMs, List.of(), List.of());
    }
}
