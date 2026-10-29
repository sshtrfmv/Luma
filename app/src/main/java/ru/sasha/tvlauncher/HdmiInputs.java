package ru.sasha.tvlauncher;

import android.content.*;
import android.media.tv.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read public TV-input metadata and deliver immutable snapshots on the main thread. */
final class HdmiInputs {
  private final Context context;
  private final TvInputManager manager;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean reading = new AtomicBoolean(), again = new AtomicBoolean();
  private volatile boolean closed = false, active = false;
  private volatile int epoch = 0;
  private boolean registered;
  private Runnable changed;
  private List<HdmiLogic.Port> ports = Collections.emptyList();
  private String message = "Читаем HDMI-входы…";
  private final TvInputManager.TvInputCallback callback =
      new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String id) {
          refresh();
        }

        @Override
        public void onInputRemoved(String id) {
          refresh();
        }

        @Override
        public void onInputUpdated(String id) {
          refresh();
        }

        @Override
        public void onInputStateChanged(String id, int state) {
          refresh();
        }

        @Override
        public void onTvInputInfoUpdated(TvInputInfo info) {
          refresh();
        }
      };

  HdmiInputs(Context context) {
    this.context = context.getApplicationContext();
    manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
  }

  List<HdmiLogic.Port> ports() {
    return ports;
  }

  String message() {
    return message;
  }

  void start(Runnable changed) {
    this.changed = changed;
    active = true;
    epoch++;
    if (manager != null && !registered)
      try {
        manager.registerCallback(callback, main);
        registered = true;
      } catch (RuntimeException failure) {
        log(failure);
      }
    refresh();
  }

  void stop() {
    active = false;
    epoch++;
    changed = null;
    if (registered) {
      try {
        manager.unregisterCallback(callback);
      } catch (RuntimeException failure) {
        log(failure);
      }
      registered = false;
    }
  }

  void refresh() {
    if (closed || !active) return;
    if (!reading.compareAndSet(false, true)) {
      again.set(true);
      return;
    }
    final int request = epoch;
    worker.execute(
        () -> {
          List<HdmiLogic.Input> records = new ArrayList<>();
          String error = null;
          try {
            if (manager == null) error = "Прошивка не предоставляет службу TV Input";
            else
              for (TvInputInfo info : manager.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (records.size() >= 64) break;
                int state = -1;
                try {
                  state = manager.getInputState(info.getId());
                } catch (RuntimeException ignored) {
                }
                CharSequence custom = info.loadCustomLabel(context),
                    label = info.loadLabel(context);
                records.add(
                    new HdmiLogic.Input(
                        info.getId(),
                        info.getParentId(),
                        label == null ? "" : label.toString(),
                        custom == null ? "" : custom.toString(),
                        state));
              }
          } catch (RuntimeException failure) {
            error =
                failure instanceof SecurityException
                    ? "Прошивка ограничила доступ к HDMI-входам"
                    : "Не удалось прочитать HDMI-входы";
            log(failure);
          }
          final List<HdmiLogic.Port> result = HdmiLogic.group(records);
          final String failure = error;
          main.post(
              () -> {
                reading.set(false);
                if (!closed && active && request == epoch) {
                  ports = result;
                  int connected = 0, standby = 0, disconnected = 0, unknown = 0;
                  for (HdmiLogic.Port port : result) {
                    if (port.state == TvInputManager.INPUT_STATE_CONNECTED) connected++;
                    else if (port.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY) standby++;
                    else if (port.state == TvInputManager.INPUT_STATE_DISCONNECTED) disconnected++;
                    else unknown++;
                  }
                  android.util.Log.i(
                      AppDiagnostics.TAG,
                      "hdmi_snapshot ports="
                          + result.size()
                          + " connected="
                          + connected
                          + " standby="
                          + standby
                          + " disconnected="
                          + disconnected
                          + " unknown="
                          + unknown
                          + " error="
                          + (failure != null));
                  message =
                      failure != null
                          ? failure
                          : result.isEmpty()
                              ? "Телевизор не предоставил HDMI-входы"
                              : "Данные TV Input · имя устройства — если доступно через CEC";
                  if (changed != null) changed.run();
                }
                if (again.getAndSet(false) && active && !closed) refresh();
              });
        });
  }

  private static void log(RuntimeException failure) {
    android.util.Log.w("SashaTV", "HDMI metadata failed: " + failure.getClass().getSimpleName());
  }

  void close() {
    stop();
    closed = true;
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
  }
}
