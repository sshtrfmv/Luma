package ru.sasha.tvlauncher;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small native badges; PS/X identify the chosen device category, not an official logo asset. */
final class HdmiDeviceIcon extends Drawable {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final String type;
  private int alpha = 255;

  HdmiDeviceIcon(String type) {
    this.type = type;
  }

  @Override
  public void draw(Canvas canvas) {
    Rect b = getBounds();
    int saved = canvas.save();
    canvas.translate(b.left, b.top);
    canvas.scale(b.width() / 100f, b.height() / 100f);
    int color =
        type.equals("playstation")
            ? 0xff1b4fa0
            : type.equals("xbox") ? 0xff197c3e : type.equals("pc") ? 0xff31677b : 0xff314858;
    paint.setColor(color);
    paint.setAlpha(alpha);
    paint.setStyle(Paint.Style.FILL);
    canvas.drawRoundRect(2, 2, 98, 98, 24, 24, paint);
    paint.setColor(0xffedf4ff);
    paint.setAlpha(alpha);
    if (type.equals("pc") || type.equals("box")) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(5);
      canvas.drawRoundRect(22, 25, 78, 65, 5, 5, paint);
      canvas.drawLine(50, 65, 50, 76, paint);
      canvas.drawLine(37, 77, 63, 77, paint);
    } else {
      String label = type.equals("playstation") ? "PS" : type.equals("xbox") ? "X" : "HDMI";
      paint.setStyle(Paint.Style.FILL);
      paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(label.length() > 2 ? 22 : 38);
      Paint.FontMetrics fm = paint.getFontMetrics();
      canvas.drawText(label, 50, 50 - (fm.ascent + fm.descent) / 2, paint);
    }
    canvas.restoreToCount(saved);
  }

  @Override
  public void setAlpha(int value) {
    alpha = value;
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
