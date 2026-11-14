package com.dettavo.feature.edit;

/**
 * REMOVE: removed words and pauses (rule 2), or cuts of different kinds merged into one. TRIM: the middle of a
 * shortened pause (rule 3). MANUAL: a piece deleted on the timeline, and SILENCE: a silence over the max pause (rule 9).
 */
public enum CutKind {
    REMOVE,
    TRIM,
    MANUAL,
    SILENCE
}
