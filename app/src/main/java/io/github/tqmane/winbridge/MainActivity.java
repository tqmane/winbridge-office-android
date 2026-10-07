package io.github.tqmane.winbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private TextView output;
    private String pendingAction;
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
            + "Companions: Termux (GitHub build) and Termux:X11. Enable allow-external-apps in Termux.\n"
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
        if (checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action;
            requestPermissions(new String[]{PERMISSION}, 1);
            return;
        }
        try (InputStream script = getAssets().open("runtime.sh")) {
            Intent callback = new Intent(this, RuntimeResult.class).setData(Uri.parse("winbridge:result/" + System.nanoTime()));
            PendingIntent result = PendingIntent.getBroadcast(this, 0, callback,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
            Intent command = new Intent("com.termux.RUN_COMMAND");
            command.setClassName("com.termux", "com.termux.app.RunCommandService");
            command.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-s", "--", action});
            command.putExtra("com.termux.RUN_COMMAND_STDIN", new String(script.readAllBytes(), StandardCharsets.UTF_8));
            command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            command.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "WinBridge: " + action);
            command.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);
            state.edit().putString("result", "Running: " + action + "\nSee the Termux notification for the active session.").apply();
            startService(command);
            if (display) showDisplay();
        } catch (Exception error) {
            state.edit().putString("result", error.toString() + "\nInstall and open Termux once, then enable its external-app setting.").apply();
        }
    }
    private void showDisplay() {
        try {
            startActivity(new Intent().setClassName("com.termux.x11", "com.termux.x11.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception error) { output.setText("Install Termux:X11 first. " + error.getMessage()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && pendingAction != null) {
            String action = pendingAction;
            pendingAction = null;
            run(action, !action.equals("status") && !action.equals("init") && !action.equals("logs"));
        } else output.setText("Termux RUN_COMMAND permission is required to start the runtime.");
    }

    public static final class WordActivity extends MainActivity { @Override protected String launchAction() { return "word"; } }
    public static final class ExcelActivity extends MainActivity { @Override protected String launchAction() { return "excel"; } }
    public static final class PowerPointActivity extends MainActivity { @Override protected String launchAction() { return "powerpoint"; } }
}
