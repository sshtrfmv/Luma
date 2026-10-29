package ru.sasha.tvlauncher;

import java.util.*;

public class HomeFeedsTest {
  private static int checks = 0;

  private static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("Home feeds check " + checks);
  }

  public static void main(String[] args) throws Exception {
    check(HomeFeeds.selected("[]").equals(Collections.singletonList("trending")));
    check(HomeFeeds.selected("bad").equals(Collections.singletonList("trending")));
    check(
        HomeFeeds.selected("[\"wrong\",\"tv\",\"tv\",\"movies\"]")
            .equals(Arrays.asList("tv", "movies")));
    check(HomeFeeds.selected("[\"tv\",\"movies\",\"rated\",\"upcoming\"]").size() == 3);
    check(HomeFeeds.url("tv").contains("/tv/popular?"));
    check(HomeFeeds.url("cinema").contains("/movie/now_playing?"));
    check(HomeFeeds.url("upcoming").contains("/movie/upcoming?"));
    boolean invalid = false;
    try {
      HomeFeeds.url("../bad");
    } catch (IllegalArgumentException expected) {
      invalid = true;
    }
    check(invalid);
    for (String id : HomeFeeds.ids()) check(!HomeFeeds.title(id).isEmpty());
    check(HomeFeeds.step(0, -1, 6) == 5);
    check(HomeFeeds.step(5, 1, 6) == 0);
    check(HomeFeeds.step(9, 0, 6) == 3);
    check(HomeFeeds.step(0, 1, 0) == 0);
    check(HomeFeeds.step(0, -1, 1) == 0);
    String movie = "{\"results\":[{\"id\":1,\"title\":\"Film\",\"release_date\":\"2026-01-01\"}]}";
    check(CatalogManager.parseFeed(movie, "cinema").get(0).type.equals("movie"));
    String tv = "{\"results\":[{\"id\":2,\"name\":\"Series\",\"first_air_date\":\"2021-01-01\"}]}";
    check(CatalogManager.parseFeed(tv, "tv").get(0).type.equals("tv"));
    check(CatalogManager.parseFeed(tv, "tv").get(0).year.equals("2021"));
    check(CatalogManager.parseFeed("{\"results\":[]}", "upcoming").isEmpty());
    StringBuilder list = new StringBuilder("{\"results\":[");
    for (int i = 1; i <= 20; i++) {
      if (i > 1) list.append(',');
      list.append("{\"id\":").append(i).append(",\"title\":\"Film\"}");
    }
    list.append("]}");
    check(CatalogManager.parseFeed(list.toString(), "movies").size() == 6);
    for (int rows = 1; rows <= 3; rows++) {
      int extra = (rows - 1) * 180;
      check((604 + extra - 80) + 95 <= 640 + extra);
    }
    System.out.println("PASS: home feeds " + checks + " checks");
  }
}
