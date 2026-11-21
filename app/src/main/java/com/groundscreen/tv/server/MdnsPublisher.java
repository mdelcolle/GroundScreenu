package com.groundscreen.tv.server;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Advertises {@code _airplay._tcp} and {@code _raop._tcp} using Android's built-in mDNS
 * responder ({@link NsdManager}), so no multicast socket handling is needed in the app.
 */
public final class MdnsPublisher {

    private static final String TAG = "MdnsPublisher";

    private final NsdManager nsd;
    private final List<NsdManager.RegistrationListener> listeners = new ArrayList<NsdManager.RegistrationListener>();

    public MdnsPublisher(Context context) {
        nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    public synchronized void publish(String name, String type, int port, Map<String, String> txt) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        for (Map.Entry<String, String> e : txt.entrySet()) {
            info.setAttribute(e.getKey(), e.getValue());
        }
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "mDNS registration failed " + serviceInfo.getServiceType() + " code=" + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS unregistration failed code=" + errorCode);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "mDNS registered " + serviceInfo.getServiceName() + " " + serviceInfo.getServiceType());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "mDNS unregistered " + serviceInfo.getServiceName());
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
            listeners.add(l);
        } catch (RuntimeException e) {
            Log.e(TAG, "registerService failed", e);
        }
    }

    public synchronized void unpublishAll() {
        for (NsdManager.RegistrationListener l : listeners) {
            try {
                nsd.unregisterService(l);
            } catch (RuntimeException ignored) {
                // already unregistered
            }
        }
        listeners.clear();
    }
}
