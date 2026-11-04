package rikka.shizuku;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Runs shell commands through Shizuku.
 *
 * Shizuku.newProcess(...) is private in the published API, so we reach it by
 * reflection rather than shipping a copy of the AIDL stubs.
 *
 * Every entry point calls Shizuku.newProcess, which itself requires the Shizuku
 * permission; a missing/granted-late binder surfaces as an exception here, so
 * callers must always be prepared to get "ERR: ..." back instead of output.
 */
public final class ShizukuShellHelper {

    private static Method NEW_PROCESS;

    private static synchronized Method method() throws Exception {
        if (NEW_PROCESS == null) {
            Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            NEW_PROCESS = m;
        }
        return NEW_PROCESS;
    }

    private static Process exec(String cmd) throws Exception {
        return (Process) method().invoke(null, new String[]{"sh", "-c", cmd}, null, null);
    }

    /** Runs a short command and returns everything it printed. */
    public static String run(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = exec(cmd);
            InputStream in = p.getInputStream();
            InputStream er = p.getErrorStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n));
            p.waitFor();
            while ((n = er.read(buf)) > 0) sb.append(new String(buf, 0, n));
        } catch (Throwable t) {
            sb.append("ERR: ").append(t).append('\n');
        }
        return sb.toString();
    }

    /**
     * Runs a long command, handing each line to {@code sink} as it appears.
     * The kit takes minutes, so buffering everything until exit would show the
     * user nothing at all.
     */
    public static void runStreaming(String cmd, Consumer<String> sink) {
        try {
            Process p = exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sink.accept(line);
            r.close();
            p.waitFor();
            sink.accept("[exit=" + p.exitValue() + "]");
        } catch (Throwable t) {
            sink.accept("ERR: " + t);
        }
    }
}
