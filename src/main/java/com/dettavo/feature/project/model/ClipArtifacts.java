package com.dettavo.feature.project.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/** Rebuildable cache files, relative to the clip's folder. A clip without video has no thumbnail. */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClipArtifacts(String audio16k, String thumbnail, String proxy) {
}
