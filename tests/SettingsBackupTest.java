package ru.sasha.tvlauncher;

import android.content.*;
import java.io.*;
import java.util.*;
import org.json.*;

public class SettingsBackupTest {
  static int checks;

  static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("backup " + checks);
  }

  interface Attempt {
    void run() throws Exception;
  }

  static void rejected(Attempt action) throws Exception {
    try {
      action.run();
      throw new AssertionError("invalid copy accepted");
    } catch (JSONException | IOException expected) {
      checks++;
    }
  }

  static JSONObject valid() throws Exception {
    return new JSONObject()
        .put("format", BackupData.FORMAT)
        .put("version", 1)
        .put("createdAt", 42)
        .put("apps", new JSONArray().put("com.google.android.youtube.tv").put("top.rootu.lampa"))
        .put(
            "films",
            new JSONArray()
                .put(new JSONObject().put("id", "42").put("type", "movie").put("title", "Фильм")))
        .put("sources", new JSONArray().put("trending").put("movies"))
        .put("motion", false)
        .put(
            "catalogs",
            new JSONObject()
                .put("movie", new JSONObject().put("page", 4))
                .put("tv", new JSONObject()))
        .put(
            "hdmi",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("id", "pkg/.Input/HW1")
                        .put("name", "PlayStation")
                        .put("type", "playstation")));
  }

  public static void main(String[] args) throws Exception {
    BackupData parsed = BackupData.parse(valid().toString());
    check(parsed.json.getJSONArray("apps").length() == 2);
    check(parsed.json.getJSONArray("films").getJSONObject(0).getString("title").equals("Фильм"));
    check(parsed.json.getJSONObject("catalogs").getJSONObject("movie").getInt("page") == 4);
    check(!parsed.json.getBoolean("motion"));
    check(parsed.summary().contains("Избранные фильмы и сериалы: 1"));
    check(BackupData.parse(parsed.json.toString()).json.toString().equals(parsed.json.toString()));
    JSONObject extras = valid().put("secret", "DO_NOT_EXPORT").put("vpn", "DO_NOT_EXPORT");
    extras.getJSONArray("films").getJSONObject(0).put("accessToken", "DO_NOT_EXPORT");
    extras.getJSONObject("catalogs").getJSONObject("tv").put("secret", "DO_NOT_EXPORT");
    check(!BackupData.parse(extras.toString()).json.toString().contains("DO_NOT_EXPORT"));
    rejected(() -> BackupData.parse("{}"));
    rejected(() -> BackupData.parse("broken"));
    String deep =
        String.join("", Collections.nCopies(100, "["))
            + "0"
            + String.join("", Collections.nCopies(100, "]"));
    rejected(() -> BackupData.parse(deep));
    rejected(() -> BackupData.parse(valid().put("version", 2).toString()));
    rejected(() -> BackupData.parse(valid().put("motion", "false").toString()));
    rejected(
        () -> BackupData.parse(valid().put("sources", new JSONArray().put("unknown")).toString()));
    rejected(
        () ->
            BackupData.parse(
                valid().put("sources", new JSONArray().put("tv").put("tv")).toString()));
    rejected(
        () ->
            BackupData.parse(valid().put("apps", new JSONArray().put("../../secret")).toString()));
    rejected(
        () ->
            BackupData.parse(
                valid().put("apps", new JSONArray().put("a.b").put("a.b")).toString()));
    JSONObject badFilm = valid();
    badFilm.getJSONArray("films").getJSONObject(0).put("id", "0");
    rejected(() -> BackupData.parse(badFilm.toString()));
    JSONObject badPort = valid();
    badPort.getJSONArray("hdmi").getJSONObject(0).put("type", "evil");
    rejected(() -> BackupData.parse(badPort.toString()));
    JSONObject controls = valid();
    controls.getJSONArray("hdmi").getJSONObject(0).put("id", "bad\nname");
    rejected(() -> BackupData.parse(controls.toString()));
    byte[] oversized = new byte[BackupData.LIMIT + 1];
    Arrays.fill(oversized, (byte) ' ');
    rejected(() -> SettingsBackup.read(new ByteArrayInputStream(oversized)));
    final boolean[] closed = {false};
    InputStream bounded =
        new ByteArrayInputStream(valid().toString().getBytes("UTF-8")) {
          public void close() throws IOException {
            closed[0] = true;
            super.close();
          }
        };
    check(SettingsBackup.read(bounded).json.getInt("version") == 1);
    check(closed[0]);
    rejected(() -> SettingsBackup.read((InputStream) null));
    FakeContext context = new FakeContext();
    context
        .getSharedPreferences("tmdb_catalog", 0)
        .edit()
        .putString("secret", "TOKEN_UNCHANGED")
        .commit();
    context
        .getSharedPreferences("vpn_control", 0)
        .edit()
        .putString("tunnel", "VPN_UNCHANGED")
        .commit();
    context
        .getSharedPreferences("sasha_interface", 0)
        .edit()
        .putString("extra", "EXTRA_UNCHANGED")
        .commit();
    context
        .getSharedPreferences("hdmi_devices", 0)
        .edit()
        .putString("last", "LAST_UNCHANGED")
        .putString("name:old", "Old")
        .commit();
    java.lang.reflect.Method apply =
        SettingsBackup.class.getDeclaredMethod("apply", Context.class, BackupData.class);
    apply.setAccessible(true);
    apply.invoke(null, context, parsed);
    check(
        context
            .getSharedPreferences("tmdb_catalog", 0)
            .getString("secret", "")
            .equals("TOKEN_UNCHANGED"));
    check(
        context
            .getSharedPreferences("vpn_control", 0)
            .getString("tunnel", "")
            .equals("VPN_UNCHANGED"));
    check(
        context
            .getSharedPreferences("sasha_interface", 0)
            .getString("extra", "")
            .equals("EXTRA_UNCHANGED"));
    check(
        context
            .getSharedPreferences("hdmi_devices", 0)
            .getString("last", "")
            .equals("LAST_UNCHANGED"));
    check(!context.getSharedPreferences("hdmi_devices", 0).contains("name:old"));
    check(
        context
            .getSharedPreferences("hdmi_devices", 0)
            .getString("name:pkg/.Input/HW1", "")
            .equals("PlayStation"));
    check(
        context.getSharedPreferences("favorite_films", 0).getString("items", "").contains("Фильм"));
    check(!context.getSharedPreferences("sasha_interface", 0).getBoolean("motion", true));
    BackupData captured = SettingsBackup.capture(context);
    check(!captured.json.toString().contains("TOKEN_UNCHANGED"));
    check(!captured.json.toString().contains("VPN_UNCHANGED"));
    check(!captured.json.toString().contains("EXTRA_UNCHANGED"));
    check(
        captured
            .json
            .getJSONArray("apps")
            .toString()
            .equals(parsed.json.getJSONArray("apps").toString()));
    check(
        captured
            .json
            .getJSONArray("hdmi")
            .toString()
            .equals(parsed.json.getJSONArray("hdmi").toString()));
    System.out.println("PASS: settings backup " + checks + " checks");
  }

  static class FakeContext extends ContextWrapper {
    final Map<String, SharedPreferences> files = new HashMap<>();

    FakeContext() {
      super(null);
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
      if (!files.containsKey(name)) files.put(name, new Prefs());
      return files.get(name);
    }
  }

  static class Prefs implements SharedPreferences {
    final Map<String, Object> map = new HashMap<>();

    public Map<String, ?> getAll() {
      return new HashMap<>(map);
    }

    public String getString(String k, String d) {
      return (String) map.getOrDefault(k, d);
    }

    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String k, Set<String> d) {
      return (Set<String>) map.getOrDefault(k, d);
    }

    public int getInt(String k, int d) {
      return (Integer) map.getOrDefault(k, d);
    }

    public long getLong(String k, long d) {
      return (Long) map.getOrDefault(k, d);
    }

    public float getFloat(String k, float d) {
      return (Float) map.getOrDefault(k, d);
    }

    public boolean getBoolean(String k, boolean d) {
      return (Boolean) map.getOrDefault(k, d);
    }

    public boolean contains(String k) {
      return map.containsKey(k);
    }

    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

    public Editor edit() {
      return new Editor() {
        final Map<String, Object> put = new HashMap<>();
        boolean clear = false;

        public Editor putString(String k, String v) {
          put.put(k, v);
          return this;
        }

        public Editor putStringSet(String k, Set<String> v) {
          put.put(k, v);
          return this;
        }

        public Editor putInt(String k, int v) {
          put.put(k, v);
          return this;
        }

        public Editor putLong(String k, long v) {
          put.put(k, v);
          return this;
        }

        public Editor putFloat(String k, float v) {
          put.put(k, v);
          return this;
        }

        public Editor putBoolean(String k, boolean v) {
          put.put(k, v);
          return this;
        }

        public Editor remove(String k) {
          put.put(k, null);
          return this;
        }

        public Editor clear() {
          clear = true;
          return this;
        }

        public boolean commit() {
          if (clear) map.clear();
          for (String k : put.keySet())
            if (put.get(k) == null) map.remove(k);
            else map.put(k, put.get(k));
          return true;
        }

        public void apply() {
          commit();
        }
      };
    }
  }
}
