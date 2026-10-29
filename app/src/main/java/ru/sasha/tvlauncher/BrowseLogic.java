package ru.sasha.tvlauncher;

final class BrowseLogic {
  static String searchText(String text) {
    if (text == null) throw new IllegalArgumentException("query");
    String normalized = text.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty() || normalized.length() > 200)
      throw new IllegalArgumentException("query");
    return normalized;
  }

  static String search(String text, String type, int page) {
    if (!type.equals("multi") && !type.equals("movie") && !type.equals("tv"))
      throw new IllegalArgumentException("search type");
    if (page < 1 || page > 500) throw new IllegalArgumentException("page");
    try {
      return "https://api.themoviedb.org/3/search/"
          + type
          + "?language=ru-RU&include_adult=false&page="
          + page
          + "&query="
          + java.net.URLEncoder.encode(searchText(text), "UTF-8");
    } catch (java.io.UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static String query(String type, int genre, int year, int rating, int sort, int page) {
    if (!type.equals("movie") && !type.equals("tv")) throw new IllegalArgumentException("type");
    if (genre < 0
        || year < 0
        || year > 2200
        || rating < 0
        || rating > 9
        || sort < 0
        || sort > 2
        || page < 1
        || page > 500) throw new IllegalArgumentException("filters");
    String order =
        sort == 1
            ? "vote_average.desc"
            : sort == 2
                ? (type.equals("movie") ? "primary_release_date.desc" : "first_air_date.desc")
                : "popularity.desc";
    String url =
        "https://api.themoviedb.org/3/discover/"
            + type
            + "?language=ru-RU&include_adult=false&sort_by="
            + order
            + "&page="
            + page;
    if (genre > 0) url += "&with_genres=" + genre;
    if (year > 0)
      url += (type.equals("movie") ? "&primary_release_year=" : "&first_air_date_year=") + year;
    if (rating > 0) url += "&vote_average.gte=" + rating;
    if (rating > 0 || sort == 1) url += "&vote_count.gte=100";
    if (sort == 2) {
      java.text.SimpleDateFormat date =
          new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
      url +=
          (type.equals("movie") ? "&primary_release_date.lte=" : "&first_air_date.lte=")
              + date.format(new java.util.Date());
    }
    return url;
  }
}
