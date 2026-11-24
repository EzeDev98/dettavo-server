package com.dettavo.feature.project.service;

import com.dettavo.feature.project.dto.Project;

/** The edits revision travels as the ETag on reads and comes back in If-Match on saves. */
public final class Revisions {

    private Revisions() {
    }

    public static String tag(Project project) {
        return String.valueOf(project.edits().revision());
    }

    /** Accepts 7, "7" and W/"7". */
    public static long parseIfMatch(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must hold the project's revision, such as \"7\", not " + ifMatch);
        }
    }
}
