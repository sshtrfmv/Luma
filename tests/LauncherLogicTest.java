package ru.sasha.tvlauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LauncherLogicTest {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    List<String> persisted = Arrays.asList("youtube", "lampa", "zona");
    List<String> draft = new ArrayList<>(persisted);
    check(LauncherLogic.move(draft, "lampa", -1), "move left");
    check(draft.equals(Arrays.asList("lampa", "youtube", "zona")), "new order");
    check(
        persisted.equals(Arrays.asList("youtube", "lampa", "zona")),
        "cancel leaves persisted order intact");
    check(!LauncherLogic.move(draft, "lampa", -1), "left boundary");
    check(!LauncherLogic.move(draft, "zona", 1), "right boundary");
    check(!LauncherLogic.move(draft, "missing", 1), "missing app");
    check(!LauncherLogic.move(draft, "lampa", 2), "invalid step");
    check(!LauncherLogic.move(new ArrayList<>(), "lampa", 1), "empty list");
    check(LauncherLogic.move(draft, "lampa", 1), "move right");
    check(draft.equals(persisted), "roundtrip");
    draft.remove("lampa");
    check(persisted.contains("lampa"), "draft removal does not mutate saved order");
    check(LauncherLogic.temperature(12.2).equals("+12°"), "positive");
    check(LauncherLogic.temperature(-12.2).equals("-12°"), "negative");
    check(LauncherLogic.temperature(-0.1).equals("0°"), "no negative zero");
    for (double invalid :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 200, -200}) {
      boolean rejected = false;
      try {
        LauncherLogic.temperature(invalid);
      } catch (IllegalArgumentException expected) {
        rejected = true;
      }
      check(rejected, "reject invalid weather " + invalid);
    }
    check(
        LauncherLogic.describeWeather(-1).equals("Нет описания"),
        "unknown negative code is not clear weather");
    check(LauncherLogic.describeWeather(66).equals("Ледяной дождь"), "freezing rain");
    check(LauncherLogic.describeWeather(56).equals("Ледяная морось"), "freezing drizzle");
    check(LauncherLogic.describeWeather(99).equals("Гроза"), "thunderstorm");
    check(LauncherLogic.describeWeather(1000).equals("Нет описания"), "unknown positive code");
    check(LauncherLogic.fresh(1000, 900, 200), "fresh cache");
    check(!LauncherLogic.fresh(1100, 900, 200), "expiry boundary");
    check(!LauncherLogic.fresh(1101, 900, 200), "expired cache");
    check(!LauncherLogic.fresh(1000, 0, 200), "no cache");
    check(!LauncherLogic.fresh(800, 900, 200), "clock moved backwards");
    check(LauncherLogic.fresh(900, 900, 200), "just saved cache");
    check(LauncherLogic.weatherIcon(0).equals("sun"), "sun icon");
    check(LauncherLogic.weatherIcon(3).equals("cloud"), "cloud icon");
    check(LauncherLogic.weatherIcon(65).equals("rain"), "rain icon");
    check(LauncherLogic.weatherIcon(75).equals("snow"), "snow icon");
    check(LauncherLogic.weatherIcon(99).equals("storm"), "storm icon");
    check(LauncherLogic.weatherIcon(-1).equals("weather"), "unknown icon");
    System.out.println("PASS: " + checks + " checks");
  }
}
