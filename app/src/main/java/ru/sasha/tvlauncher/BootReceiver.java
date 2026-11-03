package ru.sasha.tvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

/**
 * Best-effort start for TV firmware that opens its own launcher at boot.
 * The stock launcher remains installed and enabled as a fallback.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LumaBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo selected = context.getPackageManager().resolveActivity(home, 0);
        if (selected == null || selected.activityInfo == null
                || !context.getPackageName().equals(selected.activityInfo.packageName)) {
            Log.i(TAG, "Not the selected HOME application; skipping boot launch");
            return;
        }

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(launch);
            Log.i(TAG, "Started Luma after BOOT_COMPLETED");
        } catch (RuntimeException error) {
            Log.w(TAG, "Boot launch unavailable on this firmware", error);
        }
    }
}
