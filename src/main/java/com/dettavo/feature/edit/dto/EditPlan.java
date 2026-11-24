package com.dettavo.feature.edit.dto;

import java.util.List;

public record EditPlan(List<Cut> cuts, List<KeepSegment> keepSegments, long editedMs, long removedMs, long timelineMs) {

    public EditPlan {
        cuts = List.copyOf(cuts);
        keepSegments = List.copyOf(keepSegments);
    }
}
