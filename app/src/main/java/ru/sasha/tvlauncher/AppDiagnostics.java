package ru.sasha.tvlauncher;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.*;

/** Low-rate lifecycle/build events. No per-frame collector, titles, URLs or credentials. */
final class AppDiagnostics {
  static final String TAG = "SashaTVDiag";
  private static long created;
  private static int resumes, pauses;
  private static final Map<String, Integer> builds = new LinkedHashMap<>();

  static void created() {
    created = SystemClock.elapsedRealtime();
    Log.i(TAG, "activity_create");
  }

  static void built(String page) {
    int count = builds.containsKey(page) ? builds.get(page) + 1 : 1;
    builds.put(page, count);
    Log.i(TAG, "page_build page=" + page + " count=" + count);
  }

  static void resumed(Activity activity, String page, boolean reused) {
    final long started = SystemClock.elapsedRealtime();
    final int sequence = ++resumes;
    final View root = activity.getWindow().getDecorView();
    Log.i(TAG, "resume page=" + page + " reuse_home=" + reused + " sequence=" + sequence);
    root.getViewTreeObserver()
        .addOnPreDrawListener(
            new ViewTreeObserver.OnPreDrawListener() {
              public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive())
                  root.getViewTreeObserver().removeOnPreDrawListener(this);
                Log.i(
                    TAG,
                    "predraw page="
                        + page
                        + " resume_to_predraw_ms="
                        + (SystemClock.elapsedRealtime() - started)
                        + " activity_age_ms="
                        + (SystemClock.elapsedRealtime() - created));
                return true;
              }
            });
    root.invalidate();
  }

  static void paused() {
    pauses++;
    Log.i(TAG, "pause sequence=" + pauses);
  }

  static String summary() {
    Runtime runtime = Runtime.getRuntime();
    long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    return "Activity работает: "
        + Math.max(0, (SystemClock.elapsedRealtime() - created) / 1000)
        + " с"
        + "\nВозвраты / паузы процесса: "
        + resumes
        + " / "
        + pauses
        + "\nJava-память: "
        + used
        + " МБ из лимита "
        + runtime.maxMemory() / (1024 * 1024)
        + " МБ"
        + "\nПерестроения страниц в процессе: "
        + builds
        + "\n\n"
        + "Это не общий расход памяти и не FPS. Полные измерения собирает tv-check.py через ADB.\n"
        + "Время predraw — подготовка к отрисовке, а не завершённый кадр.";
  }
}
