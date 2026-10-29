package ru.sasha.tvlauncher;

import java.util.*;

public class ReleasePolicyTest {
  static int checks;

  static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("release policy " + checks);
  }

  static CatalogManager.Film film(String title, String image) {
    return new CatalogManager.Film("1", "movie", title, "2026", "Plot", image);
  }

  public static void main(String[] args) {
    Map<String, List<CatalogManager.Film>> rows = new HashMap<>();
    rows.put("trending", Collections.singletonList(film("Film", "/a.jpg")));
    List<String> selected = Collections.singletonList("trending");
    String before = HomeFeeds.contentKey(true, selected, rows);
    rows.put("trending", Collections.singletonList(film("Film", "/a.jpg")));
    check(before.equals(HomeFeeds.contentKey(true, selected, rows)));
    rows.put("trending", Collections.singletonList(film("Film", "/b.jpg")));
    check(!before.equals(HomeFeeds.contentKey(true, selected, rows)));
    rows.put("trending", Collections.singletonList(film("Renamed", "/a.jpg")));
    check(!before.equals(HomeFeeds.contentKey(true, selected, rows)));
    rows.put("trending", Collections.singletonList(film("Film", "/a.jpg")));
    check(!before.equals(HomeFeeds.contentKey(false, selected, rows)));
    rows.put("tv", Collections.singletonList(film("Other", "/x.jpg")));
    check(before.equals(HomeFeeds.contentKey(true, selected, rows)));
    check(!before.equals(HomeFeeds.contentKey(true, Arrays.asList("trending", "tv"), rows)));
    check(
        !HomeFeeds.contentKey(true, Arrays.asList("trending", "tv"), rows)
            .equals(HomeFeeds.contentKey(true, Arrays.asList("tv", "trending"), rows)));
    rows.put("trending", Collections.emptyList());
    check(!before.equals(HomeFeeds.contentKey(true, selected, rows)));
    check(LauncherLogic.deferHomeUpdate(true, false, 1000, 0));
    check(LauncherLogic.deferHomeUpdate(false, true, 1000, 0));
    check(LauncherLogic.deferHomeUpdate(false, false, 1000, 900));
    check(!LauncherLogic.deferHomeUpdate(false, false, 1250, 900));
    check(!LauncherLogic.deferHomeUpdate(false, false, 1000, 0));
    check(!LauncherLogic.deferHomeUpdate(false, false, 100, 900));
    check(LauncherLogic.nearestColumn(5, 3) == 2);
    check(LauncherLogic.nearestColumn(0, 6) == 0);
    check(LauncherLogic.nearestColumn(3, 6) == 3);
    check(LauncherLogic.nearestColumn(0, 0) == -1);
    check(LauncherLogic.defaultFocusTag("tv").equals("button:Сериалы"));
    check(LauncherLogic.defaultFocusTag("all").equals("button:Приложения"));
    check(LauncherLogic.defaultFocusTag("home").equals("button:Главная"));
    System.out.println("PASS: release policy " + checks + " checks");
  }
}
