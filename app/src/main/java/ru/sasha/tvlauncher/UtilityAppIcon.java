package ru.sasha.tvlauncher;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.util.Locale;

/** Luma's vector treatment for utility/system apps, without APK backplates. */
final class UtilityAppIcon extends Drawable {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path lines = new Path();
  private final String kind, initials;
  private final int color;
  private int alpha = 255;

  UtilityAppIcon(String label, String pkg) {
    String key = (label + " " + pkg).toLowerCase(Locale.ROOT);
    kind =
        key.contains("adb")
            ? "adb"
            : key.contains("factory")
                ? "tools"
                : key.contains("parent")
                    ? "shield"
                    : key.contains("localsend")
                        ? "share"
                        : key.contains("mhshare") || key.contains("cast")
                            ? "share"
                            : key.contains("material files") || key.contains("file")
                                ? "folder"
                                : key.contains("webview")
                                    ? "globe"
                                    : key.contains("sai")
                                        ? "install"
                                        : key.contains("vimu")
                                                || key.contains("медиа")
                                                || key.contains("media")
                                            ? "media"
                                            : key.contains("beta") || key.contains("бета")
                                                ? "beta"
                                                : key.contains("kids") || key.contains("детск")
                                                    ? "kids"
                                                    : key.contains("market")
                                                            || key.contains("каталог")
                                                        ? "market"
                                                        : key.contains("settings")
                                                                || key.contains("настрой")
                                                                || key.contains("launcher manager")
                                                            ? "settings"
                                                            : label.equalsIgnoreCase("Home")
                                                                ? "home"
                                                                : "initials";
    String text = label == null ? "" : label.trim();
    initials =
        text.isEmpty()
            ? "APP"
            : text.substring(
                    0,
                    text.offsetByCodePoints(0, Math.min(2, text.codePointCount(0, text.length()))))
                .toUpperCase(Locale.ROOT);
    color =
        kind.equals("shield")
            ? 0xffbca7e9
            : kind.equals("adb") || kind.equals("settings")
                ? 0xff90c7ee
                : kind.equals("folder") || kind.equals("share")
                    ? 0xff9ac8f4
                    : kind.equals("kids") || kind.equals("market") ? 0xffa9d9bf : 0xffd8dce5;
    paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    if (kind.equals("folder")) {
      line(8, 19, 8, 49, 56, 49, 56, 23, 31, 23, 26, 16, 8, 16, 8, 19);
    } else if (kind.equals("home")) {
      line(7, 29, 32, 9, 57, 29);
      line(14, 25, 14, 55, 27, 55, 27, 37, 38, 37, 38, 55, 50, 55, 50, 25);
    } else if (kind.equals("shield")) {
      line(32, 7, 53, 16, 50, 39, 43, 50, 32, 57, 21, 50, 14, 39, 11, 16, 32, 7);
      line(23, 32, 29, 38, 41, 25);
    } else if (kind.equals("media")) {
      lines.addRoundRect(7, 12, 57, 52, 7, 7, Path.Direction.CW);
      line(27, 23, 42, 32, 27, 41, 27, 23);
    } else if (kind.equals("install")) {
      line(12, 23, 12, 53, 52, 53, 52, 23);
      line(32, 8, 32, 40);
      line(23, 31, 32, 40, 41, 31);
    } else if (kind.equals("share")) {
      line(22, 30, 45, 16);
      line(22, 34, 45, 48);
      lines.addCircle(15, 32, 7, Path.Direction.CW);
      lines.addCircle(50, 12, 6, Path.Direction.CW);
      lines.addCircle(50, 52, 6, Path.Direction.CW);
    } else if (kind.equals("globe")) {
      lines.addCircle(32, 32, 25, Path.Direction.CW);
      lines.addOval(20, 7, 44, 57, Path.Direction.CW);
      line(8, 24, 56, 24);
      line(8, 40, 56, 40);
    } else if (kind.equals("settings")) {
      for (int i = 0; i < 48; i++) {
        double angle = i * Math.PI / 24;
        float radius = i % 6 < 3 ? 24 : 19;
        float x = 32 + (float) Math.cos(angle) * radius, y = 32 + (float) Math.sin(angle) * radius;
        if (i == 0) lines.moveTo(x, y);
        else lines.lineTo(x, y);
      }
      lines.close();
      lines.addCircle(32, 32, 8, Path.Direction.CW);
    } else if (kind.equals("tools")) {
      line(12, 12, 48, 50, 54, 44, 18, 6);
      line(39, 12, 50, 12, 50, 23, 24, 49, 14, 49, 14, 39, 39, 12);
    } else if (kind.equals("adb")) {
      lines.addRoundRect(8, 15, 56, 49, 7, 7, Path.Direction.CW);
      line(23, 49, 23, 56, 41, 56, 41, 49);
      line(23, 9, 27, 15);
      line(41, 9, 37, 15);
      line(25, 27, 19, 32, 25, 37);
      line(39, 27, 45, 32, 39, 37);
    } else if (kind.equals("beta")) {
      line(24, 9, 40, 9);
      line(27, 9, 27, 26, 12, 49, 15, 55, 49, 55, 52, 49, 37, 26, 37, 9);
      line(20, 38, 44, 38);
    } else if (kind.equals("kids")) {
      lines.addCircle(32, 32, 24, Path.Direction.CW);
      lines.addArc(21, 29, 43, 44, 0, 180);
      lines.addCircle(23, 25, 2, Path.Direction.CW);
      lines.addCircle(41, 25, 2, Path.Direction.CW);
    } else if (kind.equals("market")) {
      lines.addRoundRect(12, 20, 52, 56, 6, 6, Path.Direction.CW);
      lines.addArc(23, 6, 41, 33, 180, 180);
      line(25, 32, 40, 32);
      line(25, 41, 40, 41);
    }
  }

  private void line(float... points) {
    lines.moveTo(points[0], points[1]);
    for (int i = 2; i < points.length; i += 2) lines.lineTo(points[i], points[i + 1]);
  }

  @Override
  public void draw(Canvas canvas) {
    Rect b = getBounds();
    float size = Math.min(b.width(), b.height());
    int save = canvas.save();
    canvas.translate(b.left + (b.width() - size) / 2, b.top + (b.height() - size) / 2);
    canvas.scale(size / 64f, size / 64f);
    paint.setColor(color);
    paint.setAlpha(alpha);
    if (kind.equals("initials")) {
      paint.setStyle(Paint.Style.FILL);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(26);
      canvas.drawText(initials, 32, 42, paint);
    } else {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(3);
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setStrokeCap(Paint.Cap.ROUND);
      canvas.drawPath(lines, paint);
    }
    canvas.restoreToCount(save);
  }

  @Override
  public void setAlpha(int alpha) {
    this.alpha = Math.max(0, Math.min(255, alpha));
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
