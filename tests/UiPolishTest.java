package ru.sasha.tvlauncher;

public class UiPolishTest {
  static int checks;

  static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("UI polish " + checks);
  }

  public static void main(String[] args) {
    check(CatalogManager.imageSample(1280, 720, true) == 1);
    check(CatalogManager.imageSample(1280, 720, false) == 2);
    check(CatalogManager.imageSample(8192, 8192, true) == 8);
    for (int width : new int[] {500, 780, 1280, 1920, 3840, 8192}) {
      int sample = CatalogManager.imageSample(width, width, true);
      check(width / sample <= 1280 && (sample == 1 || width / (sample / 2) > 1280));
    }
    check(
        !CatalogManager.imageKey("/same.jpg", true)
            .equals(CatalogManager.imageKey("/same.jpg", false)));
    check(LauncherLogic.vpnLabel(2, false, false, true).equals("VPN включён"));
    check(LauncherLogic.vpnLabel(1, false, false, true).equals("Включён · AmneziaWG"));
    check(LauncherLogic.vpnLabel(0, false, false, true).equals("Выключен"));
    check(LauncherLogic.vpnLabel(-1, false, false, true).equals("Статус неизвестен"));
    check(LauncherLogic.vpnLabel(0, false, false, false).equals("Настроить AmneziaWG"));
    check(LauncherLogic.vpnLabel(2, false, false, false).equals("VPN включён"));
    check(LauncherLogic.vpnLabel(0, true, true, true).equals("Подключение…"));
    check(LauncherLogic.vpnLabel(1, true, false, true).equals("Отключение…"));
    check(LauncherLogic.cacheBackdrop(1157, 431));
    check(LauncherLogic.cacheBackdrop(2313, 861));
    check(!LauncherLogic.cacheBackdrop(4626, 1722));
    check(!LauncherLogic.cacheBackdrop(0, 431));
    check(!LauncherLogic.cacheBackdrop(-1, 431));
    check(!LauncherLogic.cacheBackdrop(Integer.MAX_VALUE, Integer.MAX_VALUE));
    System.out.println("PASS: UI polish " + checks + " checks");
  }
}
