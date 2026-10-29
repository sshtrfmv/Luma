package ru.sasha.tvlauncher;

import java.io.*;
import java.util.*;

public class CatalogParseTest {
  private static int checks = 0;

  private static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("Check " + checks);
  }

  public static void main(String[] args) throws Exception {
    String json =
        "{\"results\":[{\"id\":1,\"media_type\":\"person\",\"name\":\"actor\"},{\"id\":2,\"media_type\":\"movie\",\"title\":\"Film\",\"release_date\":\"2026-01-01\",\"backdrop_path\":\"/good.jpg\"},{\"id\":3,\"media_type\":\"tv\",\"name\":\"Series\",\"first_air_date\":\"2020-01-01\",\"overview\":\"Plot\",\"backdrop_path\":null,\"poster_path\":\"/poster.png\"},{\"id\":4,\"media_type\":\"movie\",\"title\":\"Adult\",\"adult\":true},{\"id\":2,\"media_type\":\"movie\",\"title\":\"Duplicate\"}]}";
    List<CatalogManager.Film> films = CatalogManager.parse(json);
    check(films.size() == 2);
    check(films.get(0).id.equals("2"));
    check(films.get(0).type.equals("movie"));
    check(films.get(0).year.equals("2026"));
    check(films.get(1).type.equals("tv"));
    check(films.get(1).image.equals("/poster.png"));
    check(films.get(1).overview.equals("Plot"));
    boolean immutable = false;
    try {
      films.clear();
    } catch (UnsupportedOperationException e) {
      immutable = true;
    }
    check(immutable);
    check(!CatalogManager.validPath("/../../secret.jpg"));
    check(!CatalogManager.validPath("https://evil/a.jpg"));
    check(!CatalogManager.validPath("/a.jpg?x=y"));
    check(CatalogManager.validPath("/Ab-12_c.webp"));
    boolean invalid = false;
    try {
      CatalogManager.parse("{\"results\":[]}");
    } catch (org.json.JSONException e) {
      invalid = true;
    }
    check(invalid);
    invalid = false;
    try {
      CatalogManager.parse("not JSON");
    } catch (org.json.JSONException e) {
      invalid = true;
    }
    check(invalid);
    check(CatalogManager.read(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3).length == 3);
    boolean bounded = false;
    try {
      CatalogManager.read(new ByteArrayInputStream(new byte[4]), 3);
    } catch (IOException e) {
      bounded = true;
    }
    check(bounded);
    System.out.println("PASS: catalog " + checks + " checks");
  }
}
