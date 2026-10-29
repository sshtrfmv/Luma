package ru.sasha.tvlauncher;

import java.util.*;
import org.json.*;

final class FilmDetails {
  final CatalogManager.Film film;
  final String poster, genres;
  final double rating;
  final int votes, runtime, minEpisode, maxEpisode, seasons, episodes;

  private FilmDetails(
      CatalogManager.Film film,
      String poster,
      String genres,
      double rating,
      int votes,
      int runtime,
      int minEpisode,
      int maxEpisode,
      int seasons,
      int episodes) {
    this.film = film;
    this.poster = poster;
    this.genres = genres;
    this.rating = rating;
    this.votes = votes;
    this.runtime = runtime;
    this.minEpisode = minEpisode;
    this.maxEpisode = maxEpisode;
    this.seasons = seasons;
    this.episodes = episodes;
  }

  static String url(String type, String id) {
    if (!Arrays.asList("movie", "tv").contains(type)
        || id == null
        || !id.matches("[1-9][0-9]{0,15}")) throw new IllegalArgumentException("film");
    return "https://api.themoviedb.org/3/" + type + "/" + id + "?language=ru-RU";
  }

  static FilmDetails parse(String raw, String type, String id) throws JSONException {
    url(type, id);
    JSONObject j = new JSONObject(raw);
    if (!id.equals(j.optString("id")) || j.optBoolean("adult", false))
      throw new JSONException("Unexpected film");
    String title = text(j, type.equals("tv") ? "name" : "title");
    if (title.isEmpty()) throw new JSONException("Missing title");
    String date = text(j, type.equals("tv") ? "first_air_date" : "release_date");
    String poster = text(j, "poster_path"), image = text(j, "backdrop_path");
    if (!CatalogManager.validPath(poster)) poster = "";
    if (!CatalogManager.validPath(image)) image = poster;
    LinkedHashSet<String> genres = new LinkedHashSet<>();
    JSONArray g = j.optJSONArray("genres");
    if (g != null)
      for (int i = 0; i < g.length() && genres.size() < 12; i++) {
        JSONObject entry = g.optJSONObject(i);
        if (entry != null && !text(entry, "name").isEmpty()) genres.add(text(entry, "name"));
      }
    int min = 0, max = 0;
    JSONArray times = j.optJSONArray("episode_run_time");
    if (times != null)
      for (int i = 0; i < times.length(); i++) {
        int t = times.optInt(i);
        if (t > 0 && t <= 1440) {
          min = min == 0 ? t : Math.min(min, t);
          max = Math.max(max, t);
        }
      }
    double rating = j.optDouble("vote_average", 0);
    if (!Double.isFinite(rating) || rating < 0 || rating > 10) rating = 0;
    return new FilmDetails(
        new CatalogManager.Film(
            id,
            type,
            title,
            date.matches("\\d{4}-.*") ? date.substring(0, 4) : "",
            text(j, "overview"),
            image),
        poster,
        String.join(" · ", genres),
        rating,
        bounded(j.optInt("vote_count"), 0, Integer.MAX_VALUE),
        bounded(j.optInt("runtime"), 0, 100000),
        min,
        max,
        bounded(j.optInt("number_of_seasons"), 0, 10000),
        bounded(j.optInt("number_of_episodes"), 0, 1000000));
  }

  private static int bounded(int n, int min, int max) {
    return Math.max(min, Math.min(max, n));
  }

  private static String text(JSONObject o, String key) {
    return o.isNull(key) ? "" : o.optString(key, "").trim();
  }

  String ratingLabel() {
    return votes == 0 || rating == 0
        ? "Рейтинг TMDB пока не указан"
        : "TMDB " + String.format(new Locale("ru"), "%.1f", rating) + " / 10 · оценок: " + votes;
  }

  String runtimeLabel() {
    if (film.type.equals("movie"))
      return runtime <= 0
          ? "Длительность не указана"
          : (runtime >= 60 ? runtime / 60 + " ч " : "") + (runtime % 60) + " мин";
    String length =
        minEpisode == 0
            ? "Длительность серии не указана"
            : "Серия: " + minEpisode + (minEpisode != maxEpisode ? "–" + maxEpisode : "") + " мин";
    if (seasons > 0) length += " · сезонов: " + seasons;
    if (episodes > 0) length += " · серий: " + episodes;
    return length;
  }
}
