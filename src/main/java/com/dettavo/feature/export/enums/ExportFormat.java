package com.dettavo.feature.export.enums;

public enum ExportFormat {
    MP4("mp4", "mp4", true, "mp4 video"),
    MP3("mp3", "mp3", false, "mp3 audio"),
    WAV("wav", "wav", false, "wav audio");

    private final String extension;
    private final String muxer;
    private final boolean video;
    private final String label;

    ExportFormat(String extension, String muxer, boolean video, String label) {
        this.extension = extension;
        this.muxer = muxer;
        this.video = video;
        this.label = label;
    }

    public String extension() {
        return extension;
    }

    /** The value of FFmpeg's -f, needed because the output is written to a .partial file first. */
    public String muxer() {
        return muxer;
    }

    public boolean hasVideo() {
        return video;
    }

    /** As the job's stage shows it: "Encoding mp4 video". */
    public String label() {
        return label;
    }
}
