package takagi.ru.monica.hyperos.hooks;

import java.util.Locale;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** No app/vault objects are reachable from the optional module. Only boolean policy is shared. */
public final class HookPolicy {
    public static final String GROUP = "monica_hyperos_hooks_v1";
    public static final String RESET = "security_center";
    public static final String SETTINGS = "settings_ui";
    public static final String CHOOSER = "google_chooser";
    public static final String HYBRID = "google_hybrid";
    public static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(RESET, SETTINGS, CHOOSER, HYBRID)));
    private HookPolicy() {}

    public static boolean supported(int api, int user, String manufacturer) {
        return api >= 34 && api <= 36 && user == 0 && manufacturer != null &&
                Arrays.asList("xiaomi", "redmi", "poco").contains(manufacturer.toLowerCase(Locale.ROOT));
    }

    public static boolean enabled(Map<String, ?> values, String key) {
        return KEYS.contains(key) && Integer.valueOf(0).equals(values.get("user_id")) &&
                Boolean.TRUE.equals(values.get(key));
    }

    public static String optionForPackage(String name) {
        return switch (name) {
            case "com.miui.securitycenter" -> RESET;
            case "com.android.settings" -> SETTINGS;
            case "com.xiaomi.scanner" -> HYBRID;
            default -> null;
        };
    }

    public static boolean componentEnabled(boolean appEnabled, boolean declared, int override) {
        return appEnabled && (override == 1 || (override == 0 && declared));
    }
}
