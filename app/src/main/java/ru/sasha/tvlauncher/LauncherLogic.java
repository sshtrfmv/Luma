package ru.sasha.tvlauncher;

import java.util.Collections;
import java.util.List;

/** Android-independent behavior shared by the launcher and regression tests. */
final class LauncherLogic {
  static boolean deferHomeUpdate(boolean moving, boolean drawerOpen, long now, long lastInput) {
    return moving || drawerOpen || (lastInput > 0 && now >= lastInput && now - lastInput < 350);
  }

  // At most 8 MiB for an RGBA snapshot of the composed artwork.
  static boolean cacheBackdrop(int width, int height) {
    return width > 0 && height > 0 && (long) width * height <= 2L * 1024 * 1024;
  }

  static int nearestColumn(int column, int count) {
    return count == 0 ? -1 : Math.max(0, Math.min(column, count - 1));
  }

  static boolean hideSettingsEntry(String pkg, String label, boolean system) {
    if ("com.android.settings".equals(pkg)
        || "com.android.tv.settings".equals(pkg)
        || "com.cvte.tv.setting".equals(pkg)
        || "ru.sberdevices.settings".equals(pkg)) return true;
    String name = label == null ? "" : label.trim().toLowerCase(java.util.Locale.ROOT);
    return system
        && (name.equals("настройки")
            || name.equals("настройки тв")
            || name.equals("настройки телевизора")
            || name.equals("settings")
            || name.equals("tv settings"));
  }

  static String defaultFocusTag(String page) {
    return "home".equals(page) ? "button:Главная" : drawerContentTag(page);
  }

  static String drawerKind(String page) {
    if ("movie".equals(page)) return "film";
    if ("tv".equals(page)) return "series";
    if ("favorites".equals(page)) return "favorite";
    if ("all".equals(page)) return "apps";
    if ("search".equals(page)) return "search";
    if ("settings".equals(page)) return "settings";
    if ("devices".equals(page)) return "devices";
    return "home";
  }

  static String drawerContentTag(String page) {
    if ("movie".equals(page)) return "button:Фильмы";
    if ("tv".equals(page)) return "button:Сериалы";
    if ("favorites".equals(page)) return "icon:Избранное";
    if ("all".equals(page)) return "button:Приложения";
    if ("search".equals(page)) return "icon:Поиск";
    if ("settings".equals(page)) return "icon:Настройки";
    if ("devices".equals(page)) return "button:Устройства";
    return "hero:В Lampa";
  }

  static String vpnLabel(int state, boolean pending, boolean wantUp, boolean configured) {
    if (pending) return wantUp ? "Подключение…" : "Отключение…";
    if (state == 1) return "Включён · AmneziaWG";
    if (state == 2) return "VPN включён";
    if (state == -1) return "Статус неизвестен";
    return configured ? "Выключен" : "Настроить AmneziaWG";
  }

  static boolean move(List<String> order, String pkg, int direction) {
    int from = order.indexOf(pkg), to = from + direction;
    if (Math.abs(direction) != 1 || from < 0 || to < 0 || to >= order.size()) return false;
    Collections.swap(order, from, to);
    return true;
  }

  static String describeWeather(int code) {
    switch (code) {
      case 0:
        return "Ясно";
      case 1:
      case 2:
        return "Переменная облачность";
      case 3:
        return "Облачно";
      case 45:
      case 48:
        return "Туман";
      case 51:
      case 53:
      case 55:
        return "Морось";
      case 56:
      case 57:
        return "Ледяная морось";
      case 61:
      case 63:
      case 65:
        return "Дождь";
      case 66:
      case 67:
        return "Ледяной дождь";
      case 71:
      case 73:
      case 75:
      case 77:
        return "Снег";
      case 80:
      case 81:
      case 82:
        return "Ливень";
      case 85:
      case 86:
        return "Снегопад";
      case 95:
      case 96:
      case 99:
        return "Гроза";
      default:
        return "Нет описания";
    }
  }

  static String temperature(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value) || value < -100 || value > 70)
      throw new IllegalArgumentException("Invalid weather temperature");
    long rounded = Math.round(value);
    return (rounded > 0 ? "+" : "") + rounded + "°";
  }

  static String weatherIcon(int code) {
    switch (code) {
      case 0:
        return "sun";
      case 1:
      case 2:
        return "partly";
      case 3:
        return "cloud";
      case 45:
      case 48:
        return "fog";
      case 51:
      case 53:
      case 55:
      case 56:
      case 57:
      case 61:
      case 63:
      case 65:
      case 66:
      case 67:
      case 80:
      case 81:
      case 82:
        return "rain";
      case 71:
      case 73:
      case 75:
      case 77:
      case 85:
      case 86:
        return "snow";
      case 95:
      case 96:
      case 99:
        return "storm";
      default:
        return "weather";
    }
  }

  static boolean fresh(long now, long saved, long ttl) {
    return saved > 0 && now >= saved && now - saved < ttl;
  }
}
