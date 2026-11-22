package com.groundscreen.tv.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory event log shown on the TV ("Diagnóstico") to debug sessions without adb. */
public final class DiagLog {

    private static final int MAX = 28;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static volatile long videoPackets;
    public static volatile long framesDecoded;
    public static volatile long audioPackets;
    public static volatile String videoSize = "-";

    private DiagLog() {
    }

    public static void add(String msg) {
        String line;
        synchronized (FMT) {
            line = FMT.format(new Date()) + "  " + msg;
        }
        synchronized (LINES) {
            if (LINES.size() >= MAX) {
                LINES.removeFirst();
            }
            LINES.addLast(line);
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("video pkts ").append(videoPackets).append(" | decodificados ").append(framesDecoded)
                .append(" | tamaño ").append(videoSize).append(" | audio pkts ").append(audioPackets).append('\n');
        synchronized (LINES) {
            for (String l : LINES) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }
}
