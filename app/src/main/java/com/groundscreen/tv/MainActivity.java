package com.groundscreen.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-screen TV activity: shows connection instructions while idle and the mirrored screen. */
public final class MainActivity extends Activity implements AirPlayService.StateListener, SurfaceHolder.Callback {

    private static volatile boolean visible;

    private final Handler handler = new Handler();
    private AspectRatioFrameLayout videoFrame;
    private SurfaceView surfaceView;
    private View idlePanel;
    private TextView nameText;
    private TextView statusText;
    private TextView ipText;
    private Button qualityButton;
    private Button bootButton;
    private AirPlayService service;
    private SurfaceHolder holder;

    static boolean isVisible() {
        return visible;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        videoFrame = (AspectRatioFrameLayout) findViewById(R.id.video_frame);
        surfaceView = (SurfaceView) findViewById(R.id.surface);
        idlePanel = findViewById(R.id.idle_panel);
        nameText = (TextView) findViewById(R.id.receiver_name);
        statusText = (TextView) findViewById(R.id.status);
        ipText = (TextView) findViewById(R.id.ip);
        qualityButton = (Button) findViewById(R.id.btn_quality);
        bootButton = (Button) findViewById(R.id.btn_boot);
        surfaceView.getHolder().addCallback(this);

        findViewById(R.id.btn_rename).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showRenameDialog();
            }
        });
        qualityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs p = new Prefs(MainActivity.this);
                p.setQualityIndex((p.qualityIndex() + 1) % Prefs.QUALITIES.length);
                updateButtons();
                AirPlayService.restart(MainActivity.this);
            }
        });
        bootButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs p = new Prefs(MainActivity.this);
                p.setStartOnBoot(!p.startOnBoot());
                updateButtons();
            }
        });
        View overlayBtn = findViewById(R.id.btn_overlay);
        if (Build.VERSION.SDK_INT >= 29) {
            overlayBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestOverlayPermission();
                }
            });
        } else {
            overlayBtn.setVisibility(View.GONE);
        }
        AirPlayService.start(this);
        updateButtons();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        attachService();
    }

    @Override
    protected void onStop() {
        visible = false;
        handler.removeCallbacksAndMessages(null);
        if (service != null) {
            service.removeListener(this);
            service = null;
        }
        super.onStop();
    }

    private void attachService() {
        AirPlayService s = AirPlayService.get();
        if (s == null) {
            // Service is being created; try again shortly.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (visible) {
                        attachService();
                    }
                }
            }, 200);
            return;
        }
        service = s;
        service.addListener(this);
        if (holder != null && holder.getSurface().isValid()) {
            service.video().setSurface(holder.getSurface());
        }
    }

    // ------------------------------------------------------------------ surface

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        if (service != null) {
            service.video().setSurface(h.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        holder = h;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        AirPlayService s = AirPlayService.get();
        if (s != null) {
            s.video().setSurface(null);
        }
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onStateChanged(AirPlayService.State state, String detail) {
        String name = service != null ? service.receiverName() : new Prefs(this).name();
        nameText.setText(name);
        ipText.setText(getString(R.string.ip_label, localIps()));
        boolean mirroring = state == AirPlayService.State.MIRRORING;
        idlePanel.setVisibility(mirroring ? View.GONE : View.VISIBLE);
        switch (state) {
            case STARTING:
                statusText.setText(R.string.status_starting);
                break;
            case READY:
                statusText.setText(getString(R.string.status_ready, name));
                break;
            case CONNECTED:
                statusText.setText(getString(R.string.status_connected, detail));
                break;
            case AUDIO:
                statusText.setText(R.string.status_audio);
                break;
            case MIRRORING:
                break;
            case ERROR:
                statusText.setText(getString(R.string.status_error, detail));
                break;
        }
        if (mirroring) {
            idlePanel.clearFocus();
        } else if (getCurrentFocus() == null) {
            findViewById(R.id.btn_rename).requestFocus();
        }
    }

    @Override
    public void onVideoSize(int width, int height) {
        if (width > 0 && height > 0) {
            videoFrame.setAspectRatio((float) width / height);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // While mirroring, OK / Menu shows the settings panel over the video.
        if (service != null && service.state() == AirPlayService.State.MIRRORING
                && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_ENTER)) {
            idlePanel.setVisibility(idlePanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (idlePanel.getVisibility() == View.VISIBLE) {
                statusText.setText(R.string.status_mirroring);
                findViewById(R.id.btn_rename).requestFocus();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && service != null
                && service.state() == AirPlayService.State.MIRRORING && idlePanel.getVisibility() == View.VISIBLE) {
            idlePanel.setVisibility(View.GONE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ settings

    private void updateButtons() {
        Prefs p = new Prefs(this);
        int[] q = Prefs.QUALITIES[p.qualityIndex()];
        qualityButton.setText(getString(R.string.btn_quality, q[1] + "p " + q[2] + " fps"));
        bootButton.setText(p.startOnBoot() ? R.string.btn_boot_on : R.string.btn_boot_off);
    }

    private void showRenameDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(new Prefs(this).name());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String n = input.getText().toString().trim().replaceAll("[@.]", " ");
                        if (n.length() > 40) {
                            n = n.substring(0, 40);
                        }
                        if (!n.isEmpty()) {
                            new Prefs(MainActivity.this).setName(n);
                            nameText.setText(n);
                            AirPlayService.restart(MainActivity.this);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_granted, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.overlay_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private static String localIps() {
        List<String> ips = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        if (ips.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ip);
        }
        return sb.toString();
    }
}
