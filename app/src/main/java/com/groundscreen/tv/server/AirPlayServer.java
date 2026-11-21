package com.groundscreen.tv.server;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * AirPlay screen-mirroring receiver: RTSP control server on TCP 7000 plus mDNS advertisement.
 * Only one sender is served at a time; a new sender replaces the previous one.
 */
public final class AirPlayServer {

    private static final String TAG = "AirPlayServer";
    private static final int[] PREFERRED_PORTS = {7000, 7100, 7200, 0};

    private final Context context;
    private final ReceiverConfig config;
    private final MirrorListener listener;
    private final MdnsPublisher mdns;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private RtspConnection current;

    public AirPlayServer(Context context, ReceiverConfig config, MirrorListener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
        this.mdns = new MdnsPublisher(this.context);
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = bind();
        running = true;
        final int port = serverSocket.getLocalPort();
        Log.i(TAG, "RTSP listening on port " + port);
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "airplay-accept");
        acceptThread.start();
        advertise(port);
    }

    public synchronized void stop() {
        running = false;
        mdns.unpublishAll();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (current != null) {
            current.close();
            current = null;
        }
    }

    public int getPort() {
        ServerSocket s = serverSocket;
        return s == null ? -1 : s.getLocalPort();
    }

    public ReceiverConfig getConfig() {
        return config;
    }

    private ServerSocket bind() throws IOException {
        IOException last = null;
        for (int p : PREFERRED_PORTS) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                return s;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                RtspConnection conn = new RtspConnection(socket, config, listener, serverSocket.getLocalPort(),
                        new RtspConnection.Owner() {
                            @Override
                            public boolean claimMirror(RtspConnection c) {
                                return AirPlayServer.this.claimMirror(c);
                            }

                            @Override
                            public void released(RtspConnection c) {
                                AirPlayServer.this.released(c);
                            }
                        });
                Thread t = new Thread(conn, "rtsp-" + socket.getInetAddress().getHostAddress());
                t.start();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept failed: " + e);
                }
            }
        }
    }

    /** A connection that starts streaming becomes the active one; the previous active is dropped. */
    private synchronized boolean claimMirror(RtspConnection c) {
        if (current != null && current != c) {
            Log.i(TAG, "New sender replaces the current one");
            current.close();
        }
        current = c;
        return true;
    }

    private synchronized void released(RtspConnection c) {
        if (current == c) {
            current = null;
        }
    }

    private void advertise(int port) {
        String mac = config.deviceId;
        String pk = config.identity.publicKeyHex();
        String features = AirPlayFeatures.txt();

        Map<String, String> airplay = new HashMap<String, String>();
        airplay.put("deviceid", mac);
        airplay.put("features", features);
        airplay.put("srcvers", AirPlayFeatures.SOURCE_VERSION);
        airplay.put("flags", AirPlayFeatures.FLAGS);
        airplay.put("vv", "2");
        airplay.put("model", AirPlayFeatures.MODEL);
        airplay.put("rhd", "5.6.0.0");
        airplay.put("pw", "false");
        airplay.put("pk", pk);
        mdns.publish(config.name, "_airplay._tcp", port, airplay);

        Map<String, String> raop = new HashMap<String, String>();
        raop.put("ch", "2");
        raop.put("cn", "1,3");
        raop.put("da", "true");
        raop.put("et", "0,3,5");
        raop.put("ek", "1");
        raop.put("ft", features);
        raop.put("am", AirPlayFeatures.MODEL);
        raop.put("md", "0,1,2");
        raop.put("sr", "44100");
        raop.put("ss", "16");
        raop.put("sv", "false");
        raop.put("sm", "false");
        raop.put("tp", "UDP");
        raop.put("txtvers", "1");
        raop.put("sf", AirPlayFeatures.FLAGS);
        raop.put("vs", AirPlayFeatures.SOURCE_VERSION);
        raop.put("vn", "65537");
        raop.put("pk", pk);
        mdns.publish(mac.replace(":", "") + "@" + config.name, "_raop._tcp", port, raop);
    }
}
