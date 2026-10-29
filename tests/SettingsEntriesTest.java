package ru.sasha.tvlauncher;

public class SettingsEntriesTest {
  private static int checks;

  private static void check(boolean result) {
    checks++;
    if (!result) throw new AssertionError("settings entries " + checks);
  }

  public static void main(String[] args) {
    check(LauncherLogic.hideSettingsEntry("com.android.tv.settings", "Android TV", true));
    check(LauncherLogic.hideSettingsEntry("com.cvte.tv.setting", "Настройки ТВ", true));
    check(LauncherLogic.hideSettingsEntry("ru.sberdevices.settings", "Настройки", true));
    check(LauncherLogic.hideSettingsEntry("vendor.preferences", " Настройки ", true));
    check(!LauncherLogic.hideSettingsEntry("user.preferences", "Настройки", false));
    check(!LauncherLogic.hideSettingsEntry("launcher.manager", "Launcher Manager", true));
    check(!LauncherLogic.hideSettingsEntry("org.amnezia.awg", "AmneziaWG", false));
    check(!LauncherLogic.hideSettingsEntry("vendor.unknown", null, true));
    System.out.println("PASS: settings entries " + checks + " checks");
  }
}
