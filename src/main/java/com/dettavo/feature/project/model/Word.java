package com.dettavo.feature.project.model;

/** One spoken word, w00001 upward within its clip. Times are milliseconds on the clip's source. */
public record Word(String id, String text, long startMs, long endMs, double confidence) {
}
