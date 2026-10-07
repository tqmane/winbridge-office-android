package io.github.tqmane.winbridge;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import com.termux.x11.LorieApp;
import com.termux.x11.LorieView;

public final class WinBridgeApp extends LorieApp implements android.app.Application.ActivityLifecycleCallbacks {
    private WindowsInput input;
    private SharedPreferences settings;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> updateInput();
    private boolean desktopVisible;

    @Override public void onCreate() {
        super.onCreate();
        if (!getProcessName().equals(getPackageName())) return;
        input = new WindowsInput(this);
        settings = getSharedPreferences("settings", 0);
        settings.registerOnSharedPreferenceChangeListener(changed);
        registerActivityLifecycleCallbacks(this);
        updateInput();
    }

    private void updateInput() {
        boolean enabled = settings.getBoolean("direct_input", false);
        input.pause();
        LorieView.externalInput = enabled ? input : null;
        if (enabled && desktopVisible) input.resume();
    }

    @Override public void onActivityResumed(Activity activity) {
        if (activity.getClass().getName().startsWith("com.termux.x11.MainActivity")) { desktopVisible = true; updateInput(); }
    }
    @Override public void onActivityPaused(Activity activity) {
        if (activity.getClass().getName().startsWith("com.termux.x11.MainActivity")) { desktopVisible = false; input.pause(); }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }
}
