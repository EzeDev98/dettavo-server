package com.dettavo.feature.edit;

import java.util.List;

/**
 * One stretch of a timeline clip that is cut (guide 4.9, rules 2 to 5). startMs and endMs are on the clip's own
 * source; the timeline times are on the unedited timeline. tokenIds are the removed or trimmed tokens it covers.
 */
public record Cut(String clipId, long startMs, long endMs, long timelineStartMs, long timelineEndMs, CutKind kind,
                  List<String> tokenIds) {

    public Cut {
        tokenIds = List.copyOf(tokenIds);
    }
}
