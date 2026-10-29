package ru.sasha.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Independent of the TMDB token/cache and of favorite application ordering. */
final class FilmFavorites {
  private final SharedPreferences prefs;

  FilmFavorites(Context context) {
    prefs = context.getSharedPreferences("favorite_films", Context.MODE_PRIVATE);
  }

  static List<CatalogManager.Film> decode(String raw) throws JSONException {
    JSONArray entries = new JSONArray(raw);
    List<CatalogManager.Film> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject entry = entries.optJSONObject(i);
      if (entry == null) continue;
      String id = entry.optString("id", "");
      String type = entry.optString("type", "");
      String title = entry.optString("title", "").trim();
      if (!id.matches("[1-9][0-9]{0,15}")
          || (!type.equals("movie") && !type.equals("tv"))
          || title.isEmpty()
          || title.equals("null")
          || !seen.add(type + ":" + id)) continue;
      String image = entry.optString("image", "");
      if (!CatalogManager.validPath(image)) image = "";
      result.add(
          new CatalogManager.Film(
              id,
              type,
              title,
              entry.optString("year", ""),
              entry.optString("overview", ""),
              image));
    }
    return result;
  }

  static String encode(List<CatalogManager.Film> films) throws JSONException {
    JSONArray array = new JSONArray();
    for (CatalogManager.Film f : films)
      array.put(
          new JSONObject()
              .put("id", f.id)
              .put("type", f.type)
              .put("title", f.title)
              .put("year", f.year)
              .put("overview", f.overview)
              .put("image", f.image));
    return array.toString();
  }

  static boolean contains(List<CatalogManager.Film> films, CatalogManager.Film target) {
    for (CatalogManager.Film film : films)
      if (film.id.equals(target.id) && film.type.equals(target.type)) return true;
    return false;
  }

  static List<CatalogManager.Film> toggled(
      List<CatalogManager.Film> films, CatalogManager.Film target) {
    ArrayList<CatalogManager.Film> result = new ArrayList<>(films);
    if (contains(films, target))
      result.removeIf(film -> film.id.equals(target.id) && film.type.equals(target.type));
    else result.add(0, target);
    return result;
  }

  List<CatalogManager.Film> all() {
    try {
      return decode(prefs.getString("items", "[]"));
    } catch (JSONException failure) {
      try {
        return decode(prefs.getString("backup", "[]"));
      } catch (JSONException ignored) {
        return Collections.emptyList();
      }
    }
  }

  boolean contains(CatalogManager.Film target) {
    return contains(all(), target);
  }

  boolean toggle(CatalogManager.Film target) throws Exception {
    List<CatalogManager.Film> current = all();
    boolean adding = !contains(current, target);
    if (!prefs
        .edit()
        .putString("backup", encode(current))
        .putString("items", encode(toggled(current, target)))
        .commit()) throw new IOException("Cannot save favorites");
    return adding;
  }
}
