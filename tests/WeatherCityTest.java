package ru.sasha.tvlauncher;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;

public class WeatherCityTest {
  private static int checks;

  private static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Weather city " + checks);
  }

  private static void invalid(double lat, double lon) {
    try {
      new WeatherCity("X", "", lat, lon);
      throw new AssertionError("Accepted invalid coordinate");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  public static void main(String[] args) throws Exception {
    check(WeatherCity.parse("{}").isEmpty());
    check(WeatherCity.parse("{\"results\":[]}").isEmpty());
    List<WeatherCity> cities =
        WeatherCity.parse(
            "{\"results\":[{\"name\":\"Париж\",\"latitude\":48.85,\"longitude\":2.35,\"admin1\":\"Иль-де-Франс\",\"country\":\"Франция\"},{\"name\":\"Paris\",\"latitude\":33.66,\"longitude\":-95.55,\"country\":\"США\"},{\"name\":\"Broken\",\"latitude\":999,\"longitude\":0},{\"name\":\"Missing\"},null]}");
    check(cities.size() == 2);
    check(cities.get(0).label.equals("Париж · Иль-де-Франс · Франция"));
    check(cities.get(1).label.equals("Paris · США"));
    check(cities.get(1).forecastUrl().contains("longitude=-95.55"));
    check(cities.get(0).forecastUrl().contains("timezone=auto"));
    check(WeatherCity.searchUrl("Нью Йорк &foo=x").contains("%26foo%3Dx"));
    check(WeatherCity.searchUrl("  Tomsk  ").contains("name=Tomsk&"));
    try {
      WeatherCity.searchUrl("a");
      throw new AssertionError();
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    invalid(91, 0);
    invalid(0, -181);
    invalid(Double.NaN, 0);
    invalid(0, Double.POSITIVE_INFINITY);
    Map<String, Object> values = new HashMap<>();
    SharedPreferences.Editor editor =
        (SharedPreferences.Editor)
            Proxy.newProxyInstance(
                WeatherCityTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.Editor.class},
                (p, m, a) -> {
                  if (m.getName().startsWith("put")) values.put((String) a[0], a[1]);
                  if (m.getName().equals("remove")) values.remove(a[0]);
                  return m.getReturnType() == SharedPreferences.Editor.class ? p : null;
                });
    SharedPreferences prefs =
        (SharedPreferences)
            Proxy.newProxyInstance(
                WeatherCityTest.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (p, m, a) -> m.getName().equals("edit") ? editor : values.getOrDefault(a[0], a[1]));
    check(WeatherCity.load(prefs).name.equals("Томск"));
    values.put("time", 123L);
    values.put("code", 3);
    values.put("temperature", "+5°");
    cities.get(1).save(prefs);
    WeatherCity loaded = WeatherCity.load(prefs);
    check(loaded.name.equals("Paris"));
    check(loaded.longitude == -95.55 && loaded.latitude == 33.66);
    check(
        !values.containsKey("time")
            && !values.containsKey("temperature")
            && !values.containsKey("code"));
    values.put("city_latitude", "broken");
    check(WeatherCity.load(prefs).name.equals("Томск"));
    values.put("city_latitude", "NaN");
    check(WeatherCity.load(prefs).latitude == 56.5);
    System.out.println("PASS: weather city " + checks + " checks");
  }
}
