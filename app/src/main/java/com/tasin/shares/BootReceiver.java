package com.tasin.shares;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts TV Link when the TV boots, and again after the app is updated. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        LinkService.start(ctx);
    }
}
