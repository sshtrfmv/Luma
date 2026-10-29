package ru.sasha.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** All mutable UI state lives on the main thread; network and images use bounded workers. */
final class CatalogManager {
  static final class Film {
    final String id, type, title, year, overview, image;

    Film(String id, String type, String title, String year, String overview, String image) {
      this.id = id;
      this.type = type;
      this.title = title;
      this.year = year;
      this.overview = overview;
      this.image = image;
    }
  }

  static List<Film> parse(String json) throws JSONException {
    List<Film> list = parseEntries(json, 6);
    if (list.isEmpty()) throw new JSONException("No movie or TV entries");
    return list;
  }

  private static List<Film> parseEntries(String json, int limit) throws JSONException {
    JSONArray array = new JSONObject(json).getJSONArray("results");
    List<Film> films = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (int i = 0; i < array.length() && films.size() < limit; i++) {
      JSONObject item = array.optJSONObject(i);
      if (item == null || item.optBoolean("adult", false)) continue;
      String type = item.optString("media_type", "");
      long id = item.optLong("id", 0);
      if (id <= 0 || (!type.equals("movie") && !type.equals("tv")) || !unique.add(type + ":" + id))
        continue;
      String title = item.optString(type.equals("tv") ? "name" : "title", "").trim();
      if (title.isEmpty() || title.equals("null")) continue;
      String date = item.optString(type.equals("tv") ? "first_air_date" : "release_date", "");
      String image = item.optString("backdrop_path", "");
      if (!validPath(image)) image = item.optString("poster_path", "");
      if (!validPath(image)) image = "";
      films.add(
          new Film(
              Long.toString(id),
              type,
              title,
              date.matches("\\d{4}-.*") ? date.substring(0, 4) : "",
              item.optString("overview", ""),
              image));
    }
    return Collections.unmodifiableList(films);
  }

  static final class BrowsePage {
    final List<Film> films;
    final int pages;
    final LinkedHashMap<Integer, String> genres;

    BrowsePage(List<Film> films, int pages, LinkedHashMap<Integer, String> genres) {
      this.films = films;
      this.pages = pages;
      this.genres = genres;
    }
  }

  interface BrowseCallback {
    void complete(BrowsePage result, String message);
  }

  static BrowsePage parseBrowse(String json, String type, String genreJson) throws JSONException {
    JSONObject root = new JSONObject(json);
    JSONArray array = root.getJSONArray("results");
    for (int i = 0; i < array.length(); i++) {
      JSONObject film = array.optJSONObject(i);
      if (film != null) film.put("media_type", type);
    }
    List<Film> list = parseEntries(root.toString(), 20);
    LinkedHashMap<Integer, String> genres = new LinkedHashMap<>();
    genres.put(0, "Все жанры");
    try {
      JSONArray g = new JSONObject(genreJson).getJSONArray("genres");
      for (int i = 0; i < g.length(); i++) {
        JSONObject item = g.getJSONObject(i);
        int id = item.optInt("id", 0);
        String name = item.optString("name", "");
        if (id > 0 && !name.isEmpty()) genres.put(id, name);
      }
    } catch (JSONException ignored) {
    }
    return new BrowsePage(list, Math.max(1, Math.min(500, root.optInt("total_pages", 1))), genres);
  }

  void browse(
      String type, int genre, int year, int rating, int sort, int page, BrowseCallback callback) {
    if (closed) return;
    if (token.isEmpty()) {
      callback.complete(null, "Подключи токен TMDB на главной");
      return;
    }
    final String url = BrowseLogic.query(type, genre, year, rating, sort, page), credential = token;
    final int request = generation;
    Future<?> previous = browseJobs.get(type);
    if (previous != null) previous.cancel(true);
    browseJobs.put(
        type,
        browseWorker.submit(
            () -> {
              BrowsePage result = null;
              String message = null;
              String cacheKey = "browse_" + url.hashCode();
              String genreJson = prefs.getString("genres_" + type, "{}");
              try {
                if (genreJson.equals("{}")) {
                  try {
                    genreJson =
                        new String(
                            fetch(
                                "https://api.themoviedb.org/3/genre/"
                                    + type
                                    + "/list?language=ru-RU",
                                credential,
                                65536),
                            java.nio.charset.StandardCharsets.UTF_8);
                    new JSONObject(genreJson).getJSONArray("genres");
                  } catch (Exception ignored) {
                    genreJson = "{}";
                  }
                }
                String json =
                    new String(
                        fetch(url, credential, 1024 * 1024),
                        java.nio.charset.StandardCharsets.UTF_8);
                result = parseBrowse(json, type, genreJson);
                // Cache is written on the UI thread after the credential generation check.
                final BrowsePage loaded = result;
                final String raw = json, genres = genreJson;
                main.post(
                    () -> {
                      if (closed || request != generation) return;
                      SharedPreferences.Editor editor = prefs.edit();
                      int kept = 0;
                      for (String key : prefs.getAll().keySet())
                        if (key.startsWith("browse_") && ++kept > 10) editor.remove(key);
                      editor
                          .putString(cacheKey, new JSONObjectWrapper(url, raw).json())
                          .putString("genres_" + type, genres)
                          .apply();
                      callback.complete(loaded, null);
                    });
                return;
              } catch (HttpFailure failure) {
                message =
                    (failure.status == 401 || failure.status == 403)
                        ? "TMDB отклонил доступ — проверь токен"
                        : "TMDB: HTTP " + failure.status;
              } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) return;
                message = browseError(failure);
                android.util.Log.w(
                    "SashaTV",
                    "TMDB discover/" + type + " failed: " + failure.getClass().getSimpleName());
              }
              try {
                JSONObject cache = new JSONObject(prefs.getString(cacheKey, ""));
                if (cache.optString("url").equals(url)) {
                  result = parseBrowse(cache.getString("data"), type, genreJson);
                  message += " · показан кэш";
                }
              } catch (Exception ignored) {
              }
              final BrowsePage cached = result;
              final String status = message;
              main.post(
                  () -> {
                    if (!closed && request == generation) callback.complete(cached, status);
                  });
            }));
  }

  static BrowsePage parseSearch(String json, String type) throws JSONException {
    if (!type.equals("multi") && !type.equals("movie") && !type.equals("tv"))
      throw new IllegalArgumentException("search type");
    if (!type.equals("multi")) return parseBrowse(json, type, "{}");
    JSONObject root = new JSONObject(json);
    return new BrowsePage(
        parseEntries(json, 20),
        Math.max(1, Math.min(500, root.optInt("total_pages", 1))),
        new LinkedHashMap<>());
  }

  void search(String text, String type, int page, BrowseCallback callback) {
    if (closed) return;
    if (token.isEmpty()) {
      callback.complete(null, "Подключи TMDB в настройках каталога");
      return;
    }
    final String url = BrowseLogic.search(text, type, page), credential = token;
    final int request = generation;
    final String key = "search_" + url.hashCode();
    Future<?> previous = browseJobs.get("search");
    if (previous != null) previous.cancel(true);
    browseJobs.put(
        "search",
        browseWorker.submit(
            () -> {
              BrowsePage result = null;
              String message = null;
              try {
                String json =
                    new String(
                        fetch(url, credential, 1024 * 1024),
                        java.nio.charset.StandardCharsets.UTF_8);
                result = parseSearch(json, type);
                final BrowsePage loaded = result;
                main.post(
                    () -> {
                      if (closed || request != generation) return;
                      SharedPreferences.Editor editor = prefs.edit();
                      int kept = 0;
                      for (String existing : prefs.getAll().keySet())
                        if (existing.startsWith("search_") && ++kept > 5) editor.remove(existing);
                      editor.putString(key, new JSONObjectWrapper(url, json).json()).apply();
                      callback.complete(loaded, null);
                    });
                return;
              } catch (HttpFailure failure) {
                message =
                    (failure.status == 401 || failure.status == 403)
                        ? "TMDB отклонил доступ — проверь токен"
                        : "TMDB: HTTP " + failure.status;
              } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted()) return;
                message = browseError(failure);
                android.util.Log.w(
                    "SashaTV", "TMDB search failed: " + failure.getClass().getSimpleName());
              }
              try {
                JSONObject cache = new JSONObject(prefs.getString(key, ""));
                if (cache.optString("url").equals(url)) {
                  result = parseSearch(cache.getString("data"), type);
                  message += " · показан кэш";
                }
              } catch (Exception ignored) {
              }
              final BrowsePage cached = result;
              final String status = message;
              main.post(
                  () -> {
                    if (!closed && request == generation) callback.complete(cached, status);
                  });
            }));
  }

  static String browseError(Exception failure) {
    if (failure instanceof org.json.JSONException)
      return "Ошибка данных TMDB (JSON). Пришли это сообщение";
    if (failure instanceof java.net.UnknownHostException)
      return "Не найден сервер TMDB — проверь DNS или VPN";
    if (failure instanceof java.net.SocketTimeoutException)
      return "TMDB не ответил вовремя — повтори загрузку";
    if (failure instanceof javax.net.ssl.SSLException)
      return "Ошибка TLS при подключении к TMDB (" + failure.getClass().getSimpleName() + ")";
    if (failure instanceof java.net.ConnectException)
      return "Не удалось соединиться с TMDB — проверь VPN";
    return "Ошибка каталога: " + failure.getClass().getSimpleName();
  }

  private static final class JSONObjectWrapper {
    final String url, data;

    JSONObjectWrapper(String url, String data) {
      this.url = url;
      this.data = data;
    }

    String json() {
      try {
        return new JSONObject().put("url", url).put("data", data).toString();
      } catch (JSONException e) {
        return "{}";
      }
    }
  }

  static boolean validPath(String path) {
    return path != null && path.matches("/[A-Za-z0-9_-]+\\.(jpg|png|webp)");
  }

  private final Context context;
  private final SharedPreferences prefs;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService dataWorker = Executors.newSingleThreadExecutor();
  private final ExecutorService browseWorker = Executors.newFixedThreadPool(2);
  private final Map<String, Future<?>> browseJobs = new HashMap<>();

  int version() {
    return generation;
  }

  private final ExecutorService images = Executors.newFixedThreadPool(2);
  private final LruCache<String, Bitmap> memory =
      new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value.getAllocationByteCount();
        }
      };
  private final Map<String, List<java.lang.ref.WeakReference<ImageView>>> waiting = new HashMap<>();
  private final Map<String, Long> failedImages = new HashMap<>();
  private final Map<ImageView, String> imageBindings = new WeakHashMap<>();
  private final File disk;
  private String token = "", status = "Подключить TMDB";
  private boolean loading = false, closed = false;
  private int generation = 0;
  private long lastAttempt = 0;

  CatalogManager(Context context) {
    this.context = context.getApplicationContext();
    prefs = context.getSharedPreferences("tmdb_catalog", Context.MODE_PRIVATE);
    disk = new File(context.getCacheDir(), "tmdb-images");
    disk.mkdirs();
    try {
      token = decrypt(prefs.getString("secret", ""));
    } catch (Exception e) {
      status = "Введите токен заново";
    }
    restoreFeedCaches();
    if (!films().isEmpty()) status = "Сохранённая подборка · TMDB";
    importToken();
  }

  List<Film> films() {
    return feed(primarySource());
  }

  String status() {
    return status;
  }

  boolean configured() {
    return !token.isEmpty();
  }

  void importToken() {
    File file = new File(context.getFilesDir(), "tmdb-token-import.txt");
    if (!file.exists()) return;
    try {
      String value =
          new String(read(new FileInputStream(file), 8192), java.nio.charset.StandardCharsets.UTF_8)
              .trim();
      setToken(value);
    } catch (Exception e) {
      status = "Не удалось импортировать токен";
    } finally {
      file.delete();
    }
  }

  void setToken(String input) throws Exception {
    String value = input.trim();
    if (value.startsWith("Bearer ")) value = value.substring(7).trim();
    if (value.length() < 20 || value.length() > 8192 || !value.matches("[A-Za-z0-9._-]+"))
      throw new IllegalArgumentException("Нужен API Read Access Token");
    String secret = encrypt(value);
    if (!prefs.edit().putString("secret", secret).remove("json").remove("time").commit())
      throw new IOException("Не удалось сохранить токен");
    generation++;
    token = value;
    loading = false;
    lastAttempt = 0;
    homeFeeds.clear();
    homeStatuses.clear();
    if (feedJob != null) feedJob.cancel(true);
    feedRevision++;
    status = "Каталог подключён";
  }

  void clear() {
    generation++;
    token = "";
    homeFeeds.clear();
    homeStatuses.clear();
    if (feedJob != null) feedJob.cancel(true);
    feedRevision++;
    loading = false;
    lastAttempt = 0;
    status = "Подключить TMDB";
    prefs.edit().clear().apply();
  }

  private final Map<String, List<Film>> homeFeeds = new HashMap<>();
  private final Map<String, String> homeStatuses = new HashMap<>();
  private int feedRevision = 0;
  private Future<?> feedJob;

  List<String> homeSources() {
    return HomeFeeds.selected(
        context
            .getSharedPreferences("home_layout", Context.MODE_PRIVATE)
            .getString("sources", "[]"));
  }

  String homeContentKey() {
    return HomeFeeds.contentKey(configured(), homeSources(), homeFeeds);
  }

  String primarySource() {
    return homeSources().get(0);
  }

  List<Film> feed(String source) {
    List<Film> list = homeFeeds.get(source);
    return list == null ? Collections.emptyList() : list;
  }

  String feedStatus(String source) {
    String text = homeStatuses.get(source);
    return text == null ? (configured() ? "Ожидание загрузки" : "Подключи TMDB") : text;
  }

  void setHomeSources(List<String> sources) throws IOException {
    JSONArray array = new JSONArray();
    for (String id : sources) array.put(id);
    List<String> checked = HomeFeeds.selected(array.toString());
    if (!checked.equals(sources)) throw new IllegalArgumentException("sources");
    if (!context
        .getSharedPreferences("home_layout", Context.MODE_PRIVATE)
        .edit()
        .putString("sources", array.toString())
        .commit()) throw new IOException("Cannot save home layout");
    if (feedJob != null) feedJob.cancel(true);
    feedRevision++;
    loading = false;
    lastAttempt = 0;
    status = configured() ? "Настройки сохранены" : "Подключить TMDB";
  }

  static List<Film> parseFeed(String raw, String source) throws JSONException {
    HomeFeeds.url(source);
    if (source.equals("trending")) return parse(raw);
    List<Film> all = parseBrowse(raw, source.equals("tv") ? "tv" : "movie", "{}").films;
    return Collections.unmodifiableList(new ArrayList<>(all.subList(0, Math.min(6, all.size()))));
  }

  private void restoreFeedCaches() {
    for (String source : HomeFeeds.ids()) {
      String raw =
          prefs.getString(
              "feed_" + source, source.equals("trending") ? prefs.getString("json", "") : "");
      try {
        homeFeeds.put(source, parseFeed(raw, source));
        homeStatuses.put(source, "Сохранённая подборка");
      } catch (Exception ignored) {
      }
    }
  }

  void refresh(boolean force, Runnable changed) {
    if (closed || loading || token.isEmpty()) return;
    List<String> needed = new ArrayList<>();
    long now = System.currentTimeMillis();
    for (String source : homeSources()) {
      long saved =
          prefs.getLong(
              "feedtime_" + source, source.equals("trending") ? prefs.getLong("time", 0) : 0);
      if (force
          || !homeFeeds.containsKey(source)
          || !LauncherLogic.fresh(now, saved, 6 * 60 * 60 * 1000L)) needed.add(source);
    }
    if (needed.isEmpty()) {
      status = "TMDB · Подборки главной";
      return;
    }
    long elapsed = android.os.SystemClock.elapsedRealtime();
    if (!force && lastAttempt != 0 && elapsed - lastAttempt < 60000) return;
    loading = true;
    lastAttempt = elapsed;
    status = "Загрузка подборок…";
    for (String source : needed)
      homeStatuses.put(source, feed(source).isEmpty() ? "Загрузка…" : "Обновление…");
    final int request = generation, revision = feedRevision;
    final String credential = token;
    feedJob =
        dataWorker.submit(
            () -> {
              for (String source : needed) {
                if (Thread.currentThread().isInterrupted()) return;
                List<Film> result = null;
                String raw = null, error = null;
                try {
                  raw =
                      new String(
                          fetch(HomeFeeds.url(source), credential, 1024 * 1024),
                          java.nio.charset.StandardCharsets.UTF_8);
                  result = parseFeed(raw, source);
                } catch (HttpFailure failure) {
                  error =
                      (failure.status == 401 || failure.status == 403)
                          ? "TMDB отклонил доступ — проверь токен"
                          : "TMDB: HTTP " + failure.status;
                } catch (Exception failure) {
                  if (Thread.currentThread().isInterrupted()) return;
                  error = browseError(failure);
                }
                final List<Film> loaded = result;
                final String data = raw, message = error;
                main.post(
                    () -> {
                      if (closed || request != generation || revision != feedRevision) return;
                      if (loaded != null) {
                        homeFeeds.put(source, loaded);
                        homeStatuses.put(
                            source, loaded.isEmpty() ? "В подборке пока нет фильмов" : "TMDB");
                        prefs
                            .edit()
                            .putString("feed_" + source, data)
                            .putLong("feedtime_" + source, System.currentTimeMillis())
                            .apply();
                      } else
                        homeStatuses.put(
                            source, message + (feed(source).isEmpty() ? "" : " · показан кэш"));
                      if (source.equals(primarySource())) status = feedStatus(source);
                      changed.run();
                    });
              }
              main.post(
                  () -> {
                    if (closed || request != generation || revision != feedRevision) return;
                    loading = false;
                    changed.run();
                  });
            });
  }

  static String imageKey(String path, boolean banner) {
    return (banner ? "w1280" : "w500") + path;
  }

  static int imageSample(int width, int height, boolean banner) {
    int limit = banner ? 1280 : 1000;
    int sample = 1;
    while (width / sample > limit || height / sample > limit) sample *= 2;
    return sample;
  }

  void image(ImageView view, String path) {
    image(view, path, false);
  }

  void banner(ImageView view, String path) {
    if (!validPath(path)) return;
    Bitmap preview = memory.get(imageKey(path, false));
    if (preview != null) view.setImageBitmap(preview);
    image(view, path, true);
  }

  private void image(ImageView view, String path, boolean banner) {
    if (closed || !validPath(path)) return;
    final String key = imageKey(path, banner);
    imageBindings.put(view, key);
    final int byteLimit = (banner ? 6 : 3) * 1024 * 1024;
    Bitmap cached = memory.get(key);
    if (cached != null) {
      view.setImageBitmap(cached);
      return;
    }
    long now = android.os.SystemClock.elapsedRealtime();
    Long failed = failedImages.get(key);
    if (failed != null && now - failed < 60000) {
      if (banner) image(view, path, false);
      return;
    }
    List<java.lang.ref.WeakReference<ImageView>> views = waiting.get(key);
    if (views != null) {
      views.removeIf(
          reference -> {
            ImageView old = reference.get();
            return old == null;
          });
      if (views.size() >= 32) views.remove(0);
      views.add(new java.lang.ref.WeakReference<>(view));
      return;
    }
    if (waiting.size() >= 24) {
      final java.lang.ref.WeakReference<ImageView> deferred =
          new java.lang.ref.WeakReference<>(view);
      main.postDelayed(
          () -> {
            ImageView target = deferred.get();
            if (!closed
                && target != null
                && target.isAttachedToWindow()
                && key.equals(imageBindings.get(target))) image(target, path, banner);
          },
          1500);
      return;
    }
    views = new ArrayList<>();
    views.add(new java.lang.ref.WeakReference<>(view));
    waiting.put(key, views);
    // One task per image variant; weak references do not retain replaced screens.
    images.execute(
        () -> {
          Bitmap bitmap = null;
          try {
            File file = new File(disk, (banner ? "w1280-" : "") + path.substring(1));
            byte[] bytes;
            if (file.isFile()) bytes = read(new FileInputStream(file), byteLimit);
            else {
              bytes = fetch("https://image.tmdb.org/t/p/" + key, null, byteLimit);
              synchronized (disk) {
                if (!file.isFile()) {
                  File temp = File.createTempFile("poster-", ".tmp", disk);
                  try {
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                      out.write(bytes);
                    }
                    temp.renameTo(file);
                  } finally {
                    temp.delete();
                  }
                }
                File[] files = disk.listFiles();
                if (files != null) {
                  Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                  long size = 0;
                  for (File f : files) size += f.length();
                  for (File f : files) {
                    if (size <= 16 * 1024 * 1024) break;
                    long length = f.length();
                    if (f.delete()) size -= length;
                  }
                }
              }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0
                || bounds.outHeight <= 0
                || bounds.outWidth > 8192
                || bounds.outHeight > 8192) throw new IOException("Invalid image");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = imageSample(bounds.outWidth, bounds.outHeight, banner);
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null) memory.put(key, bitmap);
          } catch (Exception error) {
            android.util.Log.w(
                "SashaTV",
                "TMDB image "
                    + (banner ? "banner" : "card")
                    + " failed: "
                    + error.getClass().getSimpleName());
          }
          final Bitmap result = bitmap;
          main.post(
              () -> {
                List<java.lang.ref.WeakReference<ImageView>> targets = waiting.remove(key);
                if (closed) return;
                if (result == null) failedImages.put(key, android.os.SystemClock.elapsedRealtime());
                else failedImages.remove(key);
                if (targets != null)
                  for (java.lang.ref.WeakReference<ImageView> reference : targets) {
                    ImageView target = reference.get();
                    // Cache reads can finish before the window is attached. The ImageView
                    // must retain its bitmap for the first draw, not discard this result.
                    if (target != null && key.equals(imageBindings.get(target))) {
                      if (result != null) target.setImageBitmap(result);
                      else if (banner) image(target, path, false);
                    }
                  }
              });
        });
  }

  static byte[] read(InputStream source, int limit) throws IOException {
    try (InputStream input = source;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (output.size() + count > limit) throw new IOException("Response too large");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static final class HttpFailure extends IOException {
    final int status;

    HttpFailure(int status) {
      this.status = status;
    }
  }

  private static byte[] fetch(String url, String credential, int limit) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    try {
      conn.setInstanceFollowRedirects(false);
      conn.setConnectTimeout(12000);
      conn.setReadTimeout(20000);
      if (credential != null) conn.setRequestProperty("Authorization", "Bearer " + credential);
      conn.setRequestProperty("Accept", credential == null ? "image/*" : "application/json");
      int status = conn.getResponseCode();
      if (status != 200) throw new HttpFailure(status);
      return read(conn.getInputStream(), limit);
    } finally {
      conn.disconnect();
    }
  }

  private javax.crypto.SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    String alias = "sashatv_tmdb";
    if (store.containsAlias(alias)) return (javax.crypto.SecretKey) store.getKey(alias, null);
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }

  private String encrypt(String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        + ":"
        + Base64.encodeToString(
            cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Base64.NO_WRAP);
  }

  private String decrypt(String value) throws Exception {
    if (value.isEmpty()) return "";
    String[] parts = value.split(":", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  interface DetailsCallback {
    void complete(FilmDetails details, String message);
  }

  private Future<?> detailsJob;
  private int detailsRevision;

  void cancelDetails() {
    detailsRevision++;
    if (detailsJob != null) detailsJob.cancel(true);
  }

  void details(Film film, boolean force, DetailsCallback callback) {
    cancelDetails();
    if (closed) return;
    if (token.isEmpty()) {
      callback.complete(null, "Подключи TMDB в настройках каталога");
      return;
    }
    final String url = FilmDetails.url(film.type, film.id),
        credential = token,
        key = "details_" + film.type + "_" + film.id;
    final int request = generation, revision = detailsRevision;
    detailsJob =
        browseWorker.submit(
            () -> {
              FilmDetails result = null;
              String message = null, raw = null;
              boolean fetched = false;
              String cached = prefs.getString(key, "");
              try {
                if (!force
                    && LauncherLogic.fresh(
                        System.currentTimeMillis(), prefs.getLong(key + "_time", 0), 86400000L))
                  result = FilmDetails.parse(cached, film.type, film.id);
              } catch (JSONException ignored) {
              }
              if (result == null)
                try {
                  raw =
                      new String(
                          fetch(url, credential, 1024 * 1024),
                          java.nio.charset.StandardCharsets.UTF_8);
                  result = FilmDetails.parse(raw, film.type, film.id);
                  fetched = true;
                } catch (Exception failure) {
                  if (Thread.currentThread().isInterrupted()) return;
                  message =
                      failure instanceof HttpFailure
                              && (((HttpFailure) failure).status == 401
                                  || ((HttpFailure) failure).status == 403)
                          ? "TMDB отклонил доступ — проверь токен"
                          : failure instanceof HttpFailure
                              ? "TMDB: HTTP " + ((HttpFailure) failure).status
                              : browseError(failure);
                  android.util.Log.w(
                      "SashaTV", "TMDB details failed: " + failure.getClass().getSimpleName());
                  try {
                    result = FilmDetails.parse(cached, film.type, film.id);
                    message += " · показаны сохранённые данные";
                  } catch (JSONException ignored) {
                  }
                }
              final FilmDetails loaded = result;
              final String status = message, data = raw;
              final boolean save = fetched;
              main.post(
                  () -> {
                    if (closed || request != generation || revision != detailsRevision) return;
                    if (save) {
                      SharedPreferences.Editor edit = prefs.edit();
                      List<String> keys = new ArrayList<>();
                      for (String k : prefs.getAll().keySet())
                        if (k.startsWith("details_") && !k.endsWith("_time")) keys.add(k);
                      keys.remove(key);
                      keys.sort(Comparator.comparingLong(k -> prefs.getLong(k + "_time", 0)));
                      while (keys.size() >= 20) {
                        String old = keys.remove(0);
                        edit.remove(old).remove(old + "_time");
                      }
                      edit.putString(key, data)
                          .putLong(key + "_time", System.currentTimeMillis())
                          .apply();
                    }
                    callback.complete(loaded, status);
                  });
            });
  }

  void clearImageMemory() {
    memory.evictAll();
    failedImages.clear();
  }

  void close() {
    cancelDetails();
    closed = true;
    generation++;
    dataWorker.shutdownNow();
    browseWorker.shutdownNow();
    browseJobs.clear();
    images.shutdownNow();
    main.removeCallbacksAndMessages(null);
    memory.evictAll();
    waiting.clear();
    failedImages.clear();
    imageBindings.clear();
  }
}
