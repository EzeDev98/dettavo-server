package com.dettavo.feature.media.dto.response;

/**
 * A video clip's filmstrip for the timeline: count frames, one every intervalMs from the clip's start, laid out in a
 * single JPEG of columns tiles per row, each tileWidth x tileHeight. Frame i shows the clip at i x intervalMs.
 * status is BUILDING while FFmpeg makes it (ask again shortly), READY once the image can be fetched, or FAILED.
 */
public record FilmstripResponse(String status, long intervalMs, int count, int columns, int tileWidth, int tileHeight) {

    public static final String BUILDING = "BUILDING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
}
