package io.github.tqmane.winbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class RuntimeResult extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Bundle result = intent.getBundleExtra("result");
        String text = result == null ? "No runtime result received." :
            "Exit: " + result.getInt("exitCode", -1) + "\n" +
            result.getString("stdout", "") + result.getString("stderr", "") +
            result.getString("errmsg", "");
        context.getSharedPreferences("runtime", 0).edit().putString("result", text).apply();
    }
}
