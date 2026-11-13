package com.dettavo.config;

/** URL paths shared by controllers and the security filter. */
public final class ApiPaths {

    public static final String BASE = "/api/v1";
    public static final String SYSTEM_STATUS = BASE + "/system/status";
    public static final String SESSION_PAIR = BASE + "/session/pair";

    public static final String PROJECTS = BASE + "/projects";
    public static final String PROJECT = PROJECTS + "/{projectId}";
    public static final String PROJECT_EDITS = PROJECT + "/edits";
    public static final String PROJECT_CLIPS = PROJECT + "/clips";
    public static final String PROJECT_CLIP = PROJECT_CLIPS + "/{clipId}";
    public static final String PROJECT_EVENTS = PROJECT + "/events";
    public static final String CLIP_SOURCE = PROJECT_CLIP + "/source";
    public static final String CLIP_PREPARE = PROJECT_CLIP + "/prepare";
    public static final String CLIP_TRANSCRIBE = PROJECT_CLIP + "/transcribe";
    public static final String CLIP_PROXY = PROJECT_CLIP + "/media/proxy";
    public static final String CLIP_THUMBNAIL = PROJECT_CLIP + "/media/thumbnail";

    public static final String JOBS = BASE + "/jobs";
    public static final String JOB = JOBS + "/{jobId}";
    public static final String JOB_CANCEL = JOB + "/cancel";
    public static final String JOB_EVENTS = JOB + "/events";

    private ApiPaths() {
    }
}
