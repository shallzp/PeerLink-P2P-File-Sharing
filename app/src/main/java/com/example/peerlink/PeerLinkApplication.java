package com.example.peerlink;

import android.app.Application;

import com.example.peerlink.Utils.ThemeManager;

public class PeerLinkApplication extends Application {

    @Override
    public void onCreate() {
        ThemeManager.applySavedTheme(this);
        super.onCreate();
    }
}
