package com.dettavo.config;

/** URL paths shared by controllers and the security filter. */
public final class ApiPaths {

    public static final String BASE = "/api/v1";
    public static final String SYSTEM_STATUS = BASE + "/system/status";
    public static final String SESSION_PAIR = BASE + "/session/pair";

    private ApiPaths() {
    }
}
