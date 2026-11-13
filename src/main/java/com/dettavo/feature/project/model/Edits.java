package com.dettavo.feature.project.model;

import lombok.Builder;

import java.util.List;

/**
 * The timeline and the edits on it (guide 4.9). timeline lists clip ids in order; removed lists
 * token ids; trims shorten pauses. revision goes up by one on every save.
 */
@Builder(toBuilder = true)
public record Edits(long revision, List<String> timeline, List<String> removed, List<Trim> trims, int padMs) {

    public Edits {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        removed = removed == null ? List.of() : List.copyOf(removed);
        trims = trims == null ? List.of() : List.copyOf(trims);
    }

    /** A new project's edits: revision 0, nothing on the timeline, nothing cut. */
    public static Edits initial(int padMs) {
        return new Edits(0, List.of(), List.of(), List.of(), padMs);
    }
}
