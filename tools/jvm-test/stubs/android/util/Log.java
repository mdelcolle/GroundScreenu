package android.util;

/** JVM stand-in for android.util.Log used by the protocol test harness. */
public final class Log {
    private Log() {
    }

    private static int p(String level, String tag, String msg, Throwable t) {
        System.out.println(level + "/" + tag + ": " + msg);
        if (t != null) {
            t.printStackTrace(System.out);
        }
        return 0;
    }

    public static int d(String tag, String msg) { return p("D", tag, msg, null); }
    public static int i(String tag, String msg) { return p("I", tag, msg, null); }
    public static int w(String tag, String msg) { return p("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return p("W", tag, msg, t); }
    public static int e(String tag, String msg) { return p("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return p("E", tag, msg, t); }
}
