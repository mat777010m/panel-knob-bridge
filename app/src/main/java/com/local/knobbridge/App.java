package com.local.knobbridge;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class App extends Application {
    private static final String TAG = "KnobBridge";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Proces aplikacji podniesiony (persistent), odpalam BridgeService...");
        
        Intent serviceIntent = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}