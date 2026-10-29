package ru.sasha.tvlauncher;

import org.json.*;

public class CatalogDetailsTest {
  static int checks;

  static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("details " + checks);
  }

  static String movie() throws Exception {
    return new JSONObject()
        .put("id", 42)
        .put("title", "Фильм")
        .put("release_date", "2024-01-02")
        .put("overview", "Полное описание")
        .put("poster_path", "/poster.jpg")
        .put("backdrop_path", "/art.jpg")
        .put("runtime", 166)
        .put("vote_average", 8.2)
        .put("vote_count", 1234)
        .put(
            "genres",
            new JSONArray()
                .put(new JSONObject().put("name", "Фантастика"))
                .put(new JSONObject().put("name", "Драма")))
        .toString();
  }

  public static void main(String[] args) throws Exception {
    FilmDetails d = FilmDetails.parse(movie(), "movie", "42");
    check(d.film.title.equals("Фильм"));
    check(d.film.year.equals("2024"));
    check(d.film.overview.equals("Полное описание"));
    check(d.poster.equals("/poster.jpg"));
    check(d.film.image.equals("/art.jpg"));
    check(d.genres.equals("Фантастика · Драма"));
    check(d.runtimeLabel().equals("2 ч 46 мин"));
    check(d.ratingLabel().contains("8,2"));
    check(d.ratingLabel().contains("1234"));
    JSONObject j =
        new JSONObject(movie())
            .put("poster_path", "https://bad/a.jpg")
            .put("backdrop_path", JSONObject.NULL)
            .put("runtime", 0)
            .put("vote_count", 0);
    d = FilmDetails.parse(j.toString(), "movie", "42");
    check(d.poster.isEmpty());
    check(d.film.image.isEmpty());
    check(d.runtimeLabel().equals("Длительность не указана"));
    check(d.ratingLabel().contains("не указан"));
    d =
        FilmDetails.parse(
            "{\"id\":7,\"name\":\"Сериал\",\"episode_run_time\":[45,60,0,-1],\"number_of_seasons\":2,\"number_of_episodes\":20}",
            "tv",
            "7");
    check(d.runtimeLabel().contains("45–60"));
    check(d.runtimeLabel().contains("сезонов: 2"));
    check(d.runtimeLabel().contains("серий: 20"));
    d = FilmDetails.parse("{\"id\":7,\"name\":\"Сериал\",\"episode_run_time\":[]}", "tv", "7");
    check(d.runtimeLabel().equals("Длительность серии не указана"));
    boolean bad = false;
    try {
      FilmDetails.parse(movie(), "movie", "43");
    } catch (JSONException expected) {
      bad = true;
    }
    check(bad);
    bad = false;
    try {
      FilmDetails.url("movie", "../42");
    } catch (IllegalArgumentException expected) {
      bad = true;
    }
    check(bad);
    bad = false;
    try {
      FilmDetails.url("person", "42");
    } catch (IllegalArgumentException expected) {
      bad = true;
    }
    check(bad);
    bad = false;
    try {
      FilmDetails.parse(new JSONObject(movie()).put("adult", true).toString(), "movie", "42");
    } catch (JSONException expected) {
      bad = true;
    }
    check(bad);
    check(
        FilmDetails.url("movie", "42")
            .equals("https://api.themoviedb.org/3/movie/42?language=ru-RU"));
    check(FilmDetails.url("tv", "7").contains("/tv/7?"));
    CatalogPosition p = new CatalogPosition();
    p.genre = 28;
    p.year = 2024;
    p.rating = 8;
    p.sort = 1;
    p.page = 4;
    p.scroll = 1200;
    p.focus = "catalog:movie:42";
    CatalogPosition copy = CatalogPosition.decode(p.encode());
    check(copy.genre == 28 && copy.year == 2024 && copy.rating == 8 && copy.sort == 1);
    check(copy.page == 4 && copy.scroll == 1200 && copy.focus.equals("catalog:movie:42"));
    copy = CatalogPosition.decode("broken");
    check(copy.page == 1 && copy.scroll == 0 && copy.focus.isEmpty());
    copy =
        CatalogPosition.decode(
            "{\"genre\":-2,\"year\":9999,\"rating\":5,\"sort\":90,\"page\":-4,\"scroll\":-10,\"focus\":\"drawer:settings\"}");
    check(copy.genre == 0 && copy.year == 0 && copy.rating == 0);
    check(copy.sort == 2 && copy.page == 1 && copy.scroll == 0 && copy.focus.isEmpty());
    copy =
        CatalogPosition.decode(
            "{\"page\":9999,\"scroll\":999999,\"focus\":\"filter:retry-error\"}");
    check(copy.page == 500 && copy.scroll == 50000 && copy.focus.equals("filter:retry-error"));
    copy = CatalogPosition.decode("{\"focus\":\"catalog:tv:7\"}");
    check(copy.focus.equals("catalog:tv:7"));
    System.out.println("PASS: catalog details " + checks + " checks");
  }
}
