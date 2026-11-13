package com.dettavo.feature.project.model;

/** Where a clip is in its pipeline: added, uploading, preparing its cache, transcribing, done or failed. */
public enum ClipState {
    EMPTY,
    UPLOADING,
    PREPARING,
    PREPARED,
    TRANSCRIBING,
    READY,
    FAILED
}
