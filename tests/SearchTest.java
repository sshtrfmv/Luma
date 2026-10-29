package ru.sasha.tvlauncher;

public class SearchTest {
  private static int checks = 0;

  private static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("Search check " + checks);
  }

  public static void main(String[] args) throws Exception {
    check(BrowseLogic.searchText("  Дюна   2\n ").equals("Дюна 2"));
    check(
        BrowseLogic.search("Дюна & море", "multi", 1)
            .contains("query=%D0%94%D1%8E%D0%BD%D0%B0+%26+%D0%BC%D0%BE%D1%80%D0%B5"));
    check(BrowseLogic.search("Lupin", "tv", 2).contains("/search/tv?"));
    check(BrowseLogic.search("Lupin", "tv", 2).contains("page=2"));
    check(BrowseLogic.search("Dune", "movie", 1).contains("/search/movie?"));
    check(BrowseLogic.search("Dune", "multi", 1).contains("include_adult=false"));
    for (String invalid :
        new String[] {null, "", "   ", new String(new char[201]).replace('\0', 'x')}) {
      boolean rejected = false;
      try {
        BrowseLogic.searchText(invalid);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(rejected);
    }
    boolean rejected = false;
    try {
      BrowseLogic.search("test", "person", 1);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected);
    rejected = false;
    try {
      BrowseLogic.search("test", "multi", 0);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected);
    String mixed =
        "{\"results\":[{\"id\":1,\"media_type\":\"person\",\"name\":\"Actor\"},{\"id\":2,\"media_type\":\"movie\",\"title\":\"Film\"},{\"id\":3,\"media_type\":\"tv\",\"name\":\"Series\"}],\"total_pages\":3}";
    CatalogManager.BrowsePage page = CatalogManager.parseSearch(mixed, "multi");
    check(page.films.size() == 2);
    check(page.films.get(0).type.equals("movie"));
    check(page.films.get(1).type.equals("tv"));
    check(page.pages == 3);
    check(CatalogManager.parseSearch("{\"results\":[]}", "multi").films.isEmpty());
    check(
        CatalogManager.parseSearch("{\"results\":[{\"id\":3,\"name\":\"Series\"}]}", "tv")
            .films
            .get(0)
            .type
            .equals("tv"));
    check(
        CatalogManager.parseSearch("{\"results\":[{\"id\":2,\"title\":\"Film\"}]}", "movie")
            .films
            .get(0)
            .title
            .equals("Film"));
    rejected = false;
    try {
      CatalogManager.parseSearch("{bad}", "multi");
    } catch (org.json.JSONException expected) {
      rejected = true;
    }
    check(rejected);
    System.out.println("PASS: search " + checks + " checks");
  }
}
