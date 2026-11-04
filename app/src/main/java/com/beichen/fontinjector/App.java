package com.beichen.fontinjector;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Applies Material You (Material 3 dynamic color) from the system wallpaper on
 * Android 12+. On older devices the static indigo scheme in colors.xml is used.
 */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
