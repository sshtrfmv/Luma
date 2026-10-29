package ru.sasha.tvlauncher;

import java.util.*;
import org.json.*;

final class HomeFeeds {
  static String contentKey(
      boolean configured, List<String> sources, Map<String, List<CatalogManager.Film>> feeds) {
    JSONArray result = new JSONArray().put(configured);
    for (String source : sources) {
      JSONArray row = new JSONArray().put(source);
      List<CatalogManager.Film> films = feeds.get(source);
      if (films != null)
        for (CatalogManager.Film film : films)
          row.put(
              new JSONArray()
                  .put(film.id)
                  .put(film.type)
                  .put(film.title)
                  .put(film.year)
                  .put(film.overview)
                  .put(film.image));
      result.put(row);
    }
    return result.toString();
  }

  static String[] ids() {
    return new String[] {"trending", "movies", "tv", "rated", "cinema", "upcoming"};
  }

  static String title(String id) {
    switch (id) {
      case "trending":
        return "Популярное за неделю";
      case "movies":
        return "Популярные фильмы";
      case "tv":
        return "Популярные сериалы";
      case "rated":
        return "Высокий рейтинг";
      case "cinema":
        return "Сейчас в кино";
      case "upcoming":
        return "Скоро в кино";
      default:
        throw new IllegalArgumentException("feed");
    }
  }

  static List<String> selected(String raw) {
    ArrayList<String> result = new ArrayList<>();
    List<String> valid = Arrays.asList(ids());
    try {
      JSONArray array = new JSONArray(raw);
      for (int i = 0; i < array.length() && result.size() < 3; i++) {
        String id = array.optString(i);
        if (valid.contains(id) && !result.contains(id)) result.add(id);
      }
    } catch (JSONException ignored) {
    }
    if (result.isEmpty()) result.add("trending");
    return result;
  }

  static String url(String id) {
    String path;
    switch (id) {
      case "trending":
        path = "trending/all/week";
        break;
      case "movies":
        path = "movie/popular";
        break;
      case "tv":
        path = "tv/popular";
        break;
      case "rated":
        path = "movie/top_rated";
        break;
      case "cinema":
        path = "movie/now_playing";
        break;
      case "upcoming":
        path = "movie/upcoming";
        break;
      default:
        throw new IllegalArgumentException("feed");
    }
    return "https://api.themoviedb.org/3/" + path + "?language=ru-RU";
  }

  static int step(int index, int delta, int count) {
    return count <= 0 ? 0 : Math.floorMod(index + delta, count);
  }
}
