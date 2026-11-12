package com.dettavo.common.file;

/**
 * Turns any file name a user sends into one that is safe on disk and on a command line:
 * only letters, digits, dot, underscore, space and hyphen, at most 120 characters, with the
 * extension kept when the name is shortened.
 */
public final class FileNameSanitizer {

    public static final int MAX_LENGTH = 120;

    private static final String FALLBACK_NAME = "file";

    private FileNameSanitizer() {
    }

    public static String sanitize(String fileName) {
        if (fileName == null) {
            return FALLBACK_NAME;
        }

        // Keep only the last path segment, whichever separator the sender used.
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);

        name = name.replaceAll("[^A-Za-z0-9._ -]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll(" {2,}", " ");

        // No leading dots (hidden files, "..") and no trailing dots or spaces.
        name = name.replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");

        if (name.isEmpty()) {
            return FALLBACK_NAME;
        }
        if (name.length() <= MAX_LENGTH) {
            return name;
        }

        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
        String base = name.substring(0, MAX_LENGTH - extension.length()).replaceAll("[. ]+$", "");
        return (base.isEmpty() ? FALLBACK_NAME : base) + extension;
    }
}
