package io.github.tqmane.winbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private TextView output;
    private SharedPreferences state;
    protected String launchAction() { return null; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = getSharedPreferences("runtime", 0);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 24, 24, 24);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("WinBridge Office · experimental x86_64 runtime");
        title.setTextSize(24);
        page.addView(title);
        TextView details = new TextView(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        details.setText("Android " + Build.VERSION.RELEASE + " · " + String.join(", ", Build.SUPPORTED_ABIS)
            + " · " + dm.widthPixels + "×" + dm.heightPixels + " · " + dm.densityDpi + " dpi\n"
            + "Shared Linux / Wine runtime in this app's private storage.\n"
            + "Office requires your own Microsoft installation and licence.");
        page.addView(details);
        LinearLayout setup = row(page);
        button(setup, "Runtime status", () -> run("status", false));
        button(setup, "Initialize runtime", () -> run("init", false));
        button(setup, "Install Microsoft 365", () -> run("install-office", true));
        LinearLayout apps = row(page);
        button(apps, "Word", () -> run("word", true));
        button(apps, "Excel", () -> run("excel", true));
        button(apps, "PowerPoint", () -> run("powerpoint", true));
        LinearLayout diagnostics = row(page);
        button(diagnostics, "Wine Notepad", () -> run("notepad", true));
        button(diagnostics, "Wine configuration", () -> run("winecfg", true));
        button(diagnostics, "Logs / diagnostics", () -> run("logs", false));
        LinearLayout lifecycle = row(page);
        button(lifecycle, "Show Windows desktop", this::showDisplay);
        button(lifecycle, "Stop Wine", () -> confirm("Stop all Wine applications? Save your documents first.", "stop"));
        button(lifecycle, "Reset prefix", () -> confirm("Archive the current prefix and create a new one? Office will need reinstalling.", "reset"));
        LinearLayout integrated = row(page);
        button(integrated, "Test integrated Linux runtime", () -> startForegroundService(
            new Intent(this, NativeRuntimeService.class).setAction("native-smoke")));
        android.widget.Switch directInput = new android.widget.Switch(this);
        directInput.setText("Direct Windows input (experimental)");
        var settings = getSharedPreferences("settings", 0);
        directInput.setChecked(settings.getBoolean("direct_input", false));
        directInput.setOnCheckedChangeListener((button, checked) -> settings.edit().putBoolean("direct_input", checked).apply());
        page.addView(directInput);
        output = new TextView(this);
        output.setTextSize(14);
        output.setTextIsSelectable(true);
        page.addView(output);
        if (saved == null && launchAction() != null) run(launchAction(), true);
    }

    @Override protected void onResume() {
        super.onResume();
        state.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(state, "result");
    }
    @Override protected void onPause() {
        state.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        output.setText(prefs.getString("result", "Ready. Initialize the runtime to begin."));
    }
    private LinearLayout row(LinearLayout page) {
        LinearLayout row = new LinearLayout(this);
        page.addView(row);
        return row;
    }
    private void button(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }
    private void confirm(String message, String action) {
        new AlertDialog.Builder(this).setMessage(message).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", (dialog, which) -> run(action, false)).show();
    }
    private void run(String action, boolean display) {
        try {
            state.edit().putString("result", "Running: " + action + "\nSee Logs / diagnostics for progress.").apply();
            startForegroundService(new Intent(this, NativeRuntimeService.class).setAction(action));
            if (display) showDisplay();
        } catch (Exception error) {
            state.edit().putString("result", error.toString()).apply();
        }
    }
    private void showDisplay() {
        try {
            startActivity(new Intent().setClassName(this, "com.termux.x11.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception error) { output.setText(error.toString()); }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (launchAction() != null) run(launchAction(), true);
    }

    public static final class WordActivity extends MainActivity { @Override protected String launchAction() { return "word"; } }
    public static final class ExcelActivity extends MainActivity { @Override protected String launchAction() { return "excel"; } }
    public static final class PowerPointActivity extends MainActivity { @Override protected String launchAction() { return "powerpoint"; } }
}
