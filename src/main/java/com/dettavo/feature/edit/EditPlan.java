package com.dettavo.feature.edit;

import java.util.List;

/**
 * The plan for a project's saved edits: cuts and keep segments in timeline order, the edited length, the length
 * cut away and the unedited timeline length. editPlan.js in dettavo-client returns the same shape.
 */
public record EditPlan(List<Cut> cuts, List<KeepSegment> keepSegments, long editedMs, long removedMs, long timelineMs) {

    public EditPlan {
        cuts = List.copyOf(cuts);
        keepSegments = List.copyOf(keepSegments);
    }
}
