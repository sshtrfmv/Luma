package ru.sasha.tvlauncher;

import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Explicit, validated location; no GPS permission or device location is used. */
final class WeatherCity {
  final String name, label;
  final double latitude, longitude;

  WeatherCity(String name, String label, double latitude, double longitude) {
    if (name == null
        || name.trim().isEmpty()
        || name.length() > 200
        || Double.isNaN(latitude)
        || Double.isInfinite(latitude)
        || Math.abs(latitude) > 90
        || Double.isNaN(longitude)
        || Double.isInfinite(longitude)
        || Math.abs(longitude) > 180) throw new IllegalArgumentException("Invalid city");
    this.name = name.trim();
    this.label = label == null || label.isEmpty() ? this.name : label;
    this.latitude = latitude;
    this.longitude = longitude;
  }

  static WeatherCity load(SharedPreferences prefs) {
    try {
      return new WeatherCity(
          prefs.getString("city_name", "Томск"),
          "",
          Double.parseDouble(prefs.getString("city_latitude", "56.50")),
          Double.parseDouble(prefs.getString("city_longitude", "84.97")));
    } catch (RuntimeException error) {
      return new WeatherCity("Томск", "Томск", 56.50, 84.97);
    }
  }

  void save(SharedPreferences prefs) {
    prefs
        .edit()
        .putString("city_name", name)
        .putString("city_latitude", Double.toString(latitude))
        .putString("city_longitude", Double.toString(longitude))
        .remove("time")
        .remove("code")
        .remove("temperature")
        .remove("description")
        .apply();
  }

  String forecastUrl() {
    return "https://api.open-meteo.com/v1/forecast?latitude="
        + latitude
        + "&longitude="
        + longitude
        + "&current=temperature_2m,weather_code&timezone=auto";
  }

  static String searchUrl(String query) throws Exception {
    String term = query.trim();
    if (term.length() < 2 || term.length() > 100) throw new IllegalArgumentException("City name");
    return "https://geocoding-api.open-meteo.com/v1/search?name="
        + URLEncoder.encode(term, "UTF-8")
        + "&count=10&language=ru&format=json";
  }

  static List<WeatherCity> parse(String json) throws JSONException {
    JSONArray results = new JSONObject(json).optJSONArray("results");
    List<WeatherCity> cities = new ArrayList<>();
    if (results == null) return cities;
    for (int i = 0; i < Math.min(10, results.length()); i++) {
      JSONObject row = results.optJSONObject(i);
      if (row == null) continue;
      try {
        String name = row.getString("name");
        String region = row.optString("admin1", ""), country = row.optString("country", "");
        String label = name;
        if (!region.isEmpty() && !region.equals(name)) label += " · " + region;
        if (!country.isEmpty()) label += " · " + country;
        cities.add(
            new WeatherCity(name, label, row.getDouble("latitude"), row.getDouble("longitude")));
      } catch (JSONException | IllegalArgumentException malformed) {
        // Skip unusable entries, never silently use default coordinates for a selected result.
      }
    }
    return cities;
  }

  static List<WeatherCity> search(String query) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) new URL(searchUrl(query)).openConnection();
    try {
      conn.setConnectTimeout(8000);
      conn.setReadTimeout(8000);
      if (conn.getResponseCode() != 200) throw new IOException("City search failed");
      StringBuilder data = new StringBuilder();
      try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
        char[] buffer = new char[2048];
        int n;
        while ((n = reader.read(buffer)) != -1) {
          if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
          if (data.length() + n > 131072) throw new IOException("City response too large");
          data.append(buffer, 0, n);
        }
      }
      return parse(data.toString());
    } finally {
      conn.disconnect();
    }
  }
}
