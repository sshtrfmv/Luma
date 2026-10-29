package ru.sasha.tvlauncher;

import java.util.*;
import org.json.*;

/** Durable browsing preferences and viewport; no movie history or credentials. */
final class CatalogPosition {
  int genre, year, rating, sort, page = 1, scroll;
  String focus = "";

  static CatalogPosition decode(String raw) {
    CatalogPosition p = new CatalogPosition();
    try {
      JSONObject o = new JSONObject(raw);
      p.genre = Math.max(0, o.optInt("genre"));
      int y = o.optInt("year");
      p.year = y >= 1900 && y <= 2200 ? y : 0;
      int rating = o.optInt("rating");
      p.rating = Arrays.asList(0, 6, 7, 8, 9).contains(rating) ? rating : 0;
      p.sort = Math.max(0, Math.min(2, o.optInt("sort")));
      p.page = Math.max(1, Math.min(500, o.optInt("page", 1)));
      p.scroll = Math.max(0, Math.min(50000, o.optInt("scroll")));
      String f = o.optString("focus", "");
      if (f.matches("catalog:(movie|tv):[1-9][0-9]{0,15}")
          || f.matches("filter:(genre|year|rating|sort|reset|retry|retry-error|previous|next)"))
        p.focus = f;
    } catch (JSONException ignored) {
    }
    return p;
  }

  String encode() {
    try {
      return new JSONObject()
          .put("genre", genre)
          .put("year", year)
          .put("rating", rating)
          .put("sort", sort)
          .put("page", page)
          .put("scroll", scroll)
          .put("focus", focus)
          .toString();
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
