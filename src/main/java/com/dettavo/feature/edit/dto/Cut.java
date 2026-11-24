package com.dettavo.feature.edit.dto;

import com.dettavo.feature.edit.enums.CutKind;

import java.util.List;

public record Cut(String clipId, long startMs, long endMs, long timelineStartMs, long timelineEndMs, CutKind kind,
                  List<String> tokenIds, List<String> rangeCutIds) {

    public Cut {
        tokenIds = List.copyOf(tokenIds);
        rangeCutIds = List.copyOf(rangeCutIds);
    }
}
