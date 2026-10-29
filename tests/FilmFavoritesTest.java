package ru.sasha.tvlauncher;

import java.util.*;

public class FilmFavoritesTest {
  private static int checks;

  private static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("Favorites check " + checks);
  }

  public static void main(String[] args) throws Exception {
    CatalogManager.Film movie =
        new CatalogManager.Film(
            "12", "movie", "Дюна & ещё", "2024", "Текст\nстрока", "/poster.jpg");
    CatalogManager.Film tv = new CatalogManager.Film("12", "tv", "Сериал", "2021", "Описание", "");
    List<CatalogManager.Film> initial = Collections.emptyList();
    List<CatalogManager.Film> added = FilmFavorites.toggled(initial, movie);
    check(initial.isEmpty());
    check(added.size() == 1);
    check(FilmFavorites.contains(added, movie));
    check(!FilmFavorites.contains(added, tv));
    List<CatalogManager.Film> both = FilmFavorites.toggled(added, tv);
    check(both.size() == 2);
    check(both.get(0) == tv);
    check(added.size() == 1);
    List<CatalogManager.Film> loaded = FilmFavorites.decode(FilmFavorites.encode(both));
    check(loaded.size() == 2);
    check(loaded.get(0).type.equals("tv"));
    check(loaded.get(1).title.equals(movie.title));
    check(loaded.get(1).overview.equals(movie.overview));
    check(loaded.get(1).image.equals("/poster.jpg"));
    List<CatalogManager.Film> removed = FilmFavorites.toggled(loaded, movie);
    check(removed.size() == 1);
    check(FilmFavorites.contains(removed, tv));
    check(!FilmFavorites.contains(removed, movie));
    check(loaded.size() == 2);
    String invalid =
        "[{\"id\":\"1\",\"type\":\"person\",\"title\":\"No\"},{\"id\":\"-1\",\"type\":\"movie\",\"title\":\"Bad\"},{\"id\":\"1\",\"type\":\"movie\",\"title\":\"Yes\",\"image\":\"/../secret.jpg\"},{\"id\":\"1\",\"type\":\"movie\",\"title\":\"Duplicate\"},null]";
    List<CatalogManager.Film> clean = FilmFavorites.decode(invalid);
    check(clean.size() == 1);
    check(clean.get(0).image.isEmpty());
    check(clean.get(0).title.equals("Yes"));
    check(FilmFavorites.decode("[]").isEmpty());
    boolean rejected = false;
    try {
      FilmFavorites.decode("broken");
    } catch (org.json.JSONException expected) {
      rejected = true;
    }
    check(rejected);
    System.out.println("PASS: film favorites " + checks + " checks");
  }
}
