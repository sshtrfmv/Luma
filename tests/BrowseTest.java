package ru.sasha.tvlauncher;

public class BrowseTest {
  private static int checks = 0;

  private static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("Browse check " + checks);
  }

  public static void main(String[] args) throws Exception {
    String movie = BrowseLogic.query("movie", 28, 2024, 7, 1, 3);
    check(movie.contains("/discover/movie?"));
    check(movie.contains("primary_release_year=2024"));
    check(movie.contains("with_genres=28"));
    check(movie.contains("vote_average.gte=7"));
    check(movie.contains("vote_count.gte=100"));
    check(movie.contains("sort_by=vote_average.desc"));
    check(movie.contains("page=3"));
    String tv = BrowseLogic.query("tv", 0, 2023, 0, 0, 1);
    check(tv.contains("first_air_date_year=2023"));
    check(!tv.contains("primary_release_year"));
    check(!tv.contains("with_genres="));
    check(!tv.contains("vote_average.gte"));
    String fresh = BrowseLogic.query("tv", 0, 0, 0, 2, 1);
    check(fresh.contains("first_air_date.desc"));
    check(fresh.contains("first_air_date.lte="));
    for (String type : new String[] {"person", "../bad", ""}) {
      boolean invalid = false;
      try {
        BrowseLogic.query(type, 0, 0, 0, 0, 1);
      } catch (IllegalArgumentException e) {
        invalid = true;
      }
      check(invalid);
    }
    boolean invalid = false;
    try {
      BrowseLogic.query("movie", 0, 0, 0, 0, 501);
    } catch (IllegalArgumentException e) {
      invalid = true;
    }
    check(invalid);
    String genres = "{\"genres\":[{\"id\":18,\"name\":\"Драма\"}]}";
    CatalogManager.BrowsePage empty =
        CatalogManager.parseBrowse("{\"results\":[],\"total_pages\":0}", "movie", genres);
    check(empty.films.isEmpty());
    check(empty.pages == 1);
    check(empty.genres.get(18).equals("Драма"));
    CatalogManager.BrowsePage show =
        CatalogManager.parseBrowse(
            "{\"results\":[{\"id\":1,\"name\":\"Series\",\"first_air_date\":\"2023-01-01\"}],\"total_pages\":999}",
            "tv",
            genres);
    check(show.films.get(0).type.equals("tv"));
    check(show.films.get(0).title.equals("Series"));
    check(show.pages == 500);
    StringBuilder list = new StringBuilder("{\"results\":[");
    for (int i = 1; i <= 20; i++) {
      if (i > 1) list.append(',');
      list.append("{\"id\":").append(i).append(",\"title\":\"Film\"}");
    }
    list.append("]}");
    check(CatalogManager.parseBrowse(list.toString(), "movie", genres).films.size() == 20);
    check(CatalogManager.browseError(new org.json.JSONException("bad")).contains("JSON"));
    check(CatalogManager.browseError(new java.net.UnknownHostException()).contains("DNS"));
    check(CatalogManager.browseError(new java.net.SocketTimeoutException()).contains("вовремя"));
    check(
        CatalogManager.browseError(new javax.net.ssl.SSLHandshakeException("bad")).contains("TLS"));
    check(
        CatalogManager.browseError(new IllegalArgumentException())
            .contains("IllegalArgumentException"));
    System.out.println("PASS: browse " + checks + " checks");
  }
}
