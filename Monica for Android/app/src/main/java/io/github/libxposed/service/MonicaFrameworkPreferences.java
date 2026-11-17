package io.github.libxposed.service;

import android.content.SharedPreferences;

/** Pinned service 102.0.0 caches a failed commit locally. A new wrapper reads the framework. */
public final class MonicaFrameworkPreferences {
    private MonicaFrameworkPreferences() {}
    public static SharedPreferences read(XposedService service, String group) {
        return new XposedService(service.asInterface()).getRemotePreferences(group);
    }
}
