package ru.sasha.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.json.JSONArray;

public class MainActivity extends Activity {
  private static final int WHITE = 0xfff4f3f5, MUTED = 0xffaeb0b8, CARD = 0xff202126;
  private final List<ResolveInfo> installed = new ArrayList<>();
  private PackageManager pm;
  private CatalogManager catalog;
  private FilmFavorites filmFavorites;
  private HdmiInputs hdmiInputs;
  private android.content.SharedPreferences hdmiPrefs;
  private TextView hdmiHomeSubtitle;
  private String devicesRenderedKey = "";
  private final Runnable devicesUpdate =
      new Runnable() {
        public void run() {
          if (!resumed || !page.equals("devices")) return;
          if (devicesRenderedKey.equals(hdmiPageKey())) return;
          if (!getWindow().getDecorView().hasWindowFocus()
              || LauncherLogic.deferHomeUpdate(
                  false,
                  drawerController != null && drawerController.expanded,
                  android.os.SystemClock.elapsedRealtime(),
                  lastNavigationInput)) {
            ui.postDelayed(this, 400);
            return;
          }
          showDevices();
        }
      };
  private TextView catalogLabel;
  private LinearLayout body, pageHeader;
  private FrameLayout pageOuter, sharedHeader;
  private ScrollView pageScroll;
  private final Set<String> favoriteBadges = new HashSet<>();
  private String movingPackage = null;
  private ArrayList<String> movingOrder = null;
  private boolean ignoreRelease = false;
  private final Set<Integer> swallowedKeys = new HashSet<>();
  private int heldConfirm = -1;
  private String page = "home", restoreFocus = null;
  private boolean resumed = false, pageTransition = false;
  private String settingsReturnPage = "home", settingsReturnFocus;

  private boolean motionEnabled() {
    return getSharedPreferences("sasha_interface", MODE_PRIVATE).getBoolean("motion", true)
        && android.animation.ValueAnimator.areAnimatorsEnabled();
  }

  private DrawerController drawerController;
  private final Map<ResolveInfo, String> appLabels = new IdentityHashMap<>();
  private final Map<String, TextView> homeFeedLabels = new HashMap<>();
  private final Map<String, TextView> homeEmptyLabels = new HashMap<>();
  private String renderedHomeKey = "";
  private long lastNavigationInput = 0;
  private final Runnable homeUpdate =
      new Runnable() {
        public void run() {
          if (!resumed || !page.equals("home") || isDestroyed()) return;
          paintHomeStatuses();
          if (renderedHomeKey.equals(catalog.homeContentKey())) return;
          if (LauncherLogic.deferHomeUpdate(
              movingPackage != null || !getWindow().getDecorView().hasWindowFocus(),
              drawerController != null && drawerController.expanded,
              android.os.SystemClock.elapsedRealtime(),
              lastNavigationInput)) {
            ui.postDelayed(this, 400);
            return;
          }
          showHome();
        }
      };
  private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
  private final java.util.concurrent.ExecutorService network =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private final Runnable weatherTick =
      new Runnable() {
        public void run() {
          if (!resumed) return;
          if (page.equals("home")) {
            updateWeather();
            refreshCatalog(false);
          }
          ui.postDelayed(this, 60000);
        }
      };

  private void rememberFocus() {
    View v = getCurrentFocus();
    if (v != null && v.getTag() instanceof String) restoreFocus = (String) v.getTag();
  }

  private void beginPage(String next) {
    AppDiagnostics.built(next);
    pageTransition = !page.equals(next);
    rememberBrowsePosition();
    if (page.equals(next) && restoreFocus == null) rememberFocus();
    else if (!page.equals(next)) restoreFocus = null;
    page = next;
    hdmiHomeSubtitle = null;
    ui.removeCallbacks(devicesUpdate);
    homeFeedLabels.clear();
    homeEmptyLabels.clear();
    ui.removeCallbacks(homeUpdate);
    if (drawerController != null) {
      drawerController.dispose();
      drawerController = null;
    }
    weatherIconView = null;
    vpnSubtitle = null;
    vpnTile = null;
    weatherTempView = null;
    weatherStatusView = null;
  }

  private void restoreTileFocus() {
    if (!page.equals("home")) wirePageFocus();
    if (pageTransition) {
      pageTransition = false;
      View content = page.equals("home") ? homeContent : body;
      if (content != null && motionEnabled()) {
        content.setAlpha(.92f);
        content.animate().alpha(1f).setDuration(140).start();
      }
    }
    final View root = getWindow().getDecorView();
    String tag = movingPackage != null ? "app:" + movingPackage : restoreFocus;
    restoreFocus = null;
    View target = tag == null ? null : root.findViewWithTag(tag);
    if (target == null) target = root.findViewWithTag(LauncherLogic.defaultFocusTag(page));
    if (target != null) {
      final View selected = target;
      selected.requestFocus();
      selected.post(
          () -> {
            if (selected.isAttachedToWindow()) selected.requestFocus();
          });
    }
  }

  private void renderPage() {
    renderPage(page);
  }

  private void renderPage(String destination) {
    if (destination.equals("settings")) showLauncherSettings();
    else if (destination.equals("devices")) showDevices();
    else if (destination.equals("all")) showAll();
    else if (destination.equals("movie") || destination.equals("tv"))
      showBrowse(destination, false);
    else if (destination.equals("search")) showSearch(false);
    else if (destination.equals("favorites")) showFilmFavorites();
    else showHome();
  }

  private void cancelMove() {
    movingPackage = null;
    movingOrder = null;
    ignoreRelease = false;
  }

  private void finishMove(boolean save, boolean remove) {
    String pkg = movingPackage;
    if (remove) movingOrder.remove(pkg);
    if (save) saveFavorites(movingOrder);
    cancelMove();
    showHome();
    if (!remove) {
      View v = getWindow().getDecorView().findViewWithTag("app:" + pkg);
      if (v != null) v.requestFocus();
    }
    Toast.makeText(
            this,
            remove
                ? "Убрано из быстрого запуска"
                : save ? "Порядок сохранён" : "Перемещение отменено",
            Toast.LENGTH_SHORT)
        .show();
  }

  // Shared 1280x720 design units for every application page.
  private int unit(float n) {
    return px(n);
  }

  private GradientDrawable bg(int color, int border) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(unit(14));
    if (border != 0) d.setStroke(unit(2), border);
    return d;
  }

  private TextView label(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    v.setIncludeFontPadding(false);
    v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
    v.setTextColor(color);
    return v;
  }

  private TextView heading(String s, int size) {
    TextView v = label(s, size, WHITE);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  private void focus(View v, int normal) {
    v.setFocusable(true);
    v.setDefaultFocusHighlightEnabled(false);
    v.setBackground(LumaStyle.states(normal, unit(14), designScale));
    liftOnFocus(v);
  }

  private void liftOnFocus(View v) {
    v.setOnFocusChangeListener(
        (view, on) -> {
          view.animate().cancel();
          float scale = on && motionEnabled() ? 1.035f : 1f;
          view.animate()
              .scaleX(scale)
              .scaleY(scale)
              .translationZ(on ? unit(5) : 0)
              .setDuration(motionEnabled() ? 160 : 0)
              .setInterpolator(new android.view.animation.DecelerateInterpolator())
              .start();
        });
  }

  private TextView button(String title, Runnable action) {
    TextView v = label(title, 14, WHITE);
    v.setTag("button:" + title);
    v.setGravity(Gravity.CENTER);
    v.setPadding(unit(16), unit(10), unit(16), unit(10));
    focus(v, CARD);
    v.setOnClickListener(w -> action.run());
    return v;
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private void space(LinearLayout parent, int height) {
    View v = new View(this);
    parent.addView(v, new LinearLayout.LayoutParams(1, unit(height)));
  }

  private void updateApps() {
    installed.clear();
    appLabels.clear();
    Map<String, ResolveInfo> unique = new LinkedHashMap<>();
    for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
      Intent q = new Intent(Intent.ACTION_MAIN);
      q.addCategory(cat);
      for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
        String p = r.activityInfo.packageName;
        if (!p.equals(getPackageName())) unique.putIfAbsent(p, r);
      }
    }
    installed.addAll(unique.values());
    for (ResolveInfo app : installed) appLabels.put(app, app.loadLabel(pm).toString());
    Collections.sort(installed, (a, b) -> appLabels.get(a).compareToIgnoreCase(appLabels.get(b)));
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    AppDiagnostics.created();
    pm = getPackageManager();
    catalog = new CatalogManager(this);
    filmFavorites = new FilmFavorites(this);
    hdmiInputs = new HdmiInputs(this);
    hdmiPrefs = getSharedPreferences("hdmi_devices", MODE_PRIVATE);
    restoreBrowser("movie");
    restoreBrowser("tv");
    vpnTunnel = getSharedPreferences("vpn_control", MODE_PRIVATE).getString("tunnel", "");
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    if (state != null) {
      favoritesPage = state.getInt("favoritesPage", 1);
      favoritesMode = state.getInt("favoritesMode", 0);
      searchState.draft = state.getString("searchDraft", "");
      searchState.query = state.getString("searchQuery", "");
      searchState.mode = state.getInt("searchMode", 0);
      searchState.page = state.getInt("searchPage", 1);
    }
    if (state != null) {
      settingsReturnPage = state.getString("settingsReturnPage", "home");
      settingsReturnFocus = state.getString("settingsReturnFocus");
      page = state.getString("page", "home");
      restoreFocus = state.getString("focus");
    }
    android.content.SharedPreferences cache = getSharedPreferences("sasha_weather", MODE_PRIVATE);
    lastWeatherSuccess = cache.getLong("time", 0);
    weatherCode = cache.getInt("code", -1);
    if (weatherCode == -1) lastWeatherSuccess = 0;
    weatherTemperature = cache.getString("temperature", "—°");
    weatherDescription = cache.getString("description", "Загрузка...");
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    hdmiInputs.start(
        () -> {
          paintHdmiHome();
          if (resumed && page.equals("devices")) {
            ui.removeCallbacks(devicesUpdate);
            ui.postDelayed(devicesUpdate, 200);
          }
        });
    String beforeApps = installedSignature();
    updateApps();
    catalog.importToken();
    boolean keepHome =
        page.equals("home")
            && homeContent != null
            && homeContent.isAttachedToWindow()
            && beforeApps.equals(installedSignature())
            && renderedHomeKey.equals(catalog.homeContentKey());
    if (keepHome) {
      restoreTileFocus();
      paintWeather();
      updateVpn();
    } else renderPage();
    ui.removeCallbacks(weatherTick);
    ui.post(weatherTick);
    ui.removeCallbacks(vpnTick);
    ui.post(vpnTick);
    AppDiagnostics.resumed(this, page, keepHome);
  }

  @Override
  protected void onPause() {
    AppDiagnostics.paused();
    rememberSearchInput();
    rememberBrowsePosition();
    rememberFocus();
    resumed = false;
    hdmiInputs.stop();
    ui.removeCallbacks(devicesUpdate);
    ui.removeCallbacks(weatherTick);
    ui.removeCallbacks(vpnTick);
    ui.removeCallbacks(homeUpdate);
    cancelMove();
    swallowedKeys.clear();
    heldConfirm = -1;
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (drawerController != null) drawerController.dispose();
    if (detailsDialog != null) detailsDialog.dismiss();
    if (restoreProgress != null) restoreProgress.dismiss();
    ui.removeCallbacksAndMessages(null);
    network.shutdownNow();
    if (catalog != null) catalog.close();
    if (hdmiInputs != null) hdmiInputs.close();
    weatherIconView = null;
    vpnSubtitle = null;
    vpnTile = null;
    weatherTempView = null;
    weatherStatusView = null;
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putString("settingsReturnPage", settingsReturnPage);
    state.putString("settingsReturnFocus", settingsReturnFocus);
    rememberFocus();
    rememberSearchInput();
    rememberBrowsePosition();
    state.putString("searchDraft", searchState.draft);
    state.putString("searchQuery", searchState.query);
    state.putInt("searchMode", searchState.mode);
    state.putInt("searchPage", searchState.page);
    state.putInt("favoritesPage", favoritesPage);
    state.putInt("favoritesMode", favoritesMode);
    state.putString("page", page);
    state.putString("focus", restoreFocus);
    super.onSaveInstanceState(state);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    rememberBrowsePosition();
    cancelMove();
    page = "home";
    restoreFocus = null;
  }

  private void launch(ResolveInfo r) {
    Intent intent = new Intent(Intent.ACTION_MAIN);
    intent.setComponent(
        new android.content.ComponentName(r.activityInfo.packageName, r.activityInfo.name));
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    try {
      startActivity(intent);
    } catch (android.content.ActivityNotFoundException | SecurityException error) {
      android.util.Log.w("SashaTV", "Cannot launch " + r.activityInfo.packageName, error);
      Toast.makeText(this, "Приложение недоступно", Toast.LENGTH_SHORT).show();
      updateApps();
    }
  }

  private int tileSize(int n) {
    return page.equals("home") ? px(n) : unit(n);
  }

  private View tile(ResolveInfo app) {
    LinearLayout tile = column();
    tile.setGravity(Gravity.CENTER);
    tile.setClipChildren(false);
    tile.setClipToPadding(false);
    tile.setTag("app:" + app.activityInfo.packageName);
    tile.setPadding(0, 0, 0, 0);
    String pkg = app.activityInfo.packageName;
    boolean shelf = page.equals("home");
    tile.setFocusable(true);
    tile.setDefaultFocusHighlightEnabled(false);
    liftOnFocus(tile);
    FrameLayout plate = new FrameLayout(this);
    plate.setDuplicateParentStateEnabled(true);
    plate.setElevation(px(2));
    plate.setBackground(LumaStyle.states(appMaterial(pkg), px(14), designScale));
    tile.addView(plate, new LinearLayout.LayoutParams(-1, px(shelf ? 70 : 104)));
    String loadedLabel = appLabels.get(app);
    if (loadedLabel == null) loadedLabel = app.loadLabel(pm).toString();
    ImageView icon = new ImageView(this);
    int artwork = appArtwork(pkg);
    icon.setImageDrawable(
        artwork != 0 ? getDrawable(artwork) : new UtilityAppIcon(loadedLabel, pkg));
    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    int iconSize = px(shelf ? 46 : 60);
    plate.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
    String displayName =
        pkg.equals("org.amnezia.vpn")
            ? "AmneziaVPN"
            : pkg.equals("org.amnezia.awg")
                ? "AmneziaWG"
                : pkg.equals("top.rootu.lampa") ? "Lampa" : loadedLabel;
    TextView name = label(displayName, 12, WHITE);
    if (page.equals("home")) name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(11));
    name.setGravity(Gravity.CENTER);
    name.setMaxLines(shelf ? 1 : 2);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, tileSize(shelf ? 19 : 28));
    lp.topMargin = tileSize(5);
    tile.addView(name, lp);
    tile.setContentDescription(loadedLabel);
    tile.setOnClickListener(v -> launch(app));
    tile.setOnLongClickListener(
        v -> {
          editFavorite(app);
          return true;
        });
    return tile;
  }

  private int appArtwork(String pkg) {
    switch (pkg) {
      case "com.google.android.youtube.tv":
        return R.drawable.luma_youtube;
      case "com.vk.tv":
        return R.drawable.luma_vk;
      case "top.rootu.lampa":
        return R.drawable.luma_lampa;
      case "mobi.zona":
        return R.drawable.luma_zona;
      case "org.amnezia.awg":
        return R.drawable.luma_awg;
      case "org.amnezia.vpn":
        return R.drawable.luma_vpn;
      case "org.videolan.vlc":
        return R.drawable.luma_vlc;
      case "org.localsend.localsend_app":
        return R.drawable.luma_localsend;
      default:
        return 0;
    }
  }

  private int appMaterial(String pkg) {
    if (pkg.equals("com.vk.tv")) return 0xff25242c;
    if (pkg.equals("org.videolan.vlc")) return 0xff272521;
    if (pkg.equals("org.localsend.localsend_app")) return 0xff202929;
    return CARD;
  }

  private String installedSignature() {
    JSONArray rows = new JSONArray();
    for (ResolveInfo app : installed)
      rows.put(
          new JSONArray()
              .put(app.activityInfo.packageName)
              .put(app.activityInfo.name)
              .put(app.activityInfo.applicationInfo.sourceDir)
              .put(appLabels.get(app)));
    return rows.toString();
  }

  private void aboutOnHold(View view) {
    view.setOnLongClickListener(
        v -> {
          showAbout();
          return true;
        });
  }

  private void showAbout() {
    String version = "неизвестна";
    try {
      android.content.pm.PackageInfo info = pm.getPackageInfo(getPackageName(), 0);
      version =
          info.versionName
              + " ("
              + (android.os.Build.VERSION.SDK_INT >= 28
                  ? info.getLongVersionCode()
                  : info.versionCode)
              + ")";
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    new android.app.AlertDialog.Builder(this)
        .setTitle("О Luma")
        .setMessage(
            "Версия: "
                + version
                + "\nПакет: "
                + getPackageName()
                + "\n\n"
                + "Короткое OK на «Настройки» открывает настройки Luma. Долгое OK — этот"
                + " экран.")
        .setPositiveButton("Закрыть", null)
        .setNeutralButton("Настройки телевизора", (dialog, which) -> openSystemSettings())
        .show();
  }

  private void openSystemSettings() {
    try {
      startActivity(new Intent(Settings.ACTION_SETTINGS));
    } catch (Exception e) {
      Toast.makeText(this, "Настройки недоступны", Toast.LENGTH_SHORT).show();
    }
  }

  private void openSettings() {
    if (!page.equals("settings")) {
      settingsReturnPage = page;
      View current = getCurrentFocus();
      settingsReturnFocus =
          current != null && current.getTag() instanceof String ? (String) current.getTag() : null;
    }
    showLauncherSettings();
  }

  private void showLauncherSettings() {
    beginPage("settings");
    setup();
    top();
    pageIntro("Настройки", "Сделай Luma удобной для себя");
    settingsSection("Главный экран");
    settingsRow(
        settingCard(
            "Подборки", "Что показывать на главной", "film", "feeds", this::homeFeedSettings),
        settingCard(
            "Погода",
            weatherCity().name + " · выбрать город",
            "weather",
            "weather",
            this::chooseWeatherCity));
    weatherStatusView = label("", 12, MUTED);
    weatherStatusView.setPadding(unit(10), 0, 0, unit(6));
    body.addView(weatherStatusView);
    paintWeather();
    boolean motion =
        getSharedPreferences("sasha_interface", MODE_PRIVATE).getBoolean("motion", true);
    settingsRow(
        settingCard(
            "Анимации",
            motion ? "Включены" : "Выключены",
            "next",
            "motion",
            () -> {
              android.content.SharedPreferences prefs =
                  getSharedPreferences("sasha_interface", MODE_PRIVATE);
              prefs.edit().putBoolean("motion", !prefs.getBoolean("motion", true)).apply();
              restoreFocus = "settings:motion";
              showLauncherSettings();
            }),
        settingCard(
            "Каталог", "Подключение и токен TMDB", "series", "catalog", this::catalogSettings));
    settingsSection("Подключение");
    settingsRow(
        settingCard("AmneziaWG", "Настроить управление VPN", "vpn", "vpn", this::vpnSettings),
        settingCard(
            "Открыть AmneziaWG",
            "Туннели и подключение",
            "apps",
            "open-vpn",
            () -> {
              for (ResolveInfo app : installed)
                if (app.activityInfo.packageName.equals("org.amnezia.awg")) {
                  launch(app);
                  return;
                }
              Toast.makeText(this, "AmneziaWG не установлен", Toast.LENGTH_SHORT).show();
            }));
    settingsRow(
        settingCard("Устройства", "Входы HDMI", "devices", "devices", this::showDevices),
        settingCard(
            "Настройки телевизора",
            "Системные параметры",
            "settings",
            "system",
            this::openSystemSettings));
    settingsSection("Данные и приложение");
    settingsRow(
        settingCard(
            "Кэш изображений",
            "Очистить изображения в памяти",
            "apps",
            "cache",
            () -> {
              catalog.clearImageMemory();
              Toast.makeText(this, "Кэш изображений в памяти очищен", Toast.LENGTH_SHORT).show();
            }),
        settingCard(
            "О Luma", "Версия и информация о приложении", "home", "about", this::showAbout));
    settingsRow(
        settingCard(
            "Сохранить копию",
            "Избранное и настройки в файл",
            "favorite",
            "export",
            this::exportSettings),
        settingCard(
            "Восстановить копию",
            "Загрузить сохранённые настройки",
            "apps",
            "import",
            this::importSettings));
    settingsRow(
        settingCard(
            "Диагностика",
            "Сведения для поиска ошибок",
            "settings",
            "diagnostics",
            () ->
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Диагностика Luma")
                    .setMessage(AppDiagnostics.summary())
                    .setPositiveButton("Закрыть", null)
                    .show()),
        settingCard(
            "На главную", "Вернуться к фильмам и приложениям", "home", "home", this::showHome));
    space(body, 8);
    body.addView(
        label("Очистка изображений сохраняет избранное, токен и остальные настройки.", 13, MUTED));
    restoreTileFocus();
  }

  private void pageIntro(String title, String subtitle) {
    space(body, 20);
    body.addView(heading(title, 29));
    space(body, 9);
    body.addView(label(subtitle, 14, MUTED));
    space(body, 20);
  }

  private void settingsSection(String title) {
    TextView caption = heading(title, 16);
    caption.setPadding(unit(9), unit(12), 0, unit(8));
    body.addView(caption);
  }

  private View settingCard(
      String title, String subtitle, String symbol, String key, Runnable action) {
    LinearLayout card = new LinearLayout(this);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(unit(20), unit(12), unit(20), unit(12));
    card.setTag("settings:" + key);
    card.setContentDescription(title + ". " + subtitle);
    focus(card, CARD);
    card.setOnClickListener(v -> action.run());
    card.addView(icon(symbol, 0xffc4c7d2), new LinearLayout.LayoutParams(unit(28), unit(28)));
    LinearLayout copy = column();
    copy.setPadding(unit(17), 0, unit(12), 0);
    TextView name = heading(title, 16);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    copy.addView(name);
    space(copy, 6);
    TextView detail = label(subtitle, 12, MUTED);
    detail.setSingleLine(true);
    detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
    copy.addView(detail);
    card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(icon("next", MUTED), new LinearLayout.LayoutParams(unit(14), unit(18)));
    return card;
  }

  private void settingsRow(View first, View second) {
    LinearLayout row = new LinearLayout(this);
    row.setClipChildren(false);
    row.setPadding(unit(9), unit(8), unit(9), unit(8));
    LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, unit(78), 1);
    a.rightMargin = unit(18);
    row.addView(first, a);
    row.addView(second, new LinearLayout.LayoutParams(0, unit(78), 1));
    body.addView(row, new LinearLayout.LayoutParams(-1, unit(94)));
  }

  private void emptyPanel(
      String symbol, String title, String message, String actionTitle, Runnable action) {
    LinearLayout panel = column();
    panel.setPadding(unit(26), unit(26), unit(26), unit(24));
    panel.setBackground(LumaStyle.surface(CARD, unit(22), designScale));
    LinearLayout lead = new LinearLayout(this);
    lead.setGravity(Gravity.CENTER_VERTICAL);
    lead.addView(icon(symbol, 0xffc4c7d2), new LinearLayout.LayoutParams(unit(30), unit(30)));
    TextView name = heading(title, 21);
    name.setPadding(unit(15), 0, 0, 0);
    lead.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    panel.addView(lead);
    space(panel, 14);
    panel.addView(label(message, 15, MUTED));
    if (action != null) {
      space(panel, 20);
      TextView control = button(actionTitle, action);
      control.setTag("empty:action");
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, unit(44));
      panel.addView(control, lp);
    }
    body.addView(panel, new LinearLayout.LayoutParams(-1, -2));
  }

  private static final int EXPORT_SETTINGS = 4901, IMPORT_SETTINGS = 4902;
  private boolean backupBusy = false;
  private android.app.AlertDialog restoreProgress;

  private void backupError(Exception error) {
    android.util.Log.w("SashaTV", "Settings copy failed: " + error.getClass().getSimpleName());
    Toast.makeText(
            this,
            "Не удалось обработать копию: "
                + (error.getMessage() == null ? "файл недоступен" : error.getMessage()),
            Toast.LENGTH_LONG)
        .show();
  }

  private void exportSettings() {
    if (backupBusy) return;
    backupBusy = true;
    network.execute(
        () -> {
          try {
            SettingsBackup.write(this, SettingsBackup.EXPORT, SettingsBackup.capture(this));
            ui.post(
                () -> {
                  if (isDestroyed() || isFinishing()) return;
                  Intent intent =
                      new Intent(Intent.ACTION_CREATE_DOCUMENT)
                          .addCategory(Intent.CATEGORY_OPENABLE)
                          .setType("application/json")
                          .putExtra(Intent.EXTRA_TITLE, "sashatv-settings.json");
                  try {
                    startActivityForResult(intent, EXPORT_SETTINGS);
                  } catch (android.content.ActivityNotFoundException | SecurityException error) {
                    backupBusy = false;
                    Toast.makeText(
                            this,
                            "Копия создана. На ТВ нет выбора файла — её можно забрать через ADB.",
                            Toast.LENGTH_LONG)
                        .show();
                  }
                });
          } catch (Exception error) {
            ui.post(
                () -> {
                  backupBusy = false;
                  if (!isDestroyed()) backupError(error);
                });
          }
        });
  }

  private void importSettings() {
    if (backupBusy) return;
    new android.app.AlertDialog.Builder(this)
        .setTitle("Восстановить настройки")
        .setItems(
            new String[] {
              "Выбрать файл копии",
              "Копия, загруженная через ADB",
              "Настройки до последнего восстановления"
            },
            (dialog, which) -> {
              if (which == 0) {
                Intent intent =
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json");
                try {
                  backupBusy = true;
                  startActivityForResult(intent, IMPORT_SETTINGS);
                } catch (android.content.ActivityNotFoundException | SecurityException error) {
                  backupBusy = false;
                  Toast.makeText(
                          this,
                          "Выбор файла недоступен. Загрузи копию через ADB.",
                          Toast.LENGTH_LONG)
                      .show();
                }
              } else
                readSettingsCopy(
                    null, which == 1 ? SettingsBackup.IMPORT : SettingsBackup.PREVIOUS);
            })
        .setNegativeButton("Отмена", null)
        .show();
  }

  private void readSettingsCopy(android.net.Uri uri, String filename) {
    backupBusy = true;
    network.execute(
        () -> {
          try {
            BackupData copy =
                uri == null
                    ? SettingsBackup.read(this, filename)
                    : SettingsBackup.read(getContentResolver().openInputStream(uri));
            String summary = copy.summary();
            ui.post(
                () -> {
                  backupBusy = false;
                  if (isDestroyed() || isFinishing()) return;
                  new android.app.AlertDialog.Builder(this)
                      .setTitle("Заменить настройки этой копией?")
                      .setMessage(
                          summary
                              + "\n\n"
                              + "Прежние настройки сохранятся отдельно для отмены восстановления.")
                      .setPositiveButton("Восстановить", (d, w) -> applySettingsCopy(copy))
                      .setNegativeButton("Отмена", null)
                      .show();
                });
          } catch (Exception error) {
            ui.post(
                () -> {
                  backupBusy = false;
                  if (!isDestroyed()) backupError(error);
                });
          }
        });
  }

  private void applySettingsCopy(BackupData copy) {
    if (backupBusy) return;
    backupBusy = true;
    restoreProgress =
        new android.app.AlertDialog.Builder(this)
            .setMessage("Восстанавливаем настройки…")
            .setCancelable(false)
            .show();
    network.execute(
        () -> {
          try {
            SettingsBackup.restore(this, copy);
            ui.post(
                () -> {
                  backupBusy = false;
                  if (isDestroyed() || isFinishing()) return;
                  if (restoreProgress != null) restoreProgress.dismiss();
                  restoreProgress = null;
                  for (String type : new String[] {"movie", "tv"}) {
                    BrowseState state = browser(type);
                    state.request++;
                    state.loading = state.loaded = state.viewportReady = false;
                    state.rows = Collections.emptyList();
                    restoreBrowser(type);
                  }
                  favoritesPage = 1;
                  bannerIndex = 0;
                  homeOffset = 0;
                  renderedHomeKey = "";
                  restoreFocus = "settings:import";
                  showLauncherSettings();
                  refreshCatalog(true);
                  Toast.makeText(this, "Настройки восстановлены", Toast.LENGTH_SHORT).show();
                });
          } catch (Exception error) {
            ui.post(
                () -> {
                  backupBusy = false;
                  if (!isDestroyed()) {
                    if (restoreProgress != null) restoreProgress.dismiss();
                    restoreProgress = null;
                    backupError(error);
                  }
                });
          }
        });
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != EXPORT_SETTINGS && requestCode != IMPORT_SETTINGS) return;
    backupBusy = false;
    if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
    final android.net.Uri uri = data.getData();
    if (!"content".equals(uri.getScheme())) {
      backupError(new java.io.IOException("Нужен файл из системного выбора документов"));
      return;
    }
    if (requestCode == IMPORT_SETTINGS) {
      readSettingsCopy(uri, null);
      return;
    }
    backupBusy = true;
    network.execute(
        () -> {
          try {
            BackupData copy = SettingsBackup.read(this, SettingsBackup.EXPORT);
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new java.io.IOException("Файл недоступен для записи");
              out.write(copy.json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            ui.post(
                () -> {
                  backupBusy = false;
                  if (!isDestroyed())
                    Toast.makeText(this, "Копия сохранена", Toast.LENGTH_SHORT).show();
                });
          } catch (Exception error) {
            ui.post(
                () -> {
                  backupBusy = false;
                  if (!isDestroyed()) backupError(error);
                });
          }
        });
  }

  private View sidebarButton(String symbol, String description, Runnable action) {
    String kind =
        symbol.equals("⌂")
            ? "home"
            : symbol.equals("▶") ? "film" : symbol.equals("▦") ? "apps" : "settings";
    ImageView image = icon(kind, MUTED);
    image.setPadding(unit(12), unit(12), unit(12), unit(12));
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    image.setContentDescription(description);
    image.setTag("side:" + description);
    focus(image, 0x00000000);
    image.setOnClickListener(v -> action.run());
    return image;
  }

  private void setup() {
    updateDesignScale();
    LinearLayout shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setBackground(LumaStyle.backdrop());
    shell.setPadding(unit(80), unit(80), unit(24), unit(20));
    shell.setClipChildren(true);
    favoriteBadges.clear();
    for (CatalogManager.Film f : filmFavorites.all()) favoriteBadges.add(f.type + ":" + f.id);
    pageHeader = column();
    pageHeader.setClipChildren(false);
    shell.addView(pageHeader, new LinearLayout.LayoutParams(-1, -2));
    ScrollView scroll = new ScrollView(this);
    pageScroll = scroll;
    scroll.setFillViewport(true);
    // Clip scrolled content at the viewport, keeping the fixed header clear.
    // Rows retain their own padding for the focused tile scale animation.
    scroll.setClipChildren(true);
    scroll.setClipToPadding(true);
    scroll.setVerticalScrollBarEnabled(false);
    body = column();
    body.setClipChildren(false);
    body.setClipToPadding(false);
    body.setPadding(unit(4), 0, unit(4), unit(20));
    scroll.addView(body);
    // The viewport itself must clip, not only the long scrollable child.
    // Its clip bounds stay in local, unscrolled coordinates on the RenderNode.
    FrameLayout viewport = new FrameLayout(this);
    viewport.setClipChildren(true);
    viewport.setClipToPadding(true);
    viewport.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            view.setClipBounds(new android.graphics.Rect(0, 0, right - left, bottom - top)));
    viewport.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    shell.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
    FrameLayout outer = new FrameLayout(this);
    pageOuter = outer;
    outer.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    setContentView(outer);
    addDrawer(outer, false);
  }

  private void top() {
    FrameLayout header = buildHeader();
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(px(1280), px(80), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    // Keep header below the drawer/scrim, outside the scrolling viewport.
    pageOuter.addView(header, 1, params);
  }

  /** One source of geometry, icons, labels and active state on every page. */
  private FrameLayout buildHeader() {
    FrameLayout header = new FrameLayout(this);
    sharedHeader = header;
    header.setClipChildren(false);
    TextView brand = designText("Luma", 23, WHITE, true);
    brand.setGravity(Gravity.CENTER);
    brand.setLetterSpacing(-.04f);
    brand.setTag("icon:Главная");
    brand.setContentDescription("Luma. Главная");
    focus(brand, 0x00000000);
    brand.setOnClickListener(v -> showHome());
    place(header, brand, 12, 17, 90, 46);
    String[] titles = {"Главная", "Фильмы", "Сериалы", "Приложения", "Устройства"};
    String[] destinations = {"home", "movie", "tv", "all", "devices"};
    int[] x = {308, 413, 512, 615, 748}, widths = {90, 85, 90, 120, 110};
    Runnable[] actions = {
      this::showHome,
      () -> showBrowse("movie", false),
      () -> showBrowse("tv", false),
      this::showAll,
      this::showDevices
    };
    for (int i = 0; i < titles.length; i++)
      place(
          header,
          designButton(titles[i], actions[i], page.equals(destinations[i])),
          x[i],
          20,
          widths[i],
          41);
    place(
        header,
        iconButton("favorite", "Избранное", this::showFilmFavorites, page.equals("favorites")),
        980,
        20,
        40,
        40);
    place(
        header,
        iconButton("search", "Поиск", () -> showSearch(false), page.equals("search")),
        1030,
        20,
        40,
        40);
    place(
        header,
        iconButton("settings", "Настройки", this::openSettings, page.equals("settings")),
        1090,
        20,
        40,
        40);
    place(
        header,
        iconButton("wifi", "Настройки сети", this::openWifiSettings, false),
        1140,
        20,
        40,
        40);
    TextClock time = new TextClock(this);
    time.setFormat24Hour("HH:mm");
    time.setFormat12Hour("HH:mm");
    time.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(18));
    time.setTextColor(WHITE);
    time.setGravity(Gravity.CENTER);
    place(header, time, 1200, 20, 65, 40);
    return header;
  }

  private void openWifiSettings() {
    try {
      startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
    } catch (android.content.ActivityNotFoundException | SecurityException error) {
      openSystemSettings();
    }
  }

  private void row(String title, List<ResolveInfo> list) {
    TextView h = heading(title, 20);
    body.addView(h);
    space(body, 10);
    if (list.isEmpty()) {
      body.addView(label("Нет доступных приложений", 14, MUTED));
      return;
    }
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.setClipChildren(false);
    scroll.setClipToPadding(false);
    LinearLayout items = new LinearLayout(this);
    items.setClipChildren(false);
    items.setClipToPadding(false);
    items.setPadding(unit(10), unit(10), unit(10), unit(10));
    for (ResolveInfo r : list) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(unit(135), unit(96));
      p.rightMargin = unit(16);
      View appTile = tile(r);
      items.addView(appTile, p);
    }
    scroll.addView(items);
    body.addView(scroll);
  }

  private ArrayList<String> favoritePackages() {
    String value =
        getSharedPreferences("sasha_favorites", MODE_PRIVATE).getString("packages", null);
    ArrayList<String> result = new ArrayList<>();
    if (value == null) {
      Collections.addAll(
          result,
          "com.google.android.youtube.tv",
          "top.rootu.lampa",
          "com.vk.tv",
          "org.videolan.vlc",
          "mobi.zona",
          "org.amnezia.vpn",
          "org.amnezia.awg");
      return result;
    }
    try {
      JSONArray array = new JSONArray(value);
      for (int i = 0; i < array.length(); i++) {
        String p = array.optString(i, "");
        if (!p.isEmpty() && !result.contains(p)) result.add(p);
      }
    } catch (org.json.JSONException error) {
      android.util.Log.w("SashaTV", "Invalid favorites; using defaults", error);
      result.clear();
      Collections.addAll(
          result,
          "com.google.android.youtube.tv",
          "top.rootu.lampa",
          "com.vk.tv",
          "org.videolan.vlc",
          "mobi.zona",
          "org.amnezia.vpn",
          "org.amnezia.awg");
    }
    return result;
  }

  private void saveFavorites(ArrayList<String> list) {
    JSONArray array = new JSONArray();
    for (String p : list) array.put(p);
    getSharedPreferences("sasha_favorites", MODE_PRIVATE)
        .edit()
        .putString("packages", array.toString())
        .apply();
  }

  private void editFavorite(ResolveInfo app) {
    String pkg = app.activityInfo.packageName;
    ArrayList<String> list = favoritePackages();
    if (!list.contains(pkg)) {
      if (heldConfirm != -1) swallowedKeys.add(heldConfirm);
      list.add(pkg);
      saveFavorites(list);
      Toast.makeText(this, "Добавлено в быстрый запуск", Toast.LENGTH_SHORT).show();
      page = "home";
      restoreFocus = "app:" + pkg;
      showHome();
      return;
    }
    movingOrder = new ArrayList<>();
    movingOrder.addAll(list);
    movingPackage = pkg;
    ignoreRelease = heldConfirm != -1;
    showHome();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    int code = event.getKeyCode(), action = event.getAction();
    if (action == KeyEvent.ACTION_DOWN
        && (code == KeyEvent.KEYCODE_DPAD_LEFT
            || code == KeyEvent.KEYCODE_DPAD_RIGHT
            || code == KeyEvent.KEYCODE_DPAD_UP
            || code == KeyEvent.KEYCODE_DPAD_DOWN))
      lastNavigationInput = android.os.SystemClock.elapsedRealtime();
    if (code == KeyEvent.KEYCODE_DPAD_CENTER
        || code == KeyEvent.KEYCODE_ENTER
        || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
      if (action == KeyEvent.ACTION_DOWN) heldConfirm = code;
      else if (action == KeyEvent.ACTION_UP) heldConfirm = -1;
    }
    if (swallowedKeys.contains(code)) {
      if (action == KeyEvent.ACTION_UP) swallowedKeys.remove(code);
      return true;
    }
    if (movingPackage != null) {
      if (code == KeyEvent.KEYCODE_DPAD_CENTER
          || code == KeyEvent.KEYCODE_ENTER
          || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
        if (action == KeyEvent.ACTION_UP) {
          if (ignoreRelease) {
            ignoreRelease = false;
            return true;
          }
          if (!event.isCanceled()) finishMove(true, false);
        }
        return true;
      }
      if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
        if (action == KeyEvent.ACTION_DOWN) {
          // Step across hidden/uninstalled favorites without dropping them from preferences.
          int direction = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
          int start = movingOrder.indexOf(movingPackage), target = start + direction;
          while (target >= 0 && target < movingOrder.size() && !hasApp(movingOrder.get(target)))
            target += direction;
          if (start >= 0 && target >= 0 && target < movingOrder.size()) {
            while (movingOrder.indexOf(movingPackage) != target)
              LauncherLogic.move(movingOrder, movingPackage, direction);
            showHome();
          }
        }
        return true;
      }
      if (code == KeyEvent.KEYCODE_BACK) {
        if (action == KeyEvent.ACTION_UP && !event.isCanceled()) finishMove(false, false);
        return true;
      }
      if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
          swallowedKeys.add(code);
          finishMove(true, true);
        }
        return true;
      }
      if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
    }
    return super.dispatchKeyEvent(event);
  }

  private boolean hasApp(String pkg) {
    for (ResolveInfo app : installed) if (app.activityInfo.packageName.equals(pkg)) return true;
    return false;
  }

  private void openLampaCard(String id, String media) {
    if (!hasApp("top.rootu.lampa")) {
      Toast.makeText(this, "Lampa не найдена", Toast.LENGTH_SHORT).show();
      return;
    }
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setComponent(
        new android.content.ComponentName("top.rootu.lampa", "top.rootu.lampa.MainActivity"));
    intent.putExtra("id", id);
    intent.putExtra("media", media);
    intent.putExtra("source", "tmdb");
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    try {
      startActivity(intent);
    } catch (android.content.ActivityNotFoundException | SecurityException error) {
      android.util.Log.w("SashaTV", "Cannot open Lampa card " + id, error);
      Toast.makeText(this, "Не удалось открыть карточку в Lampa", Toast.LENGTH_SHORT).show();
    }
  }

  private void openLampa() {
    for (ResolveInfo app : installed)
      if (app.activityInfo.packageName.equals("top.rootu.lampa")) {
        launch(app);
        return;
      }
    Toast.makeText(this, "Lampa не найдена", Toast.LENGTH_SHORT).show();
  }

  private ImageView weatherIconView;
  private int weatherCode = -1;
  private TextView weatherTempView, weatherStatusView;
  private String weatherTemperature = "—°", weatherDescription = "Загрузка...";
  private long lastWeatherSuccess = 0, lastWeatherAttempt = 0;
  private boolean weatherLoading = false;
  private int weatherRequest = 0;

  private WeatherCity weatherCity() {
    return WeatherCity.load(getSharedPreferences("sasha_weather", MODE_PRIVATE));
  }

  private void chooseWeatherCity() {
    LinearLayout form = column();
    form.setPadding(unit(24), unit(16), unit(24), unit(10));
    form.addView(label("Сейчас: " + weatherCity().name, 14, MUTED));
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setHint("Название города");
    input.setTextColor(WHITE);
    input.setHintTextColor(MUTED);
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(100)});
    form.addView(input, new LinearLayout.LayoutParams(-1, unit(52)));
    TextView status = label("Город вводится вручную. Геолокация не используется.", 13, MUTED);
    form.addView(status);
    android.app.AlertDialog dialog =
        new android.app.AlertDialog.Builder(this)
            .setTitle("Погода")
            .setView(form)
            .setPositiveButton("Найти", null)
            .setNeutralButton("Обновить погоду", (d, w) -> updateWeather(true))
            .setNegativeButton("Отмена", null)
            .create();
    final int[] search = {0};
    dialog.setOnDismissListener(d -> search[0]++);
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    String query = input.getText().toString().trim();
                    if (query.length() < 2) {
                      input.setError("Введи хотя бы 2 символа");
                      return;
                    }
                    final int ticket = ++search[0];
                    status.setText("Ищем город…");
                    network.execute(
                        () -> {
                          try {
                            List<WeatherCity> cities = WeatherCity.search(query);
                            ui.post(
                                () -> {
                                  if (isDestroyed() || !dialog.isShowing() || ticket != search[0])
                                    return;
                                  if (cities.isEmpty()) {
                                    status.setText("Город не найден. Уточни название.");
                                    return;
                                  }
                                  String[] labels = new String[cities.size()];
                                  for (int i = 0; i < labels.length; i++)
                                    labels[i] = cities.get(i).label;
                                  status.setText("Выбери город из результатов");
                                  new android.app.AlertDialog.Builder(this)
                                      .setTitle("Выбери город")
                                      .setItems(
                                          labels,
                                          (choice, which) -> {
                                            WeatherCity selected = cities.get(which);
                                            selected.save(
                                                getSharedPreferences(
                                                    "sasha_weather", MODE_PRIVATE));
                                            weatherRequest++;
                                            weatherLoading = false;
                                            lastWeatherAttempt = lastWeatherSuccess = 0;
                                            weatherCode = -1;
                                            weatherTemperature = "—°";
                                            weatherDescription = "Загрузка…";
                                            dialog.dismiss();
                                            if (page.equals("settings")) showLauncherSettings();
                                            else showHome();
                                            updateWeather(true);
                                          })
                                      .setNegativeButton("Назад", null)
                                      .show();
                                });
                          } catch (Exception error) {
                            ui.post(
                                () -> {
                                  if (!isDestroyed() && dialog.isShowing() && ticket == search[0])
                                    status.setText(
                                        "Не удалось найти город. Проверь сеть и попробуй ещё раз.");
                                });
                          }
                        });
                  });
        });
    dialog.show();
    input.requestFocus();
  }

  private void paintWeather() {
    long now = System.currentTimeMillis();
    boolean usable = LauncherLogic.fresh(now, lastWeatherSuccess, 24 * 60 * 60 * 1000L);
    boolean fresh = LauncherLogic.fresh(now, lastWeatherSuccess, 15 * 60 * 1000L);
    if (weatherIconView != null) {
      weatherIconView.setImageDrawable(
          new LineIcon(usable ? LauncherLogic.weatherIcon(weatherCode) : "weather", 0xffd4e1ed));
    }
    if (weatherTempView != null) weatherTempView.setText(usable ? weatherTemperature : "—°");
    if (weatherStatusView != null)
      weatherStatusView.setText(
          usable
              ? (fresh ? weatherDescription : "Устаревшие данные")
              : weatherLoading ? "Загрузка…" : "Нет данных");
  }

  private void updateWeather() {
    updateWeather(false);
  }

  private void updateWeather(boolean force) {
    paintWeather();
    if (!resumed
        || isDestroyed()
        || (!page.equals("home") && !page.equals("settings"))
        || weatherLoading) return;
    long now = android.os.SystemClock.elapsedRealtime();
    if (!force
        && (LauncherLogic.fresh(System.currentTimeMillis(), lastWeatherSuccess, 15 * 60 * 1000L)
            || (lastWeatherAttempt > 0 && now - lastWeatherAttempt < 60000))) return;
    final WeatherCity city = weatherCity();
    final int request = ++weatherRequest;
    weatherLoading = true;
    lastWeatherAttempt = now;
    paintWeather();
    network.execute(
        () -> {
          java.net.HttpURLConnection conn = null;
          try {
            java.net.URL url = new java.net.URL(city.forecastUrl());
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200)
              throw new java.io.IOException("HTTP " + conn.getResponseCode());
            StringBuilder data = new StringBuilder();
            try (java.io.Reader reader =
                new java.io.InputStreamReader(
                    conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
              char[] buffer = new char[2048];
              int count;
              while ((count = reader.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted())
                  throw new java.io.InterruptedIOException();
                if (data.length() + count > 65536)
                  throw new java.io.IOException("Response too large");
                data.append(buffer, 0, count);
              }
            }
            org.json.JSONObject current =
                new org.json.JSONObject(data.toString()).getJSONObject("current");
            final String temperature =
                LauncherLogic.temperature(current.getDouble("temperature_2m"));
            final int code = current.getInt("weather_code");
            final String description = LauncherLogic.describeWeather(code);
            ui.post(
                () -> {
                  if (isDestroyed() || isFinishing() || request != weatherRequest) return;
                  weatherLoading = false;
                  lastWeatherSuccess = System.currentTimeMillis();
                  weatherTemperature = temperature;
                  weatherCode = code;
                  weatherDescription = description;
                  getSharedPreferences("sasha_weather", MODE_PRIVATE)
                      .edit()
                      .putLong("time", lastWeatherSuccess)
                      .putInt("code", weatherCode)
                      .putString("temperature", temperature)
                      .putString("description", description)
                      .apply();
                  paintWeather();
                });
          } catch (Exception e) {
            android.util.Log.w("SashaTV", "Weather request failed", e);
            ui.post(
                () -> {
                  if (isDestroyed() || isFinishing() || request != weatherRequest) return;
                  weatherLoading = false;
                  paintWeather();
                });
          } finally {
            if (conn != null) conn.disconnect();
          }
        });
  }

  private void updateDesignScale() {
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
    designScale = Math.min(metrics.widthPixels / 1280f, metrics.heightPixels / 720f);
  }

  private float designScale = 1f;
  private FrameLayout designRoot;

  private int px(float value) {
    return Math.round(value * designScale);
  }

  private void place(FrameLayout parent, View child, int x, int y, int w, int h) {
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(px(w), px(h));
    p.leftMargin = px(x);
    p.topMargin = px(parent == homeContent ? y - 80 : y);
    parent.addView(child, p);
  }

  private TextView designText(String text, int size, int color, boolean bold) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTextColor(color);
    v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
    v.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    v.setIncludeFontPadding(false);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private TextView designButton(String text, Runnable action, boolean active) {
    TextView v = designText(text, 15, 0xffe7edf7, false);
    v.setTag("button:" + text);
    v.setGravity(Gravity.CENTER);
    focus(v, active ? 0xff35363e : 0x00000000);
    v.setOnClickListener(view -> action.run());
    if (text.equals("Настройки")) aboutOnHold(v);
    return v;
  }

  private FrameLayout roundedPanel(int color) {
    FrameLayout p = new FrameLayout(this);
    p.setBackground(LumaStyle.surface(color, unit(14), designScale));
    p.setClipToOutline(true);
    return p;
  }

  private View designShortcut(
      String symbol, String name, String subtitle, int color, Runnable action) {
    FrameLayout f = new FrameLayout(this);
    focus(f, color);
    f.setOnClickListener(v -> action.run());
    place(f, icon(symbol, symbol.equals("vpn") ? 0xff9bd7b8 : 0xffd2d4dc), 18, 17, 34, 34);
    place(f, designText(name, 15, WHITE, true), 18, 64, 120, 25);
    TextView subtitleView = designText(subtitle, 11, MUTED, false);
    subtitleView.setSingleLine(true);
    subtitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    if (name.equals("Устройства")) hdmiHomeSubtitle = subtitleView;
    if (name.equals("VPN")) {
      vpnSubtitle = subtitleView;
      vpnTile = f;
      f.setOnLongClickListener(
          v -> {
            vpnSettings();
            return true;
          });
    }
    place(f, subtitleView, 18, 94, 120, 28);
    place(f, icon("next", MUTED), 127, 66, 13, 18);
    f.setTag("shortcut:" + name);
    f.setContentDescription(name + ", " + subtitle);
    return f;
  }

  private void paintHomeStatuses() {
    for (Map.Entry<String, TextView> entry : homeFeedLabels.entrySet())
      setStatusText(entry.getValue(), catalog.feedStatus(entry.getKey()));
    for (Map.Entry<String, TextView> entry : homeEmptyLabels.entrySet())
      setStatusText(entry.getValue(), catalog.feedStatus(entry.getKey()));
  }

  private void setStatusText(TextView view, String text) {
    if (!text.contentEquals(view.getText())) view.setText(text);
  }

  private void refreshCatalog(boolean force) {
    catalog.refresh(
        force,
        () -> {
          if (isDestroyed() || isFinishing()) return;
          if (resumed && page.equals("home")) {
            paintHomeStatuses();
            ui.removeCallbacks(homeUpdate);
            ui.postDelayed(homeUpdate, 180);
          }
        });
    if (page.equals("home")) paintHomeStatuses();
  }

  private View onlineCard(CatalogManager.Film film, String source) {
    LinearLayout card = column();
    card.setClipChildren(false);
    card.setClipToPadding(false);
    card.setTag("home:" + source + ":" + film.type + ":" + film.id);
    card.setFocusable(true);
    card.setDefaultFocusHighlightEnabled(false);
    FrameLayout image = roundedPanel(CARD);
    ImageView picture = new ImageView(this);
    picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.addView(picture, new FrameLayout.LayoutParams(-1, -1));
    card.addView(image, new LinearLayout.LayoutParams(-1, px(98)));
    catalog.image(picture, film.image);
    TextView title = designText(film.title, 12, WHITE, false);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(title, new LinearLayout.LayoutParams(-1, px(22)));
    card.addView(
        designText(
            film.year + " · " + (film.type.equals("tv") ? "Сериал" : "Фильм"), 11, MUTED, false),
        new LinearLayout.LayoutParams(-1, px(17)));
    liftOnFocus(card);
    card.setContentDescription(film.title + ", " + film.year + ". Удерживай OK для описания");
    card.setOnClickListener(v -> openLampaCard(film.id, film.type));
    card.setOnLongClickListener(
        v -> {
          showCatalogDetails(film);
          return true;
        });
    return card;
  }

  private android.app.AlertDialog detailsDialog;

  private void showCatalogDetails(CatalogManager.Film film) {
    if (detailsDialog != null) detailsDialog.dismiss();
    rememberBrowsePosition();
    LinearLayout panel = column();
    panel.setPadding(unit(28), unit(24), unit(28), unit(20));
    panel.setBackground(LumaStyle.surface(CARD, unit(24), designScale));
    TextView title = heading(film.title, 24);
    title.setMaxLines(2);
    panel.addView(title);
    space(panel, 14);
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.TOP);
    panel.addView(row, new LinearLayout.LayoutParams(-1, unit(330)));
    FrameLayout posterFrame = roundedPanel(0xff25262c);
    posterFrame.addView(
        icon(film.type.equals("tv") ? "series" : "film", MUTED),
        new FrameLayout.LayoutParams(unit(42), unit(42), Gravity.CENTER));
    ImageView poster = new ImageView(this);
    poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
    posterFrame.addView(poster, new FrameLayout.LayoutParams(-1, -1));
    row.addView(posterFrame, new LinearLayout.LayoutParams(unit(215), unit(322)));
    if (!film.image.isEmpty()) catalog.image(poster, film.image);
    LinearLayout text = column();
    text.setPadding(unit(22), 0, 0, 0);
    row.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
    TextView meta =
        label(film.year + " · " + (film.type.equals("tv") ? "Сериал" : "Фильм"), 14, MUTED);
    text.addView(meta);
    TextView rating = label("Загружаем рейтинг…", 15, WHITE);
    rating.setPadding(0, unit(10), 0, unit(8));
    text.addView(rating);
    TextView genres = label("", 14, MUTED);
    text.addView(genres);
    TextView duration = label("", 14, MUTED);
    duration.setPadding(0, unit(8), 0, unit(10));
    text.addView(duration);
    ScrollView synopsis = new ScrollView(this);
    synopsis.setFillViewport(true);
    synopsis.setFocusable(true);
    synopsis.setDefaultFocusHighlightEnabled(false);
    synopsis.setTag("details:description");
    TextView overview =
        label(film.overview.isEmpty() ? "Описание загружается…" : film.overview, 16, WHITE);
    overview.setPadding(0, 0, unit(10), unit(10));
    synopsis.addView(overview);
    text.addView(synopsis, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView status = label("Загружаем подробности TMDB…", 12, MUTED);
    status.setPadding(0, unit(10), 0, unit(8));
    panel.addView(status);
    View retry = button("Повторить загрузку", () -> {});
    retry.setVisibility(View.GONE);
    panel.addView(retry);
    space(panel, 10);
    LinearLayout actions = new LinearLayout(this);
    actions.setClipChildren(false);
    actions.setPadding(unit(5), unit(5), unit(5), unit(5));
    TextView open = button("▶  В Lampa", () -> {});
    open.setTextColor(0xff10151c);
    focus(open, 0xffdce0e7);
    TextView favorite =
        button(filmFavorites.contains(film) ? "Убрать из избранного" : "В избранное", () -> {});
    TextView close = button("Закрыть", () -> {});
    for (TextView action : new TextView[] {open, favorite, close}) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, unit(48), 1);
      lp.rightMargin = action == close ? 0 : unit(12);
      actions.addView(action, lp);
    }
    panel.addView(actions);
    View originalFocus = getCurrentFocus();
    final String originalTag =
        originalFocus != null && originalFocus.getTag() instanceof String
            ? (String) originalFocus.getTag()
            : null;
    android.app.AlertDialog dialog =
        new android.app.AlertDialog.Builder(this).setView(panel).create();
    open.setOnClickListener(
        v -> {
          dialog.dismiss();
          openLampaCard(film.id, film.type);
        });
    favorite.setOnClickListener(
        v -> {
          toggleFilmFavorite(film);
          favorite.setText(filmFavorites.contains(film) ? "Убрать из избранного" : "В избранное");
        });
    close.setOnClickListener(v -> dialog.dismiss());
    Runnable wireActions =
        () -> {
          View above = retry.getVisibility() == View.VISIBLE ? retry : synopsis;
          link(synopsis, above == synopsis ? open : retry, KeyEvent.KEYCODE_DPAD_DOWN);
          link(synopsis, synopsis, KeyEvent.KEYCODE_DPAD_UP);
          link(synopsis, synopsis, KeyEvent.KEYCODE_DPAD_LEFT);
          link(synopsis, synopsis, KeyEvent.KEYCODE_DPAD_RIGHT);
          link(retry, synopsis, KeyEvent.KEYCODE_DPAD_UP);
          link(retry, open, KeyEvent.KEYCODE_DPAD_DOWN);
          link(retry, retry, KeyEvent.KEYCODE_DPAD_LEFT);
          link(retry, retry, KeyEvent.KEYCODE_DPAD_RIGHT);
          View[] buttons = {open, favorite, close};
          for (int i = 0; i < buttons.length; i++) {
            link(buttons[i], buttons[Math.max(0, i - 1)], KeyEvent.KEYCODE_DPAD_LEFT);
            link(
                buttons[i],
                buttons[Math.min(buttons.length - 1, i + 1)],
                KeyEvent.KEYCODE_DPAD_RIGHT);
            link(buttons[i], above, KeyEvent.KEYCODE_DPAD_UP);
            link(buttons[i], buttons[i], KeyEvent.KEYCODE_DPAD_DOWN);
          }
        };
    wireActions.run();
    detailsDialog = dialog;
    dialog.setOnDismissListener(
        d -> {
          catalog.cancelDetails();
          if (detailsDialog == dialog) detailsDialog = null;
          if (!isDestroyed() && originalTag != null) {
            View target = getWindow().getDecorView().findViewWithTag(originalTag);
            if (target != null) target.requestFocus();
          }
        });
    dialog.show();
    open.requestFocus();
    if (dialog.getWindow() != null) {
      dialog
          .getWindow()
          .setBackgroundDrawable(
              new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
      dialog
          .getWindow()
          .setLayout(
              Math.min(unit(1060), getResources().getDisplayMetrics().widthPixels - unit(50)), -2);
    }
    final CatalogManager.DetailsCallback callback =
        (details, message) -> {
          if (isDestroyed() || !dialog.isShowing() || detailsDialog != dialog) return;
          status.setText(message == null ? "Данные TMDB" : message);
          boolean retryHadFocus = retry.hasFocus();
          retry.setVisibility(message == null ? View.GONE : View.VISIBLE);
          wireActions.run();
          if (retryHadFocus && retry.getVisibility() != View.VISIBLE) open.requestFocus();
          if (details != null) {
            title.setText(details.film.title);
            meta.setText(details.film.year + " · " + (film.type.equals("tv") ? "Сериал" : "Фильм"));
            rating.setText(details.ratingLabel());
            genres.setText(details.genres.isEmpty() ? "Жанры не указаны" : details.genres);
            duration.setText(details.runtimeLabel());
            overview.setText(
                details.film.overview.isEmpty()
                    ? "Описание пока отсутствует."
                    : details.film.overview);
            if (!details.poster.isEmpty()) {
              poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
              catalog.image(poster, details.poster);
            }
          } else {
            rating.setText("Дополнительные данные недоступны");
            if (film.overview.isEmpty())
              overview.setText(
                  "Описание не загрузилось. Можно повторить запрос или открыть карточку в Lampa.");
          }
        };
    retry.setOnClickListener(
        v -> {
          status.setText("Повторная загрузка…");
          catalog.details(film, true, callback);
        });
    catalog.details(film, false, callback);
  }

  private void catalogSettings() {
    String[] actions = {
      catalog.configured() ? "Заменить токен TMDB" : "Ввести токен TMDB",
      "Обновить подборку",
      "О каталоге и TMDB",
      "Отключить каталог"
    };
    new android.app.AlertDialog.Builder(this)
        .setTitle("Каталог фильмов")
        .setItems(
            actions,
            (d, which) -> {
              if (which == 0) {
                EditText input = new EditText(this);
                input.setSingleLine(true);
                input.setHint("API Read Access Token");
                input.setInputType(
                    android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                android.app.AlertDialog dialog =
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("Токен TMDB")
                        .setView(input)
                        .setPositiveButton("Сохранить", null)
                        .setNegativeButton("Отмена", null)
                        .create();
                dialog.setOnShowListener(
                    x ->
                        dialog
                            .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(
                                v -> {
                                  try {
                                    catalog.setToken(input.getText().toString());
                                    dialog.dismiss();
                                    showHome();
                                  } catch (Exception error) {
                                    input.setError(
                                        "Не удалось сохранить. Нужен API Read Access Token.");
                                  }
                                }));
                dialog.show();
              } else if (which == 1) {
                refreshCatalog(true);
              } else if (which == 2) {
                catalogAbout();
              } else {
                catalog.clear();
                showHome();
              }
            })
        .setNegativeButton("Закрыть", null)
        .show();
  }

  private void catalogAbout() {
    LinearLayout content = column();
    content.setPadding(unit(24), unit(8), unit(24), unit(16));
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.tmdb_logo);
    logo.setScaleType(ImageView.ScaleType.FIT_START);
    content.addView(logo, new LinearLayout.LayoutParams(unit(150), unit(40)));
    TextView info =
        label(
            "Данные и изображения: TMDB. Подборка за неделю, обновление раз в 6 часов при открытии"
                + " главной.\n\n"
                + "This product uses the TMDB API but is not endorsed or certified by TMDB.\n\n"
                + "https://www.themoviedb.org",
            14,
            WHITE);
    content.addView(info);
    new android.app.AlertDialog.Builder(this)
        .setTitle("О каталоге")
        .setView(content)
        .setPositiveButton("Закрыть", null)
        .show();
  }

  private void showHome() {
    if (page.equals("home") && homeScroll != null) homeOffset = homeScroll.getScrollY();
    else homeOffset = 0;
    beginPage("home");
    if (!bannerSource.equals(catalog.primarySource())) {
      bannerSource = catalog.primarySource();
      bannerIndex = 0;
    }
    bannerIndex = HomeFeeds.step(bannerIndex, 0, catalog.films().size());
    final CatalogManager.Film featured =
        catalog.films().isEmpty() ? null : catalog.films().get(bannerIndex);
    shownFilm = featured;
    int extraRows = (catalog.homeSources().size() - 1) * 180;
    updateDesignScale();
    FrameLayout background = new FrameLayout(this);
    background.setBackground(LumaStyle.backdrop());
    background.setClipChildren(false);
    designRoot = new FrameLayout(this);
    designRoot.setClipChildren(false);
    FrameLayout.LayoutParams screen =
        new FrameLayout.LayoutParams(px(1280), px(720), Gravity.CENTER);
    background.addView(designRoot, screen);
    setContentView(background);
    FrameLayout d = designRoot;
    place(d, buildHeader(), 0, 0, 1280, 80);
    final FrameLayout surface = d;
    homeScroll = new ScrollView(this);
    homeScroll.setFillViewport(true);
    homeScroll.setVerticalScrollBarEnabled(false);
    homeScroll.setClipChildren(true);
    homeScroll.setClipToPadding(true);
    homeContent = new FrameLayout(this);
    homeContent.setClipChildren(false);
    homeContent.setClipToPadding(false);
    homeScroll.addView(homeContent, new ScrollView.LayoutParams(px(1280), px(640 + extraRows)));
    FrameLayout homeViewport = new FrameLayout(this);
    homeViewport.setClipChildren(true);
    homeViewport.setClipToPadding(true);
    homeViewport.addOnLayoutChangeListener(
        (view, l, t, r, b, ol, ot, or, ob) ->
            view.setClipBounds(new android.graphics.Rect(0, 0, r - l, b - t)));
    homeViewport.addView(homeScroll, new FrameLayout.LayoutParams(-1, -1));
    place(surface, homeViewport, 0, 80, 1280, 640);
    d = homeContent;
    FrameLayout hero = roundedPanel(0xff121922);
    FrostedBackdrop artwork = new FrostedBackdrop(this);
    ImageView heroArt = artwork.image();
    if (featured == null && !catalog.configured())
      heroArt.setImageResource(R.drawable.luma_landscape);
    else if (featured != null) catalog.banner(heroArt, featured.image);
    hero.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
    place(hero, designText("КИНО И СЕРИАЛЫ", 11, 0xffd2d0d3, false), 35, 88, 300, 20);
    TextView heroTitle =
        designText(
            featured == null
                ? (catalog.configured() ? "Подборка загружается" : "Твой вечер с Luma")
                : featured.title,
            29,
            WHITE,
            true);
    heroTitle.setMaxLines(2);
    heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    place(hero, heroTitle, 35, 118, 430, 70);
    place(
        hero,
        designText(
            featured == null
                ? (catalog.configured() ? "Дождись загрузки TMDB" : "Фильмы, сериалы и приложения")
                : featured.year + "  ·  " + (featured.type.equals("tv") ? "Сериал" : "Фильм"),
            14,
            0xffb9bec7,
            false),
        35,
        194,
        400,
        22);
    place(
        hero,
        heroAction(
            "Смотреть",
            () -> {
              if (featured == null) openLampa();
              else openLampaCard(featured.id, featured.type);
            },
            true),
        35,
        230,
        155,
        43);
    place(hero, heroAction("Подробнее", this::showMovieDetails, false), 203, 230, 127, 43);
    if (catalog.films().size() > 1) {
      TextView counter =
          designText((bannerIndex + 1) + " / " + catalog.films().size(), 11, WHITE, false);
      counter.setGravity(Gravity.CENTER);
      counter.setBackground(bg(0xaa071018, 0));
      place(hero, counter, 767, 231, 63, 36);
      View previous =
          iconButton(
              "previous", "Предыдущий баннер", () -> stepBanner(-1, "banner:previous"), true);
      previous.setTag("banner:previous");
      place(hero, previous, 840, 231, 36, 36);
      View next = iconButton("next", "Следующий баннер", () -> stepBanner(1, "banner:next"), true);
      next.setTag("banner:next");
      place(hero, next, 886, 231, 36, 36);
    }
    for (int i = 0; i < hero.getChildCount(); i++) {
      View child = hero.getChildAt(i);
      if (child instanceof TextView) ((TextView) child).setShadowLayer(px(3), 0, px(1), 0x90000000);
    }
    place(d, hero, 80, 85, 988, 287);
    FrameLayout weather = roundedPanel(0xff28333e);
    ImageView landscape = new ImageView(this);
    landscape.setScaleType(ImageView.ScaleType.CENTER_CROP);
    landscape.setImageResource(R.drawable.luma_landscape);
    weather.addView(landscape, new FrameLayout.LayoutParams(-1, -1));
    View cover = new View(this);
    // Quiet gradient keeps weather data legible over Luma's own vector landscape.
    cover.setBackground(
        new android.graphics.drawable.Drawable() {
          private final android.graphics.Paint paint = new android.graphics.Paint();

          @Override
          protected void onBoundsChange(android.graphics.Rect b) {
            super.onBoundsChange(b);
            paint.setShader(
                new android.graphics.LinearGradient(
                    0,
                    b.top,
                    0,
                    b.bottom,
                    new int[] {0xff28333e, 0xee28333e, 0x8828333e, 0x0028333e},
                    new float[] {0f, .45f, .63f, .82f},
                    android.graphics.Shader.TileMode.CLAMP));
          }

          @Override
          public void draw(android.graphics.Canvas canvas) {
            canvas.drawRect(getBounds(), paint);
          }

          @Override
          public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
          }

          @Override
          public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
          }

          @Override
          public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
          }
        });
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
    weather.addView(cover, cp);
    weatherTempView = designText(weatherTemperature, 36, WHITE, false);
    weatherStatusView = designText(weatherDescription, 11, WHITE, false);
    weatherStatusView.setMaxLines(2);
    weatherIconView = icon("weather", 0xffd4e1ed);
    place(weather, weatherIconView, 69, 20, 47, 40);
    place(weather, weatherTempView, 15, 65, 155, 48);
    weatherTempView.setGravity(Gravity.CENTER);
    place(weather, weatherStatusView, 15, 119, 155, 25);
    weatherStatusView.setGravity(Gravity.CENTER);
    TextView city = designText(weatherCity().name, 11, 0xffc4d1dc, false);
    city.setGravity(Gravity.CENTER);
    city.setSingleLine(true);
    city.setEllipsize(android.text.TextUtils.TruncateAt.END);
    place(weather, city, 15, 148, 155, 20);
    place(d, weather, 1082, 85, 185, 287);
    TextView credit = designText("Open-Meteo", 9, WHITE, false);
    credit.setBackgroundColor(0x660d1119);
    credit.setGravity(Gravity.CENTER);
    place(weather, credit, 37, 264, 110, 18);
    updateWeather();
    drawHomeRows(d);
    refreshCatalog(false);
    place(d, designText("Приложения", 19, WHITE, false), 80, 395, 250, 24);
    HorizontalScrollView appScroll = new HorizontalScrollView(this);
    appScroll.setHorizontalScrollBarEnabled(false);
    appScroll.setClipChildren(false);
    appScroll.setClipToPadding(false);
    LinearLayout appRow = new LinearLayout(this);
    appRow.setTag("home:apps-row");
    appRow.setClipChildren(false);
    appRow.setClipToPadding(false);
    appRow.setPadding(px(7), px(6), px(7), px(6));
    ArrayList<String> favorites = movingOrder != null ? movingOrder : favoritePackages();
    // Eight tightly spaced cards across the same content width as the reference.
    // More favorites scroll horizontally; fewer favorites keep these dimensions.
    int tileWidth = 137;
    int gap = 12;
    for (String pkg : favorites)
      for (ResolveInfo app : installed)
        if (app.activityInfo.packageName.equals(pkg)) {
          View v = tile(app);
          LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(tileWidth), px(98));
          p.rightMargin = px(gap);
          appRow.addView(v, p);
          break;
        }
    LinearLayout all = column();
    all.setGravity(Gravity.CENTER);
    all.setFocusable(true);
    all.setDefaultFocusHighlightEnabled(false);
    liftOnFocus(all);
    FrameLayout allPlate = new FrameLayout(this);
    allPlate.setDuplicateParentStateEnabled(true);
    allPlate.setElevation(px(2));
    allPlate.setBackground(LumaStyle.states(CARD, px(12), designScale));
    all.addView(allPlate, new LinearLayout.LayoutParams(-1, px(70)));
    all.setTag("button:Все приложения");
    all.setContentDescription("Все приложения");
    ImageView grid = new ImageView(this);
    grid.setImageResource(R.drawable.luma_apps);
    allPlate.addView(grid, new FrameLayout.LayoutParams(px(36), px(36), Gravity.CENTER));
    TextView allLabel = designText("Все приложения", 11, WHITE, false);
    allLabel.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(-1, px(20));
    caption.topMargin = px(5);
    all.addView(allLabel, caption);
    all.setOnClickListener(v -> showAll());
    LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(px(tileWidth), px(98));
    appRow.addView(all, allParams);
    appScroll.addView(appRow);
    place(d, appScroll, 73, 418, 1201, 110);
    if (movingPackage != null)
      place(
          d,
          designText(
              "← → переместить   ·   ОК сохранить   ·   ↓ убрать   ·   Назад отменить",
              11,
              0xffcbd7e5,
              false),
          340,
          395,
          920,
          24);
    addDrawer(surface, true);
    wireHomeFocus(surface);
    updateVpn();
    paintHdmiHome();
    restoreTileFocus();
    renderedHomeKey = catalog.homeContentKey();
    final ScrollView currentScroll = homeScroll;
    final int offset = homeOffset;
    View cursor = getCurrentFocus();
    boolean inside = false;
    while (cursor != null) {
      if (cursor == homeContent) {
        inside = true;
        break;
      }
      android.view.ViewParent parent = cursor.getParent();
      cursor = parent instanceof View ? (View) parent : null;
    }
    if (!inside)
      currentScroll.post(
          () -> {
            if (currentScroll.isAttachedToWindow()) currentScroll.scrollTo(0, offset);
          });
  }

  private View systemSettingsTile() {
    LinearLayout tile = column();
    tile.setGravity(Gravity.CENTER);
    tile.setClipChildren(false);
    tile.setClipToPadding(false);
    tile.setTag("button:Настройки телевизора");
    tile.setContentDescription("Настройки телевизора");
    tile.setFocusable(true);
    tile.setDefaultFocusHighlightEnabled(false);
    liftOnFocus(tile);
    FrameLayout plate = new FrameLayout(this);
    plate.setDuplicateParentStateEnabled(true);
    plate.setBackground(LumaStyle.states(CARD, unit(14), designScale));
    plate.setElevation(unit(2));
    ImageView icon = icon("settings", 0xff90c7ee);
    plate.addView(icon, new FrameLayout.LayoutParams(unit(60), unit(60), Gravity.CENTER));
    tile.addView(plate, new LinearLayout.LayoutParams(-1, unit(104)));
    TextView name = label("Настройки телевизора", 12, WHITE);
    name.setGravity(Gravity.CENTER);
    name.setMaxLines(2);
    LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(-1, unit(28));
    caption.topMargin = unit(5);
    tile.addView(name, caption);
    tile.setOnClickListener(v -> openSystemSettings());
    return tile;
  }

  private void showAll() {
    beginPage("all");
    updateApps();
    setup();
    top();
    space(body, 24);
    body.addView(heading("Все приложения", 24));
    space(body, 8);
    body.addView(label("Удерживай OK, чтобы добавить приложение в быстрый запуск", 14, MUTED));
    space(body, 16);
    List<View> tiles = new ArrayList<>();
    for (ResolveInfo app : installed) {
      int flags = app.activityInfo.applicationInfo.flags;
      boolean system =
          (flags
                  & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                      | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
              != 0;
      if (!LauncherLogic.hideSettingsEntry(
          app.activityInfo.packageName, appLabels.get(app), system)) tiles.add(tile(app));
    }
    tiles.add(systemSettingsTile());
    LinearLayout line = null;
    int index = 0;
    for (View item : tiles) {
      if (index % 6 == 0) {
        line = new LinearLayout(this);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(unit(6), unit(6), unit(6), unit(6));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, unit(154));
        row.bottomMargin = unit(6);
        body.addView(line, row);
      }
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, unit(137), 1);
      p.rightMargin = unit(14);
      line.addView(item, p);
      index++;
    }
    if (line != null && index % 6 != 0)
      for (int i = index % 6; i < 6; i++) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, unit(137), 1);
        p.rightMargin = unit(14);
        line.addView(new View(this), p);
      }
    restoreTileFocus();
  }

  @Override
  public void onBackPressed() {
    if (movingPackage != null) finishMove(false, false);
    else if (drawerController != null && drawerController.contains(getCurrentFocus())) {
      drawerController.leave();
    } else if (page.equals("settings")) {
      renderPage(settingsReturnPage.equals("settings") ? "home" : settingsReturnPage);
      restoreFocus = settingsReturnFocus;
      restoreTileFocus();
    } else if (!page.equals("home")) showHome();
  }

  private ImageView icon(String kind, int color) {
    ImageView image = new ImageView(this);
    image.setImageDrawable(new LineIcon(kind, color));
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return image;
  }

  private View iconButton(String kind, String description, Runnable action, boolean active) {
    FrameLayout frame = new FrameLayout(this);
    focus(frame, active ? 0xff35363e : 0x00000000);
    frame.setTag("icon:" + description);
    frame.setContentDescription(description);
    ImageView image = icon(kind, active ? WHITE : MUTED);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER);
    frame.addView(image, params);
    frame.setOnClickListener(v -> action.run());
    if (kind.equals("settings")) aboutOnHold(frame);
    return frame;
  }

  private View heroAction(String text, Runnable action, boolean primary) {
    FrameLayout frame = new FrameLayout(this);
    focus(frame, primary ? 0xffdce0e7 : 0xaa37383e);
    frame.setTag("hero:" + (primary ? "В Lampa" : text));
    frame.setContentDescription(primary ? "Смотреть в Lampa" : text);
    frame.setOnClickListener(v -> action.run());
    TextView label = designText(text, 13, primary ? 0xff091017 : WHITE, false);
    label.setSingleLine(true);
    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
    label.setGravity(Gravity.CENTER);
    if (primary) {
      place(frame, icon("play", 0xff091017), 17, 13, 16, 16);
      place(frame, label, 37, 0, 110, 43);
    } else frame.addView(label, new FrameLayout.LayoutParams(-1, -1));
    return frame;
  }

  private void showMovieDetails() {
    if (shownFilm != null) {
      showCatalogDetails(shownFilm);
      return;
    }
    if (catalog.configured()) {
      Toast.makeText(this, "В этой подборке пока нет карточек", Toast.LENGTH_SHORT).show();
      return;
    }
    catalogSettings();
  }

  /** Scalable line icons: independent of fonts, emoji replacement and TV firmware. */
  private static final class LineIcon extends android.graphics.drawable.Drawable {
    private final String kind;
    private final android.graphics.Path gearOutline;
    private final android.graphics.Paint paint =
        new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    LineIcon(String kind, int color) {
      this.kind = kind;
      gearOutline = kind.equals("settings") ? makeGearOutline() : null;
      paint.setColor(color);
      paint.setStrokeWidth(1.7f);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
    }

    private static android.graphics.Path makeGearOutline() {
      android.graphics.Path gear = new android.graphics.Path();
      for (int tooth = 0; tooth < 8; tooth++) {
        float[] offsets = {-18, -12, 12, 18};
        for (int point = 0; point < 4; point++) {
          double angle = Math.toRadians(tooth * 45 + offsets[point] - 90);
          float radius = point == 0 || point == 3 ? 7.1f : 9.5f;
          float x = 12 + (float) Math.cos(angle) * radius,
              y = 12 + (float) Math.sin(angle) * radius;
          if (tooth == 0 && point == 0) gear.moveTo(x, y);
          else gear.lineTo(x, y);
        }
      }
      gear.close();
      return gear;
    }

    private void path(android.graphics.Canvas c, float... points) {
      android.graphics.Path p = new android.graphics.Path();
      p.moveTo(points[0], points[1]);
      for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
      c.drawPath(p, paint);
    }

    @Override
    public void draw(android.graphics.Canvas c) {
      android.graphics.Rect b = getBounds();
      int save = c.save();
      float scale = Math.min(b.width(), b.height()) / 24f;
      c.translate(b.left + (b.width() - 24 * scale) / 2, b.top + (b.height() - 24 * scale) / 2);
      c.scale(scale, scale);
      switch (kind) {
        case "home":
          path(c, 3, 10, 12, 3, 21, 10);
          path(c, 5, 9, 5, 21, 10, 21, 10, 15, 14, 15, 14, 21, 19, 21, 19, 9);
          break;
        case "film":
          c.drawRoundRect(3, 9, 21, 21, 2, 2, paint);
          path(c, 3, 9, 2, 5, 20, 2, 21, 6, 3, 9);
          path(c, 7, 4, 10, 7);
          path(c, 14, 3, 17, 6);
          break;
        case "previous":
          path(c, 15, 5, 8, 12, 15, 19);
          break;
        case "next":
          path(c, 9, 5, 16, 12, 9, 19);
          break;
        case "favorite":
          android.graphics.Path heart = new android.graphics.Path();
          heart.moveTo(12, 21);
          heart.cubicTo(8, 18, 2, 13, 2, 8);
          heart.cubicTo(2, 2, 9, 1, 12, 6);
          heart.cubicTo(15, 1, 22, 2, 22, 8);
          heart.cubicTo(22, 13, 16, 18, 12, 21);
          heart.close();
          c.drawPath(heart, paint);
          break;
        case "vpn":
          path(c, 12, 2, 21, 6, 20, 15, 17, 19, 12, 23, 7, 19, 4, 15, 3, 6, 12, 2);
          path(c, 8, 12, 11, 15, 17, 9);
          break;
        case "series":
          c.drawRoundRect(3, 7, 21, 21, 2, 2, paint);
          path(c, 6, 3, 18, 3);
          path(c, 4, 5, 20, 5);
          path(c, 10, 11, 15, 14, 10, 17, 10, 11);
          break;
        case "devices":
          c.drawRoundRect(5, 2, 19, 18, 2, 2, paint);
          path(c, 9, 22, 15, 22);
          path(c, 12, 18, 12, 22);
          path(c, 8, 15, 16, 15);
          break;
        case "sun":
          c.drawCircle(12, 12, 4, paint);
          for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            c.drawLine(
                12 + (float) Math.cos(a) * 7,
                12 + (float) Math.sin(a) * 7,
                12 + (float) Math.cos(a) * 10,
                12 + (float) Math.sin(a) * 10,
                paint);
          }
          break;
        case "cloud":
        case "rain":
        case "snow":
        case "storm":
        case "partly":
        case "fog":
          if (kind.equals("partly")) {
            c.drawCircle(16, 6, 4, paint);
            path(c, 16, 0, 16, 1);
            path(c, 22, 6, 23, 6);
          }
          android.graphics.Path cloud = new android.graphics.Path();
          cloud.moveTo(6, 17);
          cloud.cubicTo(0, 17, 0, 10, 5, 10);
          cloud.cubicTo(4, 2, 16, 2, 17, 10);
          cloud.cubicTo(24, 9, 25, 17, 18, 17);
          cloud.close();
          c.drawPath(cloud, paint);
          if (kind.equals("rain")) {
            path(c, 7, 20, 6, 23);
            path(c, 13, 20, 12, 23);
            path(c, 19, 20, 18, 23);
          }
          if (kind.equals("snow")) {
            c.drawCircle(7, 21, .7f, paint);
            c.drawCircle(13, 22, .7f, paint);
            c.drawCircle(19, 21, .7f, paint);
          }
          if (kind.equals("storm")) path(c, 13, 17, 10, 21, 14, 21, 11, 24);
          if (kind.equals("fog")) {
            path(c, 3, 20, 21, 20);
            path(c, 6, 23, 18, 23);
          }
          break;
        case "weather":
          c.drawCircle(12, 12, 8, paint);
          path(c, 9, 9, 10, 7, 14, 7, 16, 9, 15, 11, 12, 13, 12, 15);
          c.drawCircle(12, 18, .5f, paint);
          break;
        case "monitor":
          c.drawRoundRect(2.5f, 3, 21.5f, 17, 2, 2, paint);
          path(c, 12, 17, 12, 21);
          path(c, 8, 21, 16, 21);
          break;
        case "apps":
          for (int y = 3; y <= 14; y += 11)
            for (int x = 3; x <= 14; x += 11) c.drawRoundRect(x, y, x + 7, y + 7, 1, 1, paint);
          break;
        case "settings":
          c.drawPath(gearOutline, paint);
          c.drawCircle(12, 12, 3.0f, paint);
          break;
        case "sliders":
          path(c, 3, 7, 7, 7);
          path(c, 11, 7, 21, 7);
          c.drawCircle(9, 7, 2, paint);
          path(c, 3, 17, 13, 17);
          path(c, 17, 17, 21, 17);
          c.drawCircle(15, 17, 2, paint);
          break;
        case "search":
          c.drawCircle(10, 10, 6.5f, paint);
          path(c, 15, 15, 21, 21);
          break;
        case "wifi":
          c.drawArc(-2.5f, 5.5f, 26.5f, 34.5f, 225, 90, false, paint);
          c.drawArc(3, 11, 21, 29, 225, 90, false, paint);
          c.drawArc(7.5f, 15.5f, 16.5f, 24.5f, 225, 90, false, paint);
          paint.setStyle(android.graphics.Paint.Style.FILL);
          c.drawCircle(12, 20.5f, 1.1f, paint);
          paint.setStyle(android.graphics.Paint.Style.STROKE);
          break;
        case "play":
          paint.setStyle(android.graphics.Paint.Style.FILL);
          android.graphics.Path play = new android.graphics.Path();
          play.moveTo(7, 3);
          play.lineTo(21, 12);
          play.lineTo(7, 21);
          play.close();
          c.drawPath(play, paint);
          paint.setStyle(android.graphics.Paint.Style.STROKE);
          break;
      }
      c.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
      invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {
      paint.setColorFilter(filter);
      invalidateSelf();
    }

    @Override
    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
      return 24;
    }

    @Override
    public int getIntrinsicHeight() {
      return 24;
    }
  }

  private int browserCredentialVersion = -1;
  private final BrowseState movieBrowser = new BrowseState(), tvBrowser = new BrowseState();

  private static final class BrowseState {
    int genre = 0, year = 0, rating = 0, sort = 0, page = 1, pages = 1, request = 0;
    boolean loading = false, loaded = false, error = false, viewportReady = false;
    int scrollY = 0;
    String focusTag = "", viewQuery = "";
    List<CatalogManager.Film> rows = Collections.emptyList();
    LinkedHashMap<Integer, String> genres = new LinkedHashMap<>();
    String status = "";
  }

  private String browseQuery(String type, BrowseState state) {
    return BrowseLogic.query(type, state.genre, state.year, state.rating, state.sort, state.page);
  }

  private void rememberBrowsePosition() {
    if (!page.equals("movie") && !page.equals("tv")) return;
    BrowseState state = browser(page);
    if (!state.viewportReady
        || pageScroll == null
        || !pageScroll.isAttachedToWindow()
        || !state.viewQuery.equals(browseQuery(page, state))) return;
    state.scrollY = pageScroll.getScrollY();
    View focus = getCurrentFocus();
    String tag = focus == null ? "" : String.valueOf(focus.getTag());
    if (tag.startsWith("catalog:") || tag.startsWith("filter:")) state.focusTag = tag;
    saveBrowser(page, state);
  }

  private void saveBrowser(String type, BrowseState state) {
    CatalogPosition p = new CatalogPosition();
    p.genre = state.genre;
    p.year = state.year;
    p.rating = state.rating;
    p.sort = state.sort;
    p.page = state.page;
    p.scroll = state.scrollY;
    p.focus = state.focusTag;
    getSharedPreferences("catalog_position", MODE_PRIVATE)
        .edit()
        .putString(type, p.encode())
        .apply();
  }

  private void restoreBrowser(String type) {
    CatalogPosition p =
        CatalogPosition.decode(
            getSharedPreferences("catalog_position", MODE_PRIVATE).getString(type, "{}"));
    BrowseState state = browser(type);
    state.genre = p.genre;
    state.year = p.year;
    state.rating = p.rating;
    state.sort = p.sort;
    state.page = p.page;
    state.scrollY = p.scroll;
    state.focusTag = p.focus;
    state.viewQuery = browseQuery(type, state);
  }

  private void showBrowse(String type, boolean reload) {
    rememberBrowsePosition();
    if (browserCredentialVersion != catalog.version()) {
      browserCredentialVersion = catalog.version();
      for (BrowseState old : new BrowseState[] {movieBrowser, tvBrowser}) {
        old.request++;
        old.loading = false;
        old.loaded = false;
        old.rows = Collections.emptyList();
        old.status = "";
        old.error = false;
      }
    }
    BrowseState state = browser(type);
    String query = browseQuery(type, state);
    boolean changed = !query.equals(state.viewQuery);
    if (changed) {
      state.scrollY = 0;
      state.focusTag = "filter:genre";
      state.rows = Collections.emptyList();
      state.loaded = false;
      state.loading = false;
      state.error = false;
    }
    boolean startLoad = catalog.configured() && (reload || (!state.loaded && !state.loading));
    if (startLoad) state.loading = true;
    beginPage(type);
    setup();
    top();
    state.viewQuery = query;
    List<List<View>> navigation = new ArrayList<>();
    space(body, 16);
    body.addView(heading(type.equals("tv") ? "Сериалы" : "Фильмы", 26));
    space(body, 12);
    LinearLayout filters = new LinearLayout(this);
    filters.setClipChildren(false);
    String genreName =
        state.genres.containsKey(state.genre)
            ? state.genres.get(state.genre)
            : state.genre == 0 ? "Все жанры" : "Выбранный жанр";
    addFilter(filters, "Жанр: " + genreName, () -> chooseGenre(type), "genre");
    addFilter(
        filters,
        "Год: " + (state.year == 0 ? "любой" : state.year),
        () -> chooseYear(type),
        "year");
    addFilter(
        filters,
        "Рейтинг: " + (state.rating == 0 ? "любой" : state.rating + "+"),
        () -> chooseRating(type),
        "rating");
    body.addView(filters);
    navigation.add(focusableChildren(filters));
    space(body, 10);
    LinearLayout sorting = new LinearLayout(this);
    sorting.setClipChildren(false);
    addFilter(
        sorting,
        "Сортировка: " + new String[] {"Популярность", "Рейтинг", "Новые"}[state.sort],
        () -> chooseSort(type),
        "sort");
    addFilter(
        sorting,
        "Сбросить",
        () -> {
          state.genre = state.year = state.rating = state.sort = 0;
          state.page = 1;
          showBrowse(type, true);
        },
        "reset");
    addFilter(sorting, "Обновить", () -> showBrowse(type, true), "retry");
    body.addView(sorting);
    navigation.add(focusableChildren(sorting));
    space(body, 14);
    if (!catalog.configured()) {
      body.addView(label("Подключи TMDB в настройках каталога на главной", 16, MUTED));
      View settings = button("Настройки каталога", this::catalogSettings);
      body.addView(settings);
      navigation.add(Collections.singletonList(settings));
    } else {
      body.addView(
          label(
              state.loading
                  ? (state.rows.isEmpty() ? "Загрузка…" : "Обновляем · карточки сохранены")
                  : state.status,
              14,
              MUTED));
      space(body, 10);
      if (state.error && !state.loading) {
        LinearLayout error = column();
        error.setPadding(unit(16), unit(12), unit(16), unit(12));
        error.setBackground(bg(CARD, 0));
        error.addView(
            label(
                state.rows.isEmpty()
                    ? "Не удалось загрузить страницу"
                    : "Не удалось обновить страницу",
                18,
                WHITE));
        TextView reason = label(state.status, 14, MUTED);
        reason.setPadding(0, unit(8), 0, unit(10));
        error.addView(reason);
        View retry =
            button(
                "Повторить",
                () -> {
                  state.focusTag = "filter:retry";
                  showBrowse(type, true);
                });
        retry.setTag("filter:retry-error");
        error.addView(retry);
        body.addView(error);
        navigation.add(Collections.singletonList(retry));
        space(body, 12);
      } else if (state.rows.isEmpty() && !state.loading && state.loaded)
        body.addView(label("По этим фильтрам ничего не найдено", 16, MUTED));
      LinearLayout row = null;
      List<View> controls = null;
      int index = 0;
      for (CatalogManager.Film film : state.rows) {
        if (index % 4 == 0) {
          row = new LinearLayout(this);
          row.setPadding(unit(6), unit(6), unit(6), unit(6));
          row.setClipChildren(false);
          body.addView(row, new LinearLayout.LayoutParams(-1, unit(178)));
          controls = new ArrayList<>();
          navigation.add(controls);
        }
        View card = browseCard(film);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, unit(163), 1);
        lp.rightMargin = unit(14);
        row.addView(card, lp);
        controls.add(card);
        index++;
      }
      if (row != null && index % 4 != 0)
        for (int i = index % 4; i < 4; i++) {
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, unit(163), 1);
          lp.rightMargin = unit(14);
          row.addView(new View(this), lp);
        }
      LinearLayout paging = new LinearLayout(this);
      paging.setClipChildren(false);
      if (state.page > 1)
        addFilter(
            paging,
            "← Предыдущая",
            () -> {
              state.page--;
              showBrowse(type, true);
            },
            "previous");
      TextView number =
          label(
              "Страница " + state.page + (state.loading ? " · загрузка" : " / " + state.pages),
              15,
              MUTED);
      number.setGravity(Gravity.CENTER);
      paging.addView(number, new LinearLayout.LayoutParams(0, unit(48), 1));
      if (state.page < state.pages)
        addFilter(
            paging,
            "Следующая →",
            () -> {
              state.page++;
              showBrowse(type, true);
            },
            "next");
      body.addView(paging);
      if (!focusableChildren(paging).isEmpty()) navigation.add(focusableChildren(paging));
    }
    wireBrowseRows(type, navigation);
    String tag = state.focusTag;
    if (state.loading && tag.equals("filter:retry-error")) tag = "filter:retry";
    if (getWindow().getDecorView().findViewWithTag(tag) == null) tag = "filter:genre";
    restoreFocus = tag;
    restoreTileFocus();
    final ScrollView scroll = pageScroll;
    final int offset = state.scrollY;
    scroll.post(
        () -> {
          if (scroll.isAttachedToWindow() && page.equals(type) && state.viewQuery.equals(query)) {
            scroll.scrollTo(0, offset);
            View selected = getCurrentFocus();
            if (selected != null && String.valueOf(selected.getTag()).startsWith("catalog:"))
              selected.requestRectangleOnScreen(
                  new android.graphics.Rect(0, 0, selected.getWidth(), selected.getHeight()), true);
          }
        });
    state.viewportReady = !state.rows.isEmpty() || !state.loading;
    saveBrowser(type, state);
    if (startLoad) loadBrowse(type, state);
  }

  private List<View> focusableChildren(android.view.ViewGroup group) {
    List<View> list = new ArrayList<>();
    for (int i = 0; i < group.getChildCount(); i++)
      if (group.getChildAt(i).isFocusable()) list.add(group.getChildAt(i));
    return list;
  }

  private void wireBrowseRows(String type, List<List<View>> rows) {
    View root = getWindow().getDecorView(),
        side = root.findViewWithTag("drawer:" + (type.equals("tv") ? "series" : "film")),
        tab = root.findViewWithTag(type.equals("tv") ? "button:Сериалы" : "button:Фильмы");
    for (int r = 0; r < rows.size(); r++)
      for (int c = 0; c < rows.get(r).size(); c++) {
        List<View> row = rows.get(r);
        View item = row.get(c);
        link(item, c == 0 ? side : row.get(c - 1), KeyEvent.KEYCODE_DPAD_LEFT);
        link(item, row.get(Math.min(c + 1, row.size() - 1)), KeyEvent.KEYCODE_DPAD_RIGHT);
        link(
            item,
            r == 0
                ? tab
                : rows.get(r - 1).get(LauncherLogic.nearestColumn(c, rows.get(r - 1).size())),
            KeyEvent.KEYCODE_DPAD_UP);
        link(
            item,
            r + 1 == rows.size()
                ? item
                : rows.get(r + 1).get(LauncherLogic.nearestColumn(c, rows.get(r + 1).size())),
            KeyEvent.KEYCODE_DPAD_DOWN);
      }
    if (!rows.isEmpty() && !rows.get(0).isEmpty())
      link(tab, rows.get(0).get(0), KeyEvent.KEYCODE_DPAD_DOWN);
  }

  private void loadBrowse(String type, BrowseState state) {
    state.loading = true;
    final int request = ++state.request;
    catalog.browse(
        type,
        state.genre,
        state.year,
        state.rating,
        state.sort,
        state.page,
        (result, message) -> {
          if (isDestroyed() || request != state.request) return;
          state.loading = false;
          state.error = message != null;
          if (result != null) {
            if (message == null && state.page > result.pages) {
              state.page = result.pages;
              showBrowse(type, true);
              return;
            }
            state.loaded = true;
            state.rows = result.films;
            state.pages = result.pages;
            state.genres = result.genres;
          } else {
            state.loaded = true;
          }
          state.status =
              message != null
                  ? message
                  : "TMDB · " + state.rows.size() + " результатов на странице";
          if (resumed && page.equals(type)) showBrowse(type, false);
        });
  }

  private void addFilter(LinearLayout row, String title, Runnable action, String tag) {
    TextView control = button(title, action);
    control.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(13));
    control.setSingleLine(true);
    control.setMaxWidth(unit(300));
    control.setEllipsize(android.text.TextUtils.TruncateAt.END);
    if (tag.equals("reset")
        || tag.equals("retry")
        || tag.equals("search-clear")
        || tag.equals("search-retry")) {
      focus(control, 0x00000000);
      control.setTextColor(MUTED);
    }
    control.setCompoundDrawablePadding(unit(8));
    if (!tag.equals("reset") && !tag.equals("retry") && !tag.startsWith("search-")) {
      control.setText(title + "  ⌄");
      focus(control, 0xff292a31);
    }
    control.setTag("filter:" + tag);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, unit(42));
    lp.rightMargin = unit(10);
    row.addView(control, lp);
  }

  private View browseCard(CatalogManager.Film film) {
    LinearLayout card = column();
    card.setPadding(unit(4), unit(4), unit(4), unit(4));
    card.setClipChildren(false);
    card.setClipToPadding(false);
    focus(card, 0x00000000);
    card.setTag("catalog:" + film.type + ":" + film.id);
    FrameLayout picture = new FrameLayout(this);
    picture.setBackground(bg(0xff111b24, 0));
    picture.setClipToOutline(true);
    ImageView placeholder = icon(film.type.equals("tv") ? "series" : "film", 0xff53616e);
    picture.addView(placeholder, new FrameLayout.LayoutParams(unit(28), unit(28), Gravity.CENTER));
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    picture.addView(image, new FrameLayout.LayoutParams(-1, -1));
    ImageView favorite = icon("favorite", 0xffe0e9f0);
    favorite.setPadding(unit(5), unit(5), unit(5), unit(5));
    favorite.setBackground(bg(0xcc071018, 0));
    favorite.setTag("badge:" + film.type + ":" + film.id);
    favorite.setVisibility(
        favoriteBadges.contains(film.type + ":" + film.id) ? View.VISIBLE : View.GONE);
    FrameLayout.LayoutParams badge =
        new FrameLayout.LayoutParams(unit(26), unit(26), Gravity.TOP | Gravity.RIGHT);
    badge.topMargin = unit(7);
    badge.rightMargin = unit(7);
    picture.addView(favorite, badge);
    card.addView(picture, new LinearLayout.LayoutParams(-1, unit(108)));
    catalog.image(image, film.image);
    TextView title = label(film.title, 14, WHITE);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, unit(24));
    titleParams.topMargin = unit(6);
    card.addView(title, titleParams);
    TextView metadata =
        label(film.year + " · " + (film.type.equals("tv") ? "Сериал" : "Фильм"), 12, MUTED);
    metadata.setSingleLine(true);
    metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(metadata, new LinearLayout.LayoutParams(-1, unit(17)));
    card.setContentDescription(
        film.title + ", " + film.year + ". OK — открыть, удерживать — описание");
    card.setOnClickListener(v -> openLampaCard(film.id, film.type));
    card.setOnLongClickListener(
        v -> {
          showCatalogDetails(film);
          return true;
        });
    return card;
  }

  private BrowseState browser(String type) {
    return type.equals("tv") ? tvBrowser : movieBrowser;
  }

  private void chooseGenre(String type) {
    BrowseState s = browser(type);
    if (s.genres.size() < 2) {
      Toast.makeText(
              this, "Список жанров ещё не загружен. Повтори загрузку каталога.", Toast.LENGTH_LONG)
          .show();
      return;
    }
    ArrayList<Integer> ids = new ArrayList<>(s.genres.keySet());
    String[] names = s.genres.values().toArray(new String[0]);
    new android.app.AlertDialog.Builder(this)
        .setTitle("Жанр")
        .setSingleChoiceItems(
            names,
            ids.indexOf(s.genre),
            (d, i) -> {
              s.genre = ids.get(i);
              s.page = 1;
              d.dismiss();
              showBrowse(type, true);
            })
        .setNegativeButton("Отмена", null)
        .show();
  }

  private void chooseYear(String type) {
    BrowseState s = browser(type);
    int now = Calendar.getInstance().get(Calendar.YEAR);
    String[] years = new String[now - 1900 + 2];
    years[0] = "Любой год";
    for (int i = 1; i < years.length; i++) years[i] = Integer.toString(now - i + 1);
    new android.app.AlertDialog.Builder(this)
        .setTitle(type.equals("tv") ? "Год начала сериала" : "Год фильма")
        .setSingleChoiceItems(
            years,
            s.year == 0 ? 0 : now - s.year + 1,
            (d, i) -> {
              s.year = i == 0 ? 0 : now - i + 1;
              s.page = 1;
              d.dismiss();
              showBrowse(type, true);
            })
        .setNegativeButton("Отмена", null)
        .show();
  }

  private void chooseRating(String type) {
    BrowseState s = browser(type);
    String[] items = {"Любой", "6+", "7+", "8+", "9+"};
    int[] values = {0, 6, 7, 8, 9};
    new android.app.AlertDialog.Builder(this)
        .setTitle("Минимальный рейтинг TMDB")
        .setItems(
            items,
            (d, i) -> {
              s.rating = values[i];
              s.page = 1;
              showBrowse(type, true);
            })
        .show();
  }

  private void chooseSort(String type) {
    BrowseState s = browser(type);
    new android.app.AlertDialog.Builder(this)
        .setTitle("Сортировка")
        .setSingleChoiceItems(
            new String[] {
              "По популярности", "По рейтингу (от 100 оценок)", "Сначала новые, уже вышедшие"
            },
            s.sort,
            (d, i) -> {
              s.sort = i;
              s.page = 1;
              d.dismiss();
              showBrowse(type, true);
            })
        .show();
  }

  private void addDrawer(FrameLayout root, boolean design) {
    if (drawerController != null) drawerController.dispose();
    drawerController = new DrawerController(root, design);
  }

  private final class DrawerController {
    private final FrameLayout root, drawer;
    private final View scrim;
    private final List<View> buttons = new ArrayList<>();
    private final List<TextView> captions = new ArrayList<>();
    private final TextView title;
    private final android.graphics.drawable.GradientDrawable panel =
        LumaStyle.surface(0xf224262d, 0, designScale);
    private final android.view.ViewTreeObserver.OnGlobalFocusChangeListener focusListener;
    private final boolean design;
    private java.lang.ref.WeakReference<View> returnFocus = new java.lang.ref.WeakReference<>(null);
    private android.animation.ValueAnimator animator;
    private float reveal = 0;
    private boolean expanded = false, disposed = false;

    DrawerController(FrameLayout root, boolean design) {
      this.root = root;
      this.design = design;
      scrim = new View(MainActivity.this);
      scrim.setBackgroundColor(0xff000000);
      scrim.setFocusable(false);
      scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      scrim.setOnClickListener(v -> leave());
      FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -1);
      root.addView(scrim, sp);
      drawer =
          new FrameLayout(MainActivity.this) {
            @Override
            public void addFocusables(ArrayList<View> views, int direction, int mode) {
              // Entry from content is explicit (left edge only). Never let the tall
              // rail win Android's geometric search for up/down/right transitions.
              if (contains(getCurrentFocus())) super.addFocusables(views, direction, mode);
            }
          };
      panel.setCornerRadius(0);
      drawer.setClipChildren(true);
      drawer.setClipToPadding(true);
      drawer.setBackground(panel);
      FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(unit(65), -1);
      root.addView(drawer, dp);
      title = label("Luma", 19, WHITE);
      title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      title.setLetterSpacing(.06f);
      title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(unit(150), unit(28));
      tp.leftMargin = unit(67);
      tp.topMargin = unit(46);
      drawer.addView(title, tp);
      String[] kinds = {
        "home", "film", "series", "search", "favorite", "apps", "devices", "settings"
      };
      String[] labels = {
        "Главная",
        "Фильмы",
        "Сериалы",
        "Поиск",
        "Избранное",
        "Приложения",
        "Устройства",
        "Настройки"
      };
      Runnable[] actions = {
        MainActivity.this::showHome,
        () -> showBrowse("movie", false),
        () -> showBrowse("tv", false),
        () -> showSearch(false),
        MainActivity.this::showFilmFavorites,
        MainActivity.this::showAll,
        MainActivity.this::showDevices,
        MainActivity.this::openSettings
      };
      for (int i = 0; i < kinds.length; i++) {
        FrameLayout item = new FrameLayout(MainActivity.this);
        item.setId(View.generateViewId());
        item.setTag("drawer:" + kinds[i]);
        final String kind = kinds[i];
        final boolean active = kind.equals(LauncherLogic.drawerKind(page));
        item.setSelected(active);
        item.setContentDescription(labels[i] + (active ? ". Текущий раздел" : ""));
        item.setFocusable(true);
        item.setDefaultFocusHighlightEnabled(false);
        item.setClipChildren(true);
        item.setBackground(LumaStyle.states(active ? 0xff32343b : 0, unit(14), designScale));
        final ImageView image = icon(kinds[i], active ? WHITE : MUTED);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(unit(22), unit(22));
        ip.leftMargin = unit(11);
        ip.topMargin = unit(13);
        item.addView(image, ip);
        TextView caption = label(labels[i], 15, active ? WHITE : MUTED);
        caption.setSingleLine(true);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (active) caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(unit(156), -1);
        cp.leftMargin = unit(57);
        item.addView(caption, cp);
        if (active) {
          View marker = new View(MainActivity.this);
          marker.setBackground(bg(0xffd6d7de, 0));
          FrameLayout.LayoutParams mp =
              new FrameLayout.LayoutParams(unit(3), unit(20), Gravity.CENTER_VERTICAL);
          item.addView(marker, mp);
        }
        item.setOnFocusChangeListener(
            (view, focused) -> {
              // StateListDrawable retains the translucent material without allocations on focus.
              image.setImageDrawable(new LineIcon(kind, focused || active ? WHITE : MUTED));
              caption.setTextColor(focused || active ? WHITE : MUTED);
            });
        item.setOnHoverListener(
            (view, event) -> {
              if (event.getAction() == android.view.MotionEvent.ACTION_HOVER_ENTER)
                view.requestFocus();
              return false;
            });
        final Runnable action = actions[i];
        item.setOnClickListener(v -> action.run());
        if (kind.equals("settings")) aboutOnHold(item);
        item.setOnKeyListener(
            (view, key, event) -> {
              if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) leave();
                return true;
              }
              if (key == KeyEvent.KEYCODE_DPAD_LEFT) return true;
              return false;
            });
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(unit(44), unit(48));
        bp.leftMargin = unit(10);
        bp.topMargin = unit(22 + i * 48);
        drawer.addView(item, bp);
        buttons.add(item);
        captions.add(caption);
      }
      for (int i = 0; i < buttons.size(); i++) {
        View item = buttons.get(i);
        item.setNextFocusUpId(buttons.get(Math.max(0, i - 1)).getId());
        item.setNextFocusDownId(buttons.get(Math.min(buttons.size() - 1, i + 1)).getId());
        item.setNextFocusLeftId(item.getId());
      }
      focusListener =
          (oldFocus, newFocus) -> {
            if (disposed) return;
            boolean inside = contains(newFocus);
            if (inside && !contains(oldFocus) && oldFocus != null)
              returnFocus = new java.lang.ref.WeakReference<>(oldFocus);
            expand(inside);
          };
      root.getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
      drawer.addOnAttachStateChangeListener(
          new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {
              expand(contains(getCurrentFocus()));
            }

            public void onViewDetachedFromWindow(View v) {
              dispose();
            }
          });
      applyReveal(0);
      expand(contains(getCurrentFocus()));
    }

    boolean contains(View view) {
      return view != null && (view == drawer || buttons.contains(view));
    }

    void leave() {
      View target = returnFocus.get();
      if (target == null
          || !target.isAttachedToWindow()
          || !target.isShown()
          || !target.isFocusable()
          || contains(target))
        target =
            root.findViewWithTag(design ? "hero:В Lampa" : LauncherLogic.drawerContentTag(page));
      if (target == null) target = root.findViewWithTag("button:Главная");
      if (target != null) target.requestFocus();
      if (!contains(getCurrentFocus())) expand(false);
    }

    private void expand(boolean open) {
      if (disposed || expanded == open) return;
      expanded = open;
      if (animator != null) animator.cancel();
      if (open) {
        scrim.setVisibility(View.VISIBLE);
        drawer.bringToFront();
      }
      animator = android.animation.ValueAnimator.ofFloat(reveal, open ? 1 : 0);
      animator.setDuration(
          motionEnabled() ? Math.max(1, Math.round(180 * Math.abs((open ? 1 : 0) - reveal))) : 0);
      animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
      animator.addUpdateListener(
          value -> {
            if (!disposed) applyReveal((float) value.getAnimatedValue());
          });
      animator.start();
    }

    private void applyReveal(float fraction) {
      reveal = fraction;
      android.view.ViewGroup.LayoutParams dp = drawer.getLayoutParams();
      dp.width = unit(Math.round(65 + 175 * fraction));
      dp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
      drawer.setLayoutParams(dp);
      panel.setAlpha(Math.round(255 * fraction));
      drawer.setElevation(unit(12) * fraction);
      scrim.setAlpha(.18f * fraction);
      if (fraction == 0 && !expanded) scrim.setVisibility(View.GONE);
      title.setAlpha(fraction);
      for (TextView caption : captions) caption.setAlpha(Math.max(0, (fraction - .15f) / .85f));
      for (int i = 0; i < buttons.size(); i++) {
        View button = buttons.get(i);
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) button.getLayoutParams();
        bp.width = unit(Math.round(44 + 176 * fraction));
        bp.topMargin = unit(100 + i * 48);
        button.setLayoutParams(bp);
      }
    }

    void dispose() {
      if (disposed) return;
      disposed = true;
      if (animator != null) {
        animator.cancel();
        animator.removeAllUpdateListeners();
      }
      if (root.getViewTreeObserver().isAlive())
        root.getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
      returnFocus.clear();
    }
  }

  private void wireHomeFocus(FrameLayout root) {
    View primary = root.findViewWithTag("hero:В Lampa"),
        details = root.findViewWithTag("hero:Подробнее"),
        left = root.findViewWithTag("drawer:home");
    List<View> controls = new ArrayList<>();
    collectNavigationTargets(sharedHeader, controls);
    for (int i = 0; i < controls.size(); i++) {
      View item = controls.get(i);
      link(item, i == 0 ? left : controls.get(i - 1), KeyEvent.KEYCODE_DPAD_LEFT);
      link(item, controls.get(Math.min(i + 1, controls.size() - 1)), KeyEvent.KEYCODE_DPAD_RIGHT);
      link(item, item, KeyEvent.KEYCODE_DPAD_UP);
      link(item, primary, KeyEvent.KEYCODE_DPAD_DOWN);
    }
    String[] top = {"Главная", "Фильмы", "Сериалы", "Приложения", "Устройства", "Настройки"};
    for (String name : top)
      link(root.findViewWithTag("button:" + name), primary, KeyEvent.KEYCODE_DPAD_DOWN);
    link(primary, details, KeyEvent.KEYCODE_DPAD_RIGHT);
    link(primary, left, KeyEvent.KEYCODE_DPAD_LEFT);
    link(details, primary, KeyEvent.KEYCODE_DPAD_LEFT);
    View previous = root.findViewWithTag("banner:previous"),
        next = root.findViewWithTag("banner:next");
    link(details, previous == null ? details : previous, KeyEvent.KEYCODE_DPAD_RIGHT);
    link(previous, details, KeyEvent.KEYCODE_DPAD_LEFT);
    link(previous, next, KeyEvent.KEYCODE_DPAD_RIGHT);
    link(next, previous, KeyEvent.KEYCODE_DPAD_LEFT);
    link(next, next, KeyEvent.KEYCODE_DPAD_RIGHT);
    link(primary, root.findViewWithTag("button:Главная"), KeyEvent.KEYCODE_DPAD_UP);
    link(details, root.findViewWithTag("button:Фильмы"), KeyEvent.KEYCODE_DPAD_UP);
    List<List<View>> rows = new ArrayList<>();
    View appContainer = root.findViewWithTag("home:apps-row");
    if (appContainer instanceof LinearLayout) {
      List<View> row = new ArrayList<>();
      LinearLayout appsRow = (LinearLayout) appContainer;
      for (int i = 0; i < appsRow.getChildCount(); i++)
        if (appsRow.getChildAt(i).isFocusable()) row.add(appsRow.getChildAt(i));
      if (!row.isEmpty()) rows.add(row);
    }
    for (String source : catalog.homeSources()) {
      List<View> row = new ArrayList<>();
      for (CatalogManager.Film film : catalog.feed(source)) {
        View card = root.findViewWithTag("home:" + source + ":" + film.type + ":" + film.id);
        if (card != null) row.add(card);
      }
      if (!row.isEmpty()) rows.add(row);
    }
    View first =
        rows.isEmpty() ? root.findViewWithTag("button:Все приложения") : rows.get(0).get(0);
    for (int r = 0; r < rows.size(); r++) {
      List<View> row = rows.get(r);
      for (int c = 0; c < row.size(); c++) {
        View card = row.get(c);
        link(card, c == 0 ? left : row.get(c - 1), KeyEvent.KEYCODE_DPAD_LEFT);
        link(card, row.get(Math.min(c + 1, row.size() - 1)), KeyEvent.KEYCODE_DPAD_RIGHT);
        link(
            card,
            r == 0
                ? primary
                : rows.get(r - 1).get(LauncherLogic.nearestColumn(c, rows.get(r - 1).size())),
            KeyEvent.KEYCODE_DPAD_UP);
        link(
            card,
            r + 1 == rows.size()
                ? card
                : rows.get(r + 1).get(LauncherLogic.nearestColumn(c, rows.get(r + 1).size())),
            KeyEvent.KEYCODE_DPAD_DOWN);
      }
    }
    View feedSettings = root.findViewWithTag("home:settings");
    View lastApp = rows.isEmpty() ? first : rows.get(0).get(rows.get(0).size() - 1);
    View firstFilm = rows.size() > 1 ? rows.get(1).get(0) : lastApp;
    link(lastApp, feedSettings, KeyEvent.KEYCODE_DPAD_DOWN);
    link(feedSettings, lastApp, KeyEvent.KEYCODE_DPAD_UP);
    link(feedSettings, firstFilm, KeyEvent.KEYCODE_DPAD_DOWN);
    link(feedSettings, firstFilm, KeyEvent.KEYCODE_DPAD_LEFT);
    link(feedSettings, feedSettings, KeyEvent.KEYCODE_DPAD_RIGHT);
    if (rows.size() > 1) {
      List<View> filmRow = rows.get(1);
      link(filmRow.get(filmRow.size() - 1), feedSettings, KeyEvent.KEYCODE_DPAD_UP);
    }
    link(previous, root.findViewWithTag("button:Фильмы"), KeyEvent.KEYCODE_DPAD_UP);
    link(next, root.findViewWithTag("button:Приложения"), KeyEvent.KEYCODE_DPAD_UP);
    link(previous, first, KeyEvent.KEYCODE_DPAD_DOWN);
    link(next, first, KeyEvent.KEYCODE_DPAD_DOWN);
    link(primary, first, KeyEvent.KEYCODE_DPAD_DOWN);
    link(details, first, KeyEvent.KEYCODE_DPAD_DOWN);
    for (String name :
        new String[] {
          "home", "film", "series", "search", "favorite", "apps", "devices", "settings"
        }) link(root.findViewWithTag("drawer:" + name), primary, KeyEvent.KEYCODE_DPAD_RIGHT);
  }

  /** Explicit row navigation on every secondary page, independent of drawer geometry. */
  private void wirePageFocus() {
    if (body == null || pageHeader == null) return;
    View root = getWindow().getDecorView();
    View side = root.findViewWithTag("drawer:" + LauncherLogic.drawerKind(page));
    View selectedTab = root.findViewWithTag(LauncherLogic.drawerContentTag(page));
    List<List<View>> rows = new ArrayList<>();
    List<View> header = new ArrayList<>();
    collectNavigationTargets(sharedHeader, header);
    if (!header.isEmpty()) rows.add(header);
    appendNavigationRows(pageHeader, rows);
    int headerRows = rows.size();
    appendNavigationRows(body, rows);
    for (int r = 0; r < rows.size(); r++) {
      List<View> row = rows.get(r);
      for (int c = 0; c < row.size(); c++) {
        View item = row.get(c);
        link(item, c == 0 ? side : row.get(c - 1), KeyEvent.KEYCODE_DPAD_LEFT);
        link(item, row.get(Math.min(c + 1, row.size() - 1)), KeyEvent.KEYCODE_DPAD_RIGHT);
        View up =
            r == 0
                ? item
                : r == headerRows && headerRows == 1 && selectedTab != null
                    ? selectedTab
                    : rows.get(r - 1).get(LauncherLogic.nearestColumn(c, rows.get(r - 1).size()));
        link(item, up, KeyEvent.KEYCODE_DPAD_UP);
        link(
            item,
            r + 1 == rows.size()
                ? item
                : rows.get(r + 1)
                    .get(r == 0 ? 0 : LauncherLogic.nearestColumn(c, rows.get(r + 1).size())),
            KeyEvent.KEYCODE_DPAD_DOWN);
      }
    }
  }

  private void appendNavigationRows(LinearLayout container, List<List<View>> rows) {
    for (int i = 0; i < container.getChildCount(); i++) {
      List<View> row = new ArrayList<>();
      collectNavigationTargets(container.getChildAt(i), row);
      if (!row.isEmpty()) rows.add(row);
    }
  }

  private void collectNavigationTargets(View view, List<View> targets) {
    if (view.getVisibility() != View.VISIBLE || !view.isEnabled()) return;
    if (view.isFocusable() && view.getTag() instanceof String) {
      targets.add(view);
      return;
    }
    if (view instanceof android.view.ViewGroup) {
      android.view.ViewGroup group = (android.view.ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        collectNavigationTargets(group.getChildAt(i), targets);
    }
  }

  private void link(View from, View to, int direction) {
    if (from == null || to == null) return;
    if (to.getId() == View.NO_ID) to.setId(View.generateViewId());
    if (direction == KeyEvent.KEYCODE_DPAD_LEFT) from.setNextFocusLeftId(to.getId());
    if (direction == KeyEvent.KEYCODE_DPAD_RIGHT) from.setNextFocusRightId(to.getId());
    if (direction == KeyEvent.KEYCODE_DPAD_UP) from.setNextFocusUpId(to.getId());
    if (direction == KeyEvent.KEYCODE_DPAD_DOWN) from.setNextFocusDownId(to.getId());
  }

  private static final String AWG_PERMISSION = "org.amnezia.awg.permission.CONTROL_TUNNELS";
  private TextView vpnSubtitle;
  private View vpnTile;
  private String vpnTunnel = "";
  private boolean vpnPending = false, vpnWantUp = false, vpnBeforeActive = false;
  private long vpnDeadline = 0;
  private final Runnable vpnTick =
      new Runnable() {
        public void run() {
          if (!resumed) return;
          updateVpn();
          ui.postDelayed(this, 1500);
        }
      };

  private int vpnState() {
    // 0: no visible VPN, 1: AmneziaWG owner, 2: another/undisclosed owner, -1: unknown.
    try {
      android.net.ConnectivityManager cm =
          (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
      int uid = pm.getApplicationInfo("org.amnezia.awg", 0).uid;
      for (android.net.Network network : cm.getAllNetworks()) {
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
          return android.os.Build.VERSION.SDK_INT >= 30 && caps.getOwnerUid() == uid ? 1 : 2;
        }
      }
      return 0;
    } catch (Exception error) {
      return -1;
    }
  }

  private void updateVpn() {
    int state = vpnState();
    if (vpnPending) {
      boolean confirmed = vpnWantUp ? (state == 1 || (!vpnBeforeActive && state == 2)) : state == 0;
      if (confirmed) vpnPending = false;
      else if (android.os.SystemClock.elapsedRealtime() > vpnDeadline) {
        vpnPending = false;
        Toast.makeText(
                this,
                "Переключение не подтверждено. Проверь имя туннеля и разрешение внешнего управления"
                    + " в AmneziaWG.",
                Toast.LENGTH_LONG)
            .show();
      }
    }
    if (vpnSubtitle != null)
      vpnSubtitle.setText(
          LauncherLogic.vpnLabel(state, vpnPending, vpnWantUp, !vpnTunnel.isEmpty()));
    if (vpnTile != null)
      vpnTile.setContentDescription(
          "VPN. "
              + (vpnSubtitle == null ? "AmneziaWG" : vpnSubtitle.getText())
              + ". Удерживай OK для настроек");
  }

  private void toggleVpn() {
    if (!hasApp("org.amnezia.awg")) {
      Toast.makeText(this, "AmneziaWG не найдена", Toast.LENGTH_LONG).show();
      return;
    }
    if (vpnTunnel.isEmpty()) {
      vpnSettings();
      return;
    }
    if (checkSelfPermission(AWG_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {AWG_PERMISSION}, 401);
      return;
    }
    if (vpnPending) {
      Toast.makeText(this, "Дождись завершения переключения", Toast.LENGTH_SHORT).show();
      return;
    }
    int state = vpnState();
    if (state == 0) sendVpn(true);
    else if (state == 1) sendVpn(false);
    else
      new android.app.AlertDialog.Builder(this)
          .setTitle("AmneziaWG: " + vpnTunnel)
          .setMessage(
              "Android не позволяет подтвердить, какой туннель активен. Выбери команду явно.")
          .setPositiveButton("Включить", (d, w) -> sendVpn(true))
          .setNegativeButton("Выключить", (d, w) -> sendVpn(false))
          .setNeutralButton("Отмена", null)
          .show();
  }

  private void sendVpn(boolean up) {
    try {
      Intent command = new Intent("org.amnezia.awg.action.SET_TUNNEL_" + (up ? "UP" : "DOWN"));
      command.setComponent(
          new android.content.ComponentName(
              "org.amnezia.awg", "org.amnezia.awg.model.TunnelManager$IntentReceiver"));
      android.content.pm.ActivityInfo receiver = pm.getReceiverInfo(command.getComponent(), 0);
      if (!receiver.exported) {
        Toast.makeText(
                this, "Эта версия AmneziaWG не разрешает внешнее управление", Toast.LENGTH_LONG)
            .show();
        return;
      }
      command.putExtra("tunnel", vpnTunnel);
      vpnBeforeActive = vpnState() > 0;
      sendBroadcast(command);
      vpnPending = true;
      vpnWantUp = up;
      vpnDeadline = android.os.SystemClock.elapsedRealtime() + 15000;
      updateVpn();
    } catch (PackageManager.NameNotFoundException error) {
      Toast.makeText(
              this,
              "Интерфейс управления AmneziaWG не найден в установленной версии",
              Toast.LENGTH_LONG)
          .show();
    } catch (SecurityException error) {
      Toast.makeText(
              this,
              "Нет разрешения CONTROL_TUNNELS. Проверь настройки AmneziaWG.",
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void vpnSettings() {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(vpnTunnel);
    input.setHint("Точное имя туннеля");
    LinearLayout content = column();
    content.setPadding(unit(24), 0, unit(24), 0);
    content.addView(
        label(
            "В AmneziaWG включи «Разрешить управление через внешние приложения». Имя туннеля должно"
                + " совпадать с его названием в приложении. Однократно подключи его там и подтверди"
                + " системный запрос VPN.",
            14,
            MUTED));
    content.addView(input);
    android.app.AlertDialog dialog =
        new android.app.AlertDialog.Builder(this)
            .setTitle("Управление AmneziaWG")
            .setView(content)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .setNeutralButton(
                "Открыть AmneziaWG",
                (d, w) -> {
                  for (ResolveInfo app : installed)
                    if (app.activityInfo.packageName.equals("org.amnezia.awg")) {
                      launch(app);
                      return;
                    }
                })
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String name = input.getText().toString().trim();
                      if (!name.matches("[a-zA-Z0-9_=+.-]{1,15}")) {
                        input.setError("Имя: 1–15 латинских букв, цифр или _ = + . -");
                        return;
                      }
                      vpnTunnel = name;
                      getSharedPreferences("vpn_control", MODE_PRIVATE)
                          .edit()
                          .putString("tunnel", name)
                          .apply();
                      dialog.dismiss();
                      updateVpn();
                    }));
    dialog.show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 401) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) toggleVpn();
      else
        Toast.makeText(this, "Разреши Luma управлять туннелями AmneziaWG", Toast.LENGTH_LONG)
            .show();
    }
  }

  private final SearchState searchState = new SearchState();

  private static final class SearchState {
    String draft = "", query = "", status = "";
    int mode = 0, page = 1, pages = 1, request = 0, credentialVersion = -1;
    boolean loading = false, loaded = false;
    List<CatalogManager.Film> rows = Collections.emptyList();
  }

  private void rememberSearchInput() {
    if (!page.equals("search")) return;
    View input = getWindow().getDecorView().findViewWithTag("search:query");
    if (input instanceof EditText) searchState.draft = ((EditText) input).getText().toString();
  }

  private void startSearch(EditText input) {
    final String value;
    try {
      value = BrowseLogic.searchText(input.getText().toString());
    } catch (IllegalArgumentException error) {
      input.setError("Введи название от 1 до 200 символов");
      return;
    }
    searchState.draft = value;
    searchState.query = value;
    searchState.page = 1;
    android.view.inputmethod.InputMethodManager keyboard =
        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
    restoreFocus = "search:submit";
    showSearch(true);
  }

  private void showSearch(boolean reload) {
    SearchState state = searchState;
    if (state.credentialVersion != catalog.version()) {
      state.credentialVersion = catalog.version();
      state.request++;
      state.loading = false;
      state.loaded = false;
      state.rows = Collections.emptyList();
      state.status = "";
    }
    boolean start =
        catalog.configured()
            && !state.query.isEmpty()
            && (reload || (!state.loading && !state.loaded));
    if (start) {
      state.loading = true;
      state.rows = Collections.emptyList();
    }
    beginPage("search");
    setup();
    top();
    pageIntro("Поиск", "Найди фильм или сериал для своего вечера");
    LinearLayout form = new LinearLayout(this);
    form.setGravity(Gravity.CENTER_VERTICAL);
    form.setClipChildren(false);
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(17));
    input.setTextColor(WHITE);
    input.setHintTextColor(MUTED);
    input.setHint("Название фильма или сериала");
    input.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    input.setPadding(unit(18), unit(10), unit(18), unit(10));
    input.setBackground(LumaStyle.states(CARD, unit(18), designScale));
    input.setDefaultFocusHighlightEnabled(false);
    LineIcon searchIcon = new LineIcon("search", MUTED);
    searchIcon.setBounds(0, 0, unit(20), unit(20));
    input.setCompoundDrawablesRelative(searchIcon, null, null, null);
    input.setCompoundDrawablePadding(unit(12));
    input.setText(state.draft);
    input.setSelection(input.length());
    input.setTag("search:query");
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(200)});
    form.addView(input, new LinearLayout.LayoutParams(0, unit(52), 1));
    TextView submit = button("Найти", () -> startSearch(input));
    submit.setTag("search:submit");
    submit.setTextColor(0xff091017);
    focus(submit, 0xffdce0e7);
    LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(unit(100), unit(48));
    submitParams.leftMargin = unit(12);
    form.addView(submit, submitParams);
    input.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
              || action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
              || (event != null
                  && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                  && event.getAction() == KeyEvent.ACTION_DOWN)) {
            startSearch(input);
            return true;
          }
          return false;
        });
    body.addView(form);
    space(body, 10);
    LinearLayout controls = new LinearLayout(this);
    controls.setClipChildren(false);
    String[] modes = {"Фильмы и сериалы", "Только фильмы", "Только сериалы"};
    addFilter(
        controls,
        modes[state.mode],
        () -> {
          rememberSearchInput();
          new android.app.AlertDialog.Builder(this)
              .setTitle("Искать в разделе")
              .setSingleChoiceItems(
                  modes,
                  state.mode,
                  (d, i) -> {
                    state.mode = i;
                    state.page = 1;
                    d.dismiss();
                    restoreFocus = "filter:search-mode";
                    showSearch(!state.query.isEmpty());
                  })
              .setNegativeButton("Отмена", null)
              .show();
        },
        "search-mode");
    addFilter(
        controls,
        "Очистить",
        () -> {
          state.request++;
          state.draft = state.query = "";
          state.loading = false;
          state.loaded = false;
          state.rows = Collections.emptyList();
          state.status = "";
          state.page = state.pages = 1;
          restoreFocus = "search:query";
          showSearch(false);
        },
        "search-clear");
    if (!state.query.isEmpty())
      addFilter(
          controls,
          "Повторить",
          () -> {
            rememberSearchInput();
            showSearch(true);
          },
          "search-retry");
    body.addView(controls);
    space(body, 12);
    if (!catalog.configured()) {
      emptyPanel(
          "search",
          "Подключи каталог",
          "Для поиска нужен токен TMDB.",
          "Настроить каталог",
          this::catalogSettings);
    } else if (state.query.isEmpty()) {
      emptyPanel(
          "search", "Что будем смотреть?", "Введи название сверху и нажми «Найти».", null, null);
    } else {
      body.addView(label("Запрос: " + state.query, 16, WHITE));
      space(body, 6);
      body.addView(label(state.loading ? "Поиск…" : state.status, 14, MUTED));
      space(body, 10);
      if (state.rows.isEmpty()
          && state.loaded
          && !state.loading
          && state.status.startsWith("TMDB ·"))
        emptyPanel(
            "search",
            "Ничего не найдено",
            "Попробуй другое название или измени раздел поиска.",
            null,
            null);
      LinearLayout row = null;
      int index = 0;
      for (CatalogManager.Film film : state.rows) {
        if (index % 4 == 0) {
          row = new LinearLayout(this);
          row.setClipChildren(false);
          row.setPadding(unit(6), unit(6), unit(6), unit(6));
          body.addView(row, new LinearLayout.LayoutParams(-1, unit(178)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, unit(163), 1);
        lp.rightMargin = unit(14);
        row.addView(browseCard(film), lp);
        index++;
      }
      if (row != null && index % 4 != 0)
        for (int i = index % 4; i < 4; i++) {
          LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, unit(163), 1);
          lp.rightMargin = unit(14);
          row.addView(new View(this), lp);
        }
      if (state.loaded && !state.loading) {
        LinearLayout paging = new LinearLayout(this);
        paging.setClipChildren(false);
        if (state.page > 1)
          addFilter(
              paging,
              "← Предыдущая",
              () -> {
                rememberSearchInput();
                state.page--;
                showSearch(true);
              },
              "search-previous");
        TextView pageLabel = label("Страница " + state.page + " / " + state.pages, 15, MUTED);
        pageLabel.setGravity(Gravity.CENTER);
        paging.addView(pageLabel, new LinearLayout.LayoutParams(0, unit(48), 1));
        if (state.page < state.pages)
          addFilter(
              paging,
              "Следующая →",
              () -> {
                rememberSearchInput();
                state.page++;
                showSearch(true);
              },
              "search-next");
        body.addView(paging);
      }
    }
    if (restoreFocus == null && state.query.isEmpty()) restoreFocus = "search:query";
    restoreTileFocus();
    if (start) {
      final int request = ++state.request;
      catalog.search(
          state.query,
          new String[] {"multi", "movie", "tv"}[state.mode],
          state.page,
          (result, error) -> {
            if (isDestroyed() || request != state.request) return;
            rememberSearchInput();
            state.loading = false;
            state.loaded = true;
            if (result == null) {
              state.rows = Collections.emptyList();
              state.status = error == null ? "Ошибка поиска" : error;
            } else {
              state.rows = result.films;
              state.pages = result.pages;
              state.status =
                  error == null
                      ? "TMDB · " + state.rows.size() + " результатов на странице"
                      : error;
            }
            if (resumed && page.equals("search")) showSearch(false);
          });
    }
  }

  private int favoritesPage = 1, favoritesMode = 0;

  private void toggleFilmFavorite(CatalogManager.Film film) {
    try {
      boolean added = filmFavorites.toggle(film);
      String key = film.type + ":" + film.id;
      if (added) favoriteBadges.add(key);
      else favoriteBadges.remove(key);
      View badge = getWindow().getDecorView().findViewWithTag("badge:" + key);
      if (badge != null) badge.setVisibility(added ? View.VISIBLE : View.GONE);
      Toast.makeText(
              this, added ? "Добавлено в избранное" : "Удалено из избранного", Toast.LENGTH_SHORT)
          .show();
      if (page.equals("favorites")) {
        if (!added) restoreFocus = "filter:favorites-mode";
        showFilmFavorites();
      }
    } catch (Exception error) {
      Toast.makeText(this, "Не удалось сохранить избранное", Toast.LENGTH_LONG).show();
    }
  }

  private void showFilmFavorites() {
    beginPage("favorites");
    setup();
    top();
    space(body, 16);
    List<CatalogManager.Film> all = filmFavorites.all(), list = new ArrayList<>();
    for (CatalogManager.Film film : all)
      if (favoritesMode == 0 || film.type.equals(favoritesMode == 1 ? "movie" : "tv"))
        list.add(film);
    pageIntro(
        "Избранное", "Сохранено: " + list.size() + " · OK — смотреть, удержание OK — описание");
    LinearLayout filters = new LinearLayout(this);
    filters.setClipChildren(false);
    String[] modes = {"Фильмы и сериалы", "Только фильмы", "Только сериалы"};
    addFilter(
        filters,
        modes[favoritesMode],
        () ->
            new android.app.AlertDialog.Builder(this)
                .setTitle("Избранное")
                .setSingleChoiceItems(
                    modes,
                    favoritesMode,
                    (d, i) -> {
                      favoritesMode = i;
                      favoritesPage = 1;
                      d.dismiss();
                      restoreFocus = "filter:favorites-mode";
                      showFilmFavorites();
                    })
                .setNegativeButton("Отмена", null)
                .show(),
        "favorites-mode");
    body.addView(filters);
    space(body, 12);
    if (list.isEmpty()) {
      emptyPanel(
          "favorite",
          all.isEmpty() ? "Здесь будет твоё избранное" : "В этом разделе пока пусто",
          all.isEmpty()
              ? "Добавляй фильмы и сериалы через кнопку «В избранное» в описании."
              : "Выбери другой раздел или найди что-нибудь новое.",
          "Найти фильм",
          () -> showSearch(false));
      restoreTileFocus();
      return;
    }
    int pages = (list.size() + 19) / 20;
    favoritesPage = Math.max(1, Math.min(favoritesPage, pages));
    int first = (favoritesPage - 1) * 20, last = Math.min(first + 20, list.size()), index = 0;
    LinearLayout row = null;
    for (CatalogManager.Film film : list.subList(first, last)) {
      if (index % 4 == 0) {
        row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setPadding(unit(6), unit(6), unit(6), unit(6));
        body.addView(row, new LinearLayout.LayoutParams(-1, unit(178)));
      }
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, unit(163), 1);
      lp.rightMargin = unit(14);
      row.addView(browseCard(film), lp);
      index++;
    }
    if (row != null && index % 4 != 0)
      for (int i = index % 4; i < 4; i++) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, unit(163), 1);
        lp.rightMargin = unit(14);
        row.addView(new View(this), lp);
      }
    LinearLayout paging = new LinearLayout(this);
    paging.setClipChildren(false);
    if (favoritesPage > 1)
      addFilter(
          paging,
          "← Предыдущая",
          () -> {
            favoritesPage--;
            showFilmFavorites();
          },
          "favorites-previous");
    TextView label = label("Страница " + favoritesPage + " / " + pages, 15, MUTED);
    label.setGravity(Gravity.CENTER);
    paging.addView(label, new LinearLayout.LayoutParams(0, unit(48), 1));
    if (favoritesPage < pages)
      addFilter(
          paging,
          "Следующая →",
          () -> {
            favoritesPage++;
            showFilmFavorites();
          },
          "favorites-next");
    body.addView(paging);
    restoreTileFocus();
  }

  private FrameLayout homeContent;
  private ScrollView homeScroll;
  private int homeOffset = 0, bannerIndex = 0;
  private String bannerSource = "";
  private CatalogManager.Film shownFilm;

  private void stepBanner(int delta, String focusTag) {
    bannerIndex = HomeFeeds.step(bannerIndex, delta, catalog.films().size());
    restoreFocus = focusTag;
    showHome();
  }

  private void homeFeedSettings() {
    String[] ids = HomeFeeds.ids(), labels = new String[ids.length];
    boolean[] selected = new boolean[ids.length];
    List<String> current = catalog.homeSources();
    for (int i = 0; i < ids.length; i++) {
      labels[i] = HomeFeeds.title(ids[i]);
      selected[i] = current.contains(ids[i]);
    }
    android.app.AlertDialog dialog =
        new android.app.AlertDialog.Builder(this)
            .setTitle("Подборки на главной — до трёх")
            .setMultiChoiceItems(labels, selected, (d, which, checked) -> selected[which] = checked)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Настройки TMDB", (d, w) -> catalogSettings())
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      ArrayList<String> choice = new ArrayList<>();
                      for (int i = 0; i < ids.length; i++) if (selected[i]) choice.add(ids[i]);
                      if (choice.isEmpty() || choice.size() > 3) {
                        Toast.makeText(this, "Выбери от одной до трёх подборок", Toast.LENGTH_SHORT)
                            .show();
                        return;
                      }
                      try {
                        catalog.setHomeSources(choice);
                        bannerIndex = 0;
                        homeOffset = 0;
                        if (homeScroll != null) homeScroll.scrollTo(0, 0);
                        dialog.dismiss();
                        showHome();
                      } catch (Exception error) {
                        Toast.makeText(
                                this, "Не удалось сохранить оформление главной", Toast.LENGTH_LONG)
                            .show();
                      }
                    }));
    dialog.show();
  }

  private void drawHomeRows(FrameLayout content) {
    List<String> sources = catalog.homeSources();
    for (int row = 0; row < sources.size(); row++) {
      String source = sources.get(row);
      int y = row * 180;
      place(content, designText(HomeFeeds.title(source), 19, WHITE, false), 80, 535 + y, 600, 24);
      if (row == 0) {
        FrameLayout customize = new FrameLayout(this);
        customize.setTag("home:settings");
        customize.setContentDescription("Настроить подборки на главной");
        focus(customize, 0x101e2b36);
        customize.setOnClickListener(v -> homeFeedSettings());
        catalogLabel = designText("Настроить подборки", 12, WHITE, false);
        catalogLabel.setSingleLine(true);
        catalogLabel.setGravity(Gravity.CENTER_VERTICAL);
        place(customize, icon("sliders", MUTED), 13, 8, 16, 16);
        place(customize, catalogLabel, 42, 0, 147, 32);
        place(content, customize, 1070, 530, 197, 32);
      }
      List<CatalogManager.Film> films = catalog.feed(source);
      if (films.isEmpty()) {
        TextView empty = designText(catalog.feedStatus(source), 14, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(bg(CARD, 0));
        homeEmptyLabels.put(source, empty);
        place(content, empty, 80, 568 + y, 1182, 125);
      } else {
        int i = 0;
        for (CatalogManager.Film film : films)
          place(content, onlineCard(film, source), 80 + (i++) * 199, 568 + y, 187, 142);
      }
      if (!films.isEmpty()) {
        TextView sourceStatus = designText(catalog.feedStatus(source), 10, MUTED, false);
        sourceStatus.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        homeFeedLabels.put(source, sourceStatus);
        place(content, sourceStatus, 760, 536 + y, row == 0 ? 220 : 507, 23);
      }
    }
  }

  private void paintHdmiHome() {
    if (hdmiHomeSubtitle != null && hdmiInputs != null)
      hdmiHomeSubtitle.setText(
          hdmiInputs.ports().isEmpty()
              ? "HDMI · открыть устройства"
              : "HDMI · входов: " + hdmiInputs.ports().size());
  }

  private String hdmiPageKey() {
    JSONArray rows =
        new JSONArray().put(hdmiInputs.message()).put(hdmiPrefs.getString("last_input", ""));
    for (HdmiLogic.Port port : hdmiInputs.ports())
      rows.put(
          new JSONArray()
              .put(port.id)
              .put(port.target)
              .put(port.label)
              .put(port.device)
              .put(port.type)
              .put(port.state)
              .put(hdmiPrefs.getString("name:" + port.id, ""))
              .put(hdmiPrefs.getString("type:" + port.id, "auto")));
    return rows.toString();
  }

  private void showDevices() {
    beginPage("devices");
    setup();
    top();
    pageHeader.addView(heading("Устройства", 29));
    TextView hint =
        label("Выбери HDMI-вход. Удерживай OK, чтобы изменить название и значок.", 14, MUTED);
    hint.setPadding(0, unit(9), 0, unit(18));
    pageHeader.addView(hint);
    LinearLayout tools = new LinearLayout(this);
    tools.setClipChildren(false);
    tools.setPadding(unit(6), unit(6), unit(6), unit(6));
    View refresh = button("Обновить входы", () -> hdmiInputs.refresh());
    View system = button("Выбрать вход на ТВ", this::openSystemInputs);
    LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-2, unit(44));
    refreshParams.rightMargin = unit(12);
    tools.addView(refresh, refreshParams);
    tools.addView(system, new LinearLayout.LayoutParams(-2, unit(44)));
    pageHeader.addView(tools);
    space(body, 18);
    List<HdmiLogic.Port> ports = hdmiInputs.ports();
    LinearLayout line = null;
    for (int i = 0; i < ports.size(); i++) {
      if (i % 3 == 0) {
        line = new LinearLayout(this);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(unit(6), unit(6), unit(6), unit(6));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, unit(242));
        row.bottomMargin = unit(12);
        body.addView(line, row);
      }
      View card = hdmiCard(ports.get(i));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, unit(224), 1);
      cp.rightMargin = unit(12);
      line.addView(card, cp);
    }
    if (line != null && ports.size() % 3 != 0)
      for (int i = ports.size() % 3; i < 3; i++) {
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, unit(224), 1);
        cp.rightMargin = unit(12);
        line.addView(new View(this), cp);
      }
    if (ports.isEmpty()) {
      emptyPanel(
          "devices",
          "HDMI-входы пока недоступны",
          hdmiInputs.message(),
          "Открыть входы телевизора",
          this::openSystemInputs);
    }
    TextView note =
        label(
            "Если телевизор не сообщает состояние входа, переключиться на него всё равно можно. "
                + "Метка «Последний выбор» показывает твой выбор в Luma.",
            12,
            MUTED);
    note.setPadding(unit(9), unit(14), unit(9), unit(18));
    body.addView(note);
    // Shared row navigation owns the links; a second HDMI-specific pass used to be overwritten.
    devicesRenderedKey = hdmiPageKey();
    restoreTileFocus();
  }

  private View hdmiCard(HdmiLogic.Port port) {
    String manualName = hdmiPrefs.getString("name:" + port.id, ""),
        manualType = hdmiPrefs.getString("type:" + port.id, "auto");
    String name = HdmiLogic.displayName(port, manualName, manualType);
    LinearLayout card = column();
    card.setPadding(unit(22), unit(20), unit(22), unit(18));
    focus(card, CARD);
    card.setTag("device:" + port.id);
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    ImageView image = new ImageView(this);
    image.setImageDrawable(new HdmiDeviceIcon(HdmiLogic.iconType(port, manualType)));
    top.addView(image, new LinearLayout.LayoutParams(unit(52), unit(52)));
    TextView inputLabel = label(port.label, 13, MUTED);
    inputLabel.setPadding(unit(16), 0, 0, 0);
    inputLabel.setSingleLine(true);
    inputLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
    top.addView(inputLabel, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(top);
    space(card, 16);
    TextView title = heading(name, 21);
    title.setMaxLines(2);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(title, new LinearLayout.LayoutParams(-1, unit(52)));
    TextView status =
        label(HdmiLogic.stateLabel(port.state), 13, port.state == 0 ? 0xff9ccdb5 : MUTED);
    status.setSingleLine(true);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(status);
    space(card, 8);
    boolean last = port.id.equals(hdmiPrefs.getString("last_input", ""));
    TextView foot = label(last ? "Последний выбор" : "OK — переключить вход", 11, MUTED);
    foot.setSingleLine(true);
    foot.setEllipsize(android.text.TextUtils.TruncateAt.END);
    card.addView(foot);
    card.setContentDescription(
        port.label
            + ". "
            + name
            + ". "
            + HdmiLogic.stateLabel(port.state)
            + ". OK — открыть, удержание — настроить.");
    card.setOnClickListener(v -> openHdmi(port));
    card.setOnLongClickListener(
        v -> {
          editHdmi(port);
          return true;
        });
    return card;
  }

  private void openHdmi(HdmiLogic.Port port) {
    android.net.Uri uri =
        android.media.tv.TvContract.buildChannelUriForPassthroughInput(port.target);
    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try {
      // Try the known Salute TV input application, then Android's regular resolver.
      try {
        startActivity(new Intent(intent).setPackage("ru.sberdevices.tvinputs"));
      } catch (android.content.ActivityNotFoundException | SecurityException unsupported) {
        startActivity(intent);
      }
      hdmiPrefs.edit().putString("last_input", port.id).apply();
    } catch (RuntimeException error) {
      android.util.Log.w("SashaTV", "HDMI launch failed: " + error.getClass().getSimpleName());
      Toast.makeText(
              this,
              "Прошивка не открыла этот HDMI напрямую. Открой «Выбор входов на ТВ».",
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void openSystemInputs() {
    Intent intent = pm.getLeanbackLaunchIntentForPackage("ru.sberdevices.tvinputs");
    if (intent == null) intent = pm.getLaunchIntentForPackage("ru.sberdevices.tvinputs");
    if (intent != null)
      try {
        startActivity(intent);
        return;
      } catch (RuntimeException ignored) {
      }
    Toast.makeText(
            this,
            "Системный выбор входов не предоставлен приложению. Открываю настройки телевизора.",
            Toast.LENGTH_LONG)
        .show();
    openSystemSettings();
  }

  private void redrawDevices(String id) {
    restoreFocus = "device:" + id;
    if (page.equals("devices")) showDevices();
  }

  private void editHdmi(HdmiLogic.Port port) {
    new android.app.AlertDialog.Builder(this)
        .setTitle(port.label)
        .setItems(
            new String[] {"Название", "Значок устройства", "Сбросить мои настройки"},
            (dialog, which) -> {
              if (which == 0) {
                EditText input = new EditText(this);
                input.setSingleLine(true);
                input.setFilters(
                    new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(64)});
                input.setText(hdmiPrefs.getString("name:" + port.id, ""));
                input.setSelection(input.length());
                input.setTextColor(WHITE);
                input.setHintTextColor(MUTED);
                input.setPadding(unit(20), unit(12), unit(20), unit(12));
                input.setHint("Пусто — название от телевизора");
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Название устройства")
                    .setView(input)
                    .setPositiveButton(
                        "Сохранить",
                        (d, w) -> {
                          hdmiPrefs
                              .edit()
                              .putString(
                                  "name:" + port.id, HdmiLogic.clean(input.getText().toString()))
                              .apply();
                          redrawDevices(port.id);
                        })
                    .setNegativeButton("Отмена", null)
                    .show();
              } else if (which == 1) {
                int current =
                    Arrays.asList(HdmiLogic.TYPES)
                        .indexOf(
                            HdmiLogic.checkedType(hdmiPrefs.getString("type:" + port.id, "auto")));
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Значок устройства")
                    .setSingleChoiceItems(
                        HdmiLogic.TYPE_LABELS,
                        current,
                        (d, i) -> {
                          hdmiPrefs.edit().putString("type:" + port.id, HdmiLogic.TYPES[i]).apply();
                          d.dismiss();
                          redrawDevices(port.id);
                        })
                    .setNegativeButton("Отмена", null)
                    .show();
              } else {
                hdmiPrefs.edit().remove("name:" + port.id).remove("type:" + port.id).apply();
                redrawDevices(port.id);
              }
            })
        .setNegativeButton("Закрыть", null)
        .show();
  }
}
