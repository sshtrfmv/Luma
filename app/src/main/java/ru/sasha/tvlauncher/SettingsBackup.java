package ru.sasha.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Bounded file IO and whitelisted restoration, called on the background executor. */
final class SettingsBackup {
  static final String EXPORT = "sashatv-settings.json",
      IMPORT = "sashatv-import.json",
      PREVIOUS = "sashatv-before-restore.json";

  static BackupData capture(Context c) throws Exception {
    JSONObject data =
        new JSONObject()
            .put("format", BackupData.FORMAT)
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis());
    String apps = c.getSharedPreferences("sasha_favorites", 0).getString("packages", null);
    if (apps == null)
      apps =
          new JSONArray(
                  Arrays.asList(
                      "com.google.android.youtube.tv",
                      "top.rootu.lampa",
                      "com.vk.tv",
                      "org.videolan.vlc",
                      "mobi.zona",
                      "org.amnezia.vpn",
                      "org.amnezia.awg"))
              .toString();
    data.put("apps", new JSONArray(apps));
    data.put("films", new JSONArray(FilmFavorites.encode(new FilmFavorites(c).all())));
    data.put(
        "sources",
        new JSONArray(
            HomeFeeds.selected(
                c.getSharedPreferences("home_layout", 0).getString("sources", "[]"))));
    data.put("motion", c.getSharedPreferences("sasha_interface", 0).getBoolean("motion", true));
    JSONObject positions = new JSONObject();
    for (String type : new String[] {"movie", "tv"})
      positions.put(
          type,
          new JSONObject(
              CatalogPosition.decode(
                      c.getSharedPreferences("catalog_position", 0).getString(type, "{}"))
                  .encode()));
    data.put("catalogs", positions);
    SharedPreferences hdmi = c.getSharedPreferences("hdmi_devices", 0);
    Set<String> ids = new TreeSet<>();
    for (String key : hdmi.getAll().keySet())
      if (key.startsWith("name:") || key.startsWith("type:")) ids.add(key.substring(5));
    JSONArray ports = new JSONArray();
    for (String id : ids)
      ports.put(
          new JSONObject()
              .put("id", id)
              .put("name", hdmi.getString("name:" + id, ""))
              .put("type", hdmi.getString("type:" + id, "auto")));
    data.put("hdmi", ports);
    return BackupData.parse(data.toString());
  }

  static void write(Context c, String filename, BackupData data) throws IOException {
    AtomicFile file = new AtomicFile(new File(c.getFilesDir(), filename));
    FileOutputStream stream = null;
    try {
      stream = file.startWrite();
      stream.write(data.json.toString().getBytes(StandardCharsets.UTF_8));
      file.finishWrite(stream);
    } catch (IOException error) {
      if (stream != null) file.failWrite(stream);
      throw error;
    }
  }

  static BackupData read(InputStream input) throws Exception {
    if (input == null) throw new IOException("Файл недоступен");
    try (InputStream in = input;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) != -1) {
        if (out.size() + count > BackupData.LIMIT)
          throw new IOException("Размер копии превышает 2 МБ");
        out.write(buffer, 0, count);
      }
      return BackupData.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
    }
  }

  static BackupData read(Context c, String filename) throws Exception {
    return read(new AtomicFile(new File(c.getFilesDir(), filename)).openRead());
  }

  static void restore(Context c, BackupData data) throws Exception {
    BackupData before = capture(c);
    write(c, PREVIOUS, before);
    try {
      apply(c, data);
    } catch (Exception error) {
      try {
        apply(c, before);
      } catch (Exception rollback) {
        throw new IOException(
            "Восстановление прервано. Копия прежних настроек сохранена; нужен повтор"
                + " восстановления.",
            rollback);
      }
      throw new IOException("Не удалось сохранить изменения; прежние настройки возвращены", error);
    }
  }

  private static void commit(SharedPreferences.Editor editor) throws IOException {
    if (!editor.commit()) throw new IOException("Не удалось сохранить настройки");
  }

  private static void apply(Context c, BackupData data) throws Exception {
    JSONObject d = data.json;
    commit(
        c.getSharedPreferences("sasha_favorites", 0)
            .edit()
            .putString("packages", d.getJSONArray("apps").toString()));
    SharedPreferences films = c.getSharedPreferences("favorite_films", 0);
    commit(
        films
            .edit()
            .putString("backup", films.getString("items", "[]"))
            .putString("items", d.getJSONArray("films").toString()));
    commit(
        c.getSharedPreferences("home_layout", 0)
            .edit()
            .putString("sources", d.getJSONArray("sources").toString()));
    commit(
        c.getSharedPreferences("sasha_interface", 0)
            .edit()
            .putBoolean("motion", d.getBoolean("motion")));
    SharedPreferences.Editor positions = c.getSharedPreferences("catalog_position", 0).edit();
    for (String type : new String[] {"movie", "tv"})
      positions.putString(type, d.getJSONObject("catalogs").getJSONObject(type).toString());
    commit(positions);
    SharedPreferences prefs = c.getSharedPreferences("hdmi_devices", 0);
    SharedPreferences.Editor hdmi = prefs.edit();
    for (String key : prefs.getAll().keySet())
      if (key.startsWith("name:") || key.startsWith("type:")) hdmi.remove(key);
    JSONArray ports = d.getJSONArray("hdmi");
    for (int i = 0; i < ports.length(); i++) {
      JSONObject port = ports.getJSONObject(i);
      String id = port.getString("id");
      hdmi.putString("name:" + id, port.getString("name"))
          .putString("type:" + id, port.getString("type"));
    }
    commit(hdmi);
  }
}
