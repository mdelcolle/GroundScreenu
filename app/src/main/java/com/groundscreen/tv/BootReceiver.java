package com.groundscreen.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver in the background when the TV box boots (optional, on by default). */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (new Prefs(context).startOnBoot()) {
            AirPlayService.start(context);
        }
    }
}
