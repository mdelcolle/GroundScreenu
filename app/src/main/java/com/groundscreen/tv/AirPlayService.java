package com.groundscreen.tv;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import com.groundscreen.tv.media.AudioRenderer;
import com.groundscreen.tv.media.VideoRenderer;
import com.groundscreen.tv.server.AirPlayServer;
import com.groundscreen.tv.server.MirrorListener;
import com.groundscreen.tv.server.ReceiverConfig;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that keeps the AirPlay receiver discoverable and owns the decoders,
 * so mirroring keeps working while the activity is recreated or in the background.
 */
public final class AirPlayService extends Service implements MirrorListener, VideoRenderer.SizeListener {

    public enum State { STARTING, READY, CONNECTED, AUDIO, MIRRORING, ERROR }

    public interface StateListener {
        void onStateChanged(State state, String detail);

        void onVideoSize(int width, int height);
    }

    private static final String TAG = "AirPlayService";
    private static final String CHANNEL = "receiver";
    private static final int NOTIFICATION_ID = 1;

    private static volatile AirPlayService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<StateListener>();
    private final VideoRenderer video = new VideoRenderer(this);
    private final AudioRenderer audio = new AudioRenderer();

    private AirPlayServer server;
    private WifiManager.WifiLock wifiLock;
    private volatile State state = State.STARTING;
    private volatile String detail = "";

    public static void start(Context c) {
        Intent i = new Intent(c, AirPlayService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    /** Restarts the receiver so new name / quality settings are advertised. */
    public static void restart(Context c) {
        AirPlayService s = instance;
        if (s != null) {
            s.restartServer();
        } else {
            start(c);
        }
    }

    public static AirPlayService get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(NOTIFICATION_ID, buildNotification());
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "groundscreen");
            wifiLock.setReferenceCounted(false);
        }
        startServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        video.release();
        audio.release();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ public API for the UI

    public VideoRenderer video() {
        return video;
    }

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public String receiverName() {
        return new Prefs(this).name();
    }

    public void addListener(StateListener l) {
        listeners.add(l);
        l.onStateChanged(state, detail);
    }

    public void removeListener(StateListener l) {
        listeners.remove(l);
    }

    // ------------------------------------------------------------------ server lifecycle

    private void startServer() {
        final Prefs prefs = new Prefs(this);
        setState(State.STARTING, "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int[] q = Prefs.QUALITIES[prefs.qualityIndex()];
                    ReceiverConfig cfg = new ReceiverConfig(prefs.name(), prefs.deviceId(), q[0], q[1], q[2],
                            PairingIdentity.fromSeed(prefs.pairingSeed()));
                    AirPlayServer s = new AirPlayServer(AirPlayService.this, cfg, AirPlayService.this);
                    s.start();
                    synchronized (AirPlayService.this) {
                        server = s;
                    }
                    setState(State.READY, "");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start receiver", e);
                    setState(State.ERROR, String.valueOf(e.getMessage()));
                }
            }
        }, "airplay-start").start();
    }

    private synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        endMirror();
        audio.release();
    }

    private void restartServer() {
        stopServer();
        startServer();
    }

    private void setState(State s, String d) {
        state = s;
        detail = d;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (StateListener l : listeners) {
                    l.onStateChanged(state, detail);
                }
            }
        });
    }

    private void endMirror() {
        video.reset();
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // ------------------------------------------------------------------ MirrorListener

    @Override
    public void onClientConnected(String clientAddress) {
        setState(State.CONNECTED, clientAddress);
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public void onMirrorStarted() {
        video.reset();
        if (wifiLock != null) {
            wifiLock.acquire();
        }
        setState(State.MIRRORING, "");
        bringToFront();
    }

    @Override
    public void onVideoConfig(byte[] sps, byte[] pps) {
        video.onConfig(sps, pps);
    }

    @Override
    public void onVideoFrame(byte[] annexB, long ntpTimestamp) {
        video.onFrame(annexB);
    }

    @Override
    public void onAudioFormat(AudioStreamInfo info) {
        audio.configure(info);
        if (state == State.CONNECTED) {
            setState(State.AUDIO, detail);
        }
    }

    @Override
    public void onAudioFrame(byte[] data, int offset, int length) {
        audio.onFrame(data, offset, length);
    }

    @Override
    public void onVolume(float volume) {
        audio.setVolume(volume);
    }

    @Override
    public void onAudioStopped() {
        audio.release();
        if (state == State.AUDIO) {
            setState(State.CONNECTED, detail);
        }
    }

    @Override
    public void onMirrorStopped() {
        endMirror();
        setState(State.CONNECTED, "");
    }

    @Override
    public void onClientDisconnected() {
        endMirror();
        audio.release();
        setState(State.READY, "");
    }

    @Override
    public void onVideoSize(final int width, final int height) {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (StateListener l : listeners) {
                    l.onVideoSize(width, height);
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void bringToFront() {
        if (MainActivity.isVisible()) {
            return;
        }
        // Allowed from a foreground service up to Android 9; on 10+ only with the overlay permission.
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
            Log.i(TAG, "Cannot open the activity from background on this Android version");
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "startActivity failed: " + e);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
