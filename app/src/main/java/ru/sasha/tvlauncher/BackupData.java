package ru.sasha.tvlauncher;

import java.util.*;
import org.json.*;

/** Whitelisted, versioned settings format. Never serializes arbitrary preferences. */
final class BackupData {
  static final int LIMIT = 2 * 1024 * 1024;
  static final String FORMAT = "sasha-tv-settings";
  final JSONObject json;

  private BackupData(JSONObject json) {
    this.json = json;
  }

  static BackupData parse(String raw) throws JSONException {
    if (raw == null
        || raw.length() > LIMIT
        || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > LIMIT)
      throw new JSONException("Размер копии превышает 2 МБ");
    int depth = 0;
    boolean quoted = false, escaped = false;
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (ch == '\\') escaped = true;
        else if (ch == '"') quoted = false;
      } else if (ch == '"') quoted = true;
      else if (ch == '{' || ch == '[') {
        if (++depth > 32) throw new JSONException("Слишком сложная структура копии");
      } else if (ch == '}' || ch == ']') depth--;
    }
    JSONObject source = new JSONObject(raw);
    if (!FORMAT.equals(source.optString("format")) || source.optInt("version") != 1)
      throw new JSONException("Неизвестный формат или версия копии");
    JSONObject safe =
        new JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("createdAt", Math.max(0, source.optLong("createdAt", 0)));
    JSONArray apps = source.getJSONArray("apps");
    if (apps.length() > 200) throw new JSONException("Слишком много приложений");
    JSONArray checkedApps = new JSONArray();
    Set<String> packages = new HashSet<>();
    for (int i = 0; i < apps.length(); i++) {
      Object value = apps.get(i);
      if (!(value instanceof String)
          || !((String) value).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
          || ((String) value).length() > 255
          || !packages.add((String) value))
        throw new JSONException("Некорректный список приложений");
      checkedApps.put(value);
    }
    safe.put("apps", checkedApps);
    JSONArray films = source.getJSONArray("films");
    if (films.length() > 1000) throw new JSONException("В копии больше 1000 фильмов");
    for (int i = 0; i < films.length(); i++) {
      JSONObject film = films.getJSONObject(i);
      if (film.optString("title", "").length() > 500
          || film.optString("overview", "").length() > 16000
          || film.optString("year", "").length() > 32)
        throw new JSONException("Слишком длинные данные фильма");
    }
    List<CatalogManager.Film> decoded = FilmFavorites.decode(films.toString());
    if (decoded.size() != films.length())
      throw new JSONException("Повреждено избранное или есть повторения");
    safe.put("films", new JSONArray(FilmFavorites.encode(decoded)));
    JSONArray sources = source.getJSONArray("sources"), checkedSources = new JSONArray();
    Set<String> seen = new HashSet<>();
    if (sources.length() < 1 || sources.length() > 3)
      throw new JSONException("Нужно от одной до трёх подборок");
    for (int i = 0; i < sources.length(); i++) {
      String id = sources.getString(i);
      if (!Arrays.asList(HomeFeeds.ids()).contains(id) || !seen.add(id))
        throw new JSONException("Неизвестная подборка");
      checkedSources.put(id);
    }
    safe.put("sources", checkedSources);
    Object motion = source.get("motion");
    if (!(motion instanceof Boolean)) throw new JSONException("Некорректный параметр анимаций");
    safe.put("motion", motion);
    JSONObject positions = source.getJSONObject("catalogs"), checkedPositions = new JSONObject();
    for (String type : new String[] {"movie", "tv"})
      checkedPositions.put(
          type,
          new JSONObject(
              CatalogPosition.decode(positions.getJSONObject(type).toString()).encode()));
    safe.put("catalogs", checkedPositions);
    JSONArray hdmi = source.getJSONArray("hdmi"), checkedHdmi = new JSONArray();
    if (hdmi.length() > 64) throw new JSONException("Слишком много HDMI-устройств");
    seen.clear();
    for (int i = 0; i < hdmi.length(); i++) {
      JSONObject port = hdmi.getJSONObject(i);
      String id = port.getString("id"),
          name = port.getString("name"),
          type = port.getString("type");
      if (id.isEmpty()
          || id.length() > 1024
          || name.length() > 80
          || !seen.add(id)
          || !Arrays.asList(HdmiLogic.TYPES).contains(type))
        throw new JSONException("Некорректные данные HDMI");
      for (char c : (id + name).toCharArray())
        if (Character.isISOControl(c)) throw new JSONException("Некорректные символы HDMI");
      checkedHdmi.put(new JSONObject().put("id", id).put("name", name).put("type", type));
    }
    safe.put("hdmi", checkedHdmi);
    if (safe.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > LIMIT)
      throw new JSONException("Размер нормализованной копии превышает 2 МБ");
    return new BackupData(safe);
  }

  String summary() throws JSONException {
    return "Приложения: "
        + json.getJSONArray("apps").length()
        + "\nИзбранные фильмы и сериалы: "
        + json.getJSONArray("films").length()
        + "\nПодборки: "
        + json.getJSONArray("sources").length()
        + "\nНазвания HDMI: "
        + json.getJSONArray("hdmi").length()
        + "\n\nТакже будут заменены фильтры каталогов и настройка анимаций."
        + "\nТокен TMDB и конфигурация VPN останутся прежними.";
  }
}
