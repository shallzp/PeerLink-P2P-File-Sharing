package com.example.peerlink.Utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

public final class ThemeManager {

    private static final String PREFS_NAME = "PeerLinkPrefs";
    private static final String KEY_THEME_MODE = "THEME_MODE";

    private ThemeManager() {
        // Utility class
    }

    public static void applySavedTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_THEME_MODE)) {
            return;
        }

        int themeMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (AppCompatDelegate.getDefaultNightMode() != themeMode) {
            AppCompatDelegate.setDefaultNightMode(themeMode);
        }
    }

    public static void toggleTheme(Context context) {
        int nextMode = isDarkMode(context)
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_YES;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_THEME_MODE, nextMode).apply();
        AppCompatDelegate.setDefaultNightMode(nextMode);
    }

    public static boolean isDarkMode(Context context) {
        int currentNightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES;
    }
}
