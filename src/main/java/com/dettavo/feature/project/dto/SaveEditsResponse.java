package com.dettavo.feature.project.dto;

/** The revision to send in If-Match on the next save. */
public record SaveEditsResponse(long revision) {
}
