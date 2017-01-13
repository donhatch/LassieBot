package com.superliminal.android.lassiebot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Launches the service right after Android boots-up
 * but only if it had been running at shutdown.
 * 
 * @author Melinda Green
 */
public class BootRelauncher extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if("android.intent.action.BOOT_COMPLETED".equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(LassieBotService.PREFS_NAME, LassieBotService.PREFS_SHARE_MODE);
            boolean was_running = prefs.getBoolean(LassieBotService.PREFS_KEY_RUNNING, false);
            if(was_running)
                context.startService(new Intent(context, LassieBotService.class));
        }
    }
}
