package com.groundscreen.tv.server;

import android.util.Log;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.groundscreen.tv.util.BinaryPlist;
import com.groundscreen.tv.util.DiagLog;
import com.groundscreen.tv.util.IoUtil;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One AirPlay control connection (RTSP/1.0 framing, HTTP-like). Handles discovery info,
 * legacy pairing, FairPlay setup and stream SETUP/TEARDOWN.
 */
final class RtspConnection implements Runnable {

    interface Owner {
        boolean claimMirror(RtspConnection c);

        void released(RtspConnection c);
    }

    private static final String TAG = "RtspConnection";
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private final Socket socket;
    private final ReceiverConfig config;
    private final MirrorListener listener;
    private final int rtspPort;
    private final Owner owner;
    private final FairPlaySession session;

    private MirrorVideoReceiver video;
    private AudioReceiver audio;
    private boolean claimed;
    private volatile boolean closed;

    RtspConnection(Socket socket, ReceiverConfig config, MirrorListener listener, int rtspPort, Owner owner) {
        this.socket = socket;
        this.config = config;
        this.listener = listener;
        this.rtspPort = rtspPort;
        this.owner = owner;
        this.session = new FairPlaySession(config.identity);
    }

    @Override
    public void run() {
        String remote = socket.getInetAddress().getHostAddress();
        Log.i(TAG, "Control connection from " + remote);
        DiagLog.add("Conexión de " + remote);
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (!closed) {
                Request req = readRequest(in);
                if (req == null) {
                    break;
                }
                Response resp = handle(req, remote);
                writeResponse(out, req, resp);
            }
        } catch (IOException e) {
            if (!closed) {
                Log.i(TAG, "Connection ended: " + e);
                DiagLog.add("Conexión terminada");
            }
        } catch (Exception e) {
            Log.e(TAG, "Control connection failed", e);
        } finally {
            close();
        }
    }

    void close() {
        boolean wasClosed;
        synchronized (this) {
            wasClosed = closed;
            closed = true;
        }
        stopVideo();
        stopAudio();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (!wasClosed && claimed) {
            owner.released(this);
            listener.onClientDisconnected();
        }
    }

    // ------------------------------------------------------------------ request handling

    private Response handle(Request req, String remote) throws Exception {
        String m = req.method;
        String uri = req.uri;
        if (!"POST".equals(m) || !"/feedback".equals(uri)) {
            Log.d(TAG, m + " " + uri + " (" + req.body.length + " bytes)");
            DiagLog.add(m + " " + uri + (req.protocol.startsWith("HTTP") ? " [HTTP]" : ""));
        }
        if ("GET".equals(m) && uri.startsWith("/info")) {
            return Response.ok(infoPlist(), "application/x-apple-binary-plist");
        }
        if ("POST".equals(m) && "/pair-setup".equals(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            session.pairSetup(bos);
            return Response.ok(bos.toByteArray(), "application/octet-stream");
        }
        if ("POST".equals(m) && "/pair-verify".equals(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            session.pairVerify(new ByteArrayInputStream(req.body), bos);
            return Response.ok(bos.toByteArray(), "application/octet-stream");
        }
        if ("POST".equals(m) && "/fp-setup".equals(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            session.fairPlaySetup(new ByteArrayInputStream(req.body), bos);
            if (!claimed) {
                claimed = owner.claimMirror(this);
                listener.onClientConnected(remote);
            }
            return Response.ok(bos.toByteArray(), "application/octet-stream");
        }
        if ("SETUP".equals(m)) {
            return handleSetup(req, remote);
        }
        if ("GET_PARAMETER".equals(m)) {
            String body = new String(req.body, ASCII);
            if (body.contains("volume")) {
                return Response.ok("volume: 0.000000\r\n".getBytes(ASCII), "text/parameters");
            }
            return Response.ok();
        }
        if ("SET_PARAMETER".equals(m)) {
            handleSetParameter(req);
            return Response.ok().header("Audio-Jack-Status", "connected; type=analog");
        }
        if ("RECORD".equals(m)) {
            return Response.ok().header("Audio-Latency", "11025").header("Audio-Jack-Status", "connected; type=analog");
        }
        if ("OPTIONS".equals(m)) {
            return Response.ok().header("Public",
                    "ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET");
        }
        if ("TEARDOWN".equals(m)) {
            handleTeardown(req);
            return Response.ok();
        }
        if ("FLUSH".equals(m) || "ANNOUNCE".equals(m) || ("POST".equals(m)
                && ("/feedback".equals(uri) || "/audioMode".equals(uri) || "/command".equals(uri)))) {
            return Response.ok();
        }
        Log.w(TAG, "Unhandled request: " + m + " " + uri);
        DiagLog.add("  -> no soportado (404)");
        return new Response(404, "Not Found");
    }

    private Response handleSetup(Request req, String remote) throws Exception {
        if (!claimed) {
            claimed = owner.claimMirror(this);
            listener.onClientConnected(remote);
        }
        MediaStreamInfo info = session.rtspSetup(req.body);
        if (info == null) {
            // Initial SETUP carrying ekey/eiv: plain 200 (same as java-airplay).
            return Response.ok();
        }
        if (info instanceof VideoStreamInfo) {
            stopVideo();
            video = new MirrorVideoReceiver(session, listener);
            int port = video.start();
            listener.onMirrorStarted();
            Log.i(TAG, "Mirror video SETUP, data port " + port);
            DiagLog.add("  -> video en puerto " + port);
            List<Object> streams = new ArrayList<Object>();
            streams.add(BinaryPlist.dict("dataPort", port, "type", AirPlayStreamType.SCREEN));
            return Response.ok(BinaryPlist.write(BinaryPlist.dict(
                    "streams", streams,
                    "eventPort", rtspPort,
                    "timingPort", 0)), "application/x-apple-binary-plist");
        }
        AudioStreamInfo a = (AudioStreamInfo) info;
        Log.i(TAG, "Audio SETUP " + a);
        DiagLog.add("  -> audio " + a.getCompressionType());
        stopAudio();
        listener.onAudioFormat(a);
        audio = new AudioReceiver(session, listener);
        int[] ports = audio.start();
        List<Object> streams = new ArrayList<Object>();
        streams.add(BinaryPlist.dict(
                "dataPort", ports[0],
                "type", a.getRtspStreamType(),
                "controlPort", ports[1]));
        return Response.ok(BinaryPlist.write(BinaryPlist.dict("streams", streams)),
                "application/x-apple-binary-plist");
    }

    private void handleTeardown(Request req) throws Exception {
        MediaStreamInfo info = session.rtspTeardown(req.body);
        if (info instanceof VideoStreamInfo) {
            stopVideo();
        } else if (info instanceof AudioStreamInfo) {
            stopAudio();
        } else {
            stopVideo();
            stopAudio();
        }
    }

    private void handleSetParameter(Request req) {
        String ct = req.headers.get("content-type");
        if (ct == null || !ct.contains("text/parameters")) {
            return;
        }
        String body = new String(req.body, ASCII);
        for (String line : body.split("\r?\n")) {
            if (line.toLowerCase(Locale.US).startsWith("volume:")) {
                try {
                    double db = Double.parseDouble(line.substring(7).trim());
                    float linear;
                    if (db <= -30.0) {
                        linear = 0f;
                    } else {
                        linear = (float) Math.pow(10.0, db / 20.0);
                    }
                    listener.onVolume(Math.max(0f, Math.min(1f, linear)));
                } catch (NumberFormatException ignored) {
                    // malformed volume
                }
            }
        }
    }

    private synchronized void stopVideo() {
        if (video != null) {
            video.stop();
            video = null;
            listener.onMirrorStopped();
        }
    }

    private synchronized void stopAudio() {
        if (audio != null) {
            audio.stop();
            audio = null;
            listener.onAudioStopped();
        }
    }

    private byte[] infoPlist() throws IOException {
        Map<String, Object> af100 = BinaryPlist.dict("audioInputFormats", 0x3FFFFFC,
                "audioOutputFormats", 0x3FFFFFC, "type", 100);
        Map<String, Object> af101 = BinaryPlist.dict("audioInputFormats", 0x3FFFFFC,
                "audioOutputFormats", 0x3FFFFFC, "type", 101);
        List<Object> audioFormats = new ArrayList<Object>();
        audioFormats.add(af100);
        audioFormats.add(af101);

        List<Object> audioLatencies = new ArrayList<Object>();
        audioLatencies.add(BinaryPlist.dict("audioType", "default", "inputLatencyMicros", Boolean.FALSE, "type", 100));
        audioLatencies.add(BinaryPlist.dict("audioType", "default", "inputLatencyMicros", Boolean.FALSE, "type", 101));

        Map<String, Object> display = new LinkedHashMap<String, Object>();
        display.put("features", 0xE);
        display.put("height", config.height);
        display.put("heightPhysical", Boolean.FALSE);
        display.put("heightPixels", config.height);
        display.put("maxFPS", config.fps);
        display.put("overscanned", Boolean.FALSE);
        display.put("refreshRate", 60);
        display.put("rotation", Boolean.FALSE);
        display.put("uuid", "e5f7a68d-7b0f-4305-984b-974f677a150b");
        display.put("width", config.width);
        display.put("widthPhysical", Boolean.FALSE);
        display.put("widthPixels", config.width);
        List<Object> displays = new ArrayList<Object>();
        displays.add(display);

        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("audioFormats", audioFormats);
        info.put("audioLatencies", audioLatencies);
        info.put("displays", displays);
        info.put("features", AirPlayFeatures.MASK);
        info.put("keepAliveSendStatsAsBody", 1);
        info.put("model", AirPlayFeatures.MODEL);
        info.put("name", config.name);
        info.put("deviceID", config.deviceId);
        info.put("macAddress", config.deviceId);
        info.put("pi", "b08f5a79-db29-4384-b456-a4784d9e6055");
        info.put("sourceVersion", AirPlayFeatures.SOURCE_VERSION);
        info.put("statusFlags", AirPlayFeatures.STATUS_FLAGS);
        info.put("vv", 2);
        info.put("pk", config.identity.publicKey());
        return BinaryPlist.write(info);
    }

    // ------------------------------------------------------------------ wire format

    private static final class Request {
        String method;
        String uri;
        String protocol;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        byte[] body = new byte[0];
    }

    static final class Response {
        final int status;
        final String reason;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        byte[] body = new byte[0];

        Response(int status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        static Response ok() {
            return new Response(200, "OK");
        }

        static Response ok(byte[] body, String contentType) {
            Response r = ok();
            r.body = body;
            r.headers.put("Content-Type", contentType);
            return r;
        }

        Response header(String k, String v) {
            headers.put(k, v);
            return this;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
            if (line.size() > 8192) {
                throw new IOException("Header line too long");
            }
        }
        if (c == -1 && line.size() == 0) {
            return null;
        }
        return new String(line.toByteArray(), ASCII);
    }

    private static Request readRequest(InputStream in) throws IOException {
        String first;
        do {
            first = readLine(in);
            if (first == null) {
                return null;
            }
        } while (first.isEmpty());
        String[] parts = first.split(" ");
        if (parts.length < 3) {
            throw new IOException("Bad request line: " + first);
        }
        Request r = new Request();
        r.method = parts[0];
        r.uri = parts[1];
        r.protocol = parts[2];
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                r.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }
        }
        String cl = r.headers.get("content-length");
        if (cl != null) {
            int len = Integer.parseInt(cl.trim());
            if (len < 0 || len > MAX_BODY) {
                throw new IOException("Bad Content-Length " + len);
            }
            r.body = new byte[len];
            try {
                IoUtil.readFully(in, r.body, 0, len);
            } catch (EOFException e) {
                return null;
            }
        }
        return r;
    }

    private void writeResponse(OutputStream out, Request req, Response resp) throws IOException {
        String proto = req.protocol.startsWith("HTTP") ? req.protocol : "RTSP/1.0";
        StringBuilder sb = new StringBuilder();
        sb.append(proto).append(' ').append(resp.status).append(' ').append(resp.reason).append("\r\n");
        String cseq = req.headers.get("cseq");
        if (cseq != null) {
            sb.append("CSeq: ").append(cseq).append("\r\n");
        }
        sb.append("Server: AirTunes/").append(AirPlayFeatures.SOURCE_VERSION).append("\r\n");
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(resp.body.length).append("\r\n\r\n");
        synchronized (this) {
            out.write(sb.toString().getBytes(ASCII));
            out.write(resp.body);
            out.flush();
        }
    }
}
