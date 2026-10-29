package ru.sasha.tvlauncher;

public class DrawerNavigationTest {
  static int checks;

  static void check(boolean ok) {
    checks++;
    if (!ok) throw new AssertionError("drawer " + checks);
  }

  public static void main(String[] args) {
    String[] pages = {"home", "movie", "tv", "search", "favorites", "all"};
    String[] icons = {"home", "film", "series", "search", "favorite", "apps"};
    String[] targets = {
      "hero:В Lampa",
      "button:Фильмы",
      "button:Сериалы",
      "icon:Поиск",
      "icon:Избранное",
      "button:Приложения"
    };
    for (int i = 0; i < pages.length; i++) {
      check(icons[i].equals(LauncherLogic.drawerKind(pages[i])));
      check(targets[i].equals(LauncherLogic.drawerContentTag(pages[i])));
    }
    check(LauncherLogic.drawerKind(null).equals("home"));
    check(LauncherLogic.drawerKind("unknown").equals("home"));
    check(LauncherLogic.drawerContentTag(null).equals("hero:В Lampa"));
    check(LauncherLogic.drawerContentTag("unknown").equals("hero:В Lampa"));
    check(LauncherLogic.drawerKind("settings").equals("settings"));
    check(LauncherLogic.defaultFocusTag("settings").equals("icon:Настройки"));
    System.out.println("PASS: drawer navigation " + checks + " checks");
  }
}
