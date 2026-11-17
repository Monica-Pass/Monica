package takagi.ru.monica.hyperos.hooks;

/** Keep original exceptions and call counts intact; never retry a side-effecting original call. */
public final class HookGuard {
    private HookGuard() {}
    @FunctionalInterface public interface Call { Object run() throws Throwable; }
    @FunctionalInterface public interface Change { void run() throws Exception; }
    public static Object after(Call original, Change change) throws Throwable {
        Object result = original.run();
        try { change.run(); } catch (Exception ignored) { /* Original result survives incompatible OEM fields. */ }
        return result;
    }
    public static Object temporary(Call original, Change begin, Change restore) throws Throwable {
        try { begin.run(); } catch (Exception unavailable) { return original.run(); }
        try { return original.run(); } finally { restore.run(); }
    }
}
