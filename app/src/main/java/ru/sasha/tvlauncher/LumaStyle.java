package ru.sasha.tvlauncher;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** Lightweight graphite materials. Drawables are prepared once, never per animation frame. */
final class LumaStyle {
  private LumaStyle() {}

  static GradientDrawable backdrop() {
    return new GradientDrawable(
        GradientDrawable.Orientation.TL_BR, new int[] {0xff1c1c21, 0xff111216, 0xff14171d});
  }

  static GradientDrawable surface(int base, float radius, float scale) {
    GradientDrawable material;
    if (Color.alpha(base) == 0) {
      material = new GradientDrawable();
      material.setColor(base);
    } else {
      material =
          new GradientDrawable(
              GradientDrawable.Orientation.TL_BR,
              new int[] {tint(base, .075f), base, shade(base, .08f)});
      // Constant material edge, identical in focused/unfocused states; no focus outline.
      material.setStroke(Math.max(1, Math.round(scale * .5f)), 0x10ffffff);
    }
    material.setCornerRadius(radius);
    return material;
  }

  static StateListDrawable states(int base, float radius, float scale) {
    StateListDrawable result = new StateListDrawable();
    int focused =
        base == 0xffdce0e7 ? 0xfff5f5f7 : Color.alpha(base) == 0 ? 0xff34363e : tint(base, .12f);
    result.addState(new int[] {android.R.attr.state_focused}, surface(focused, radius, scale));
    result.addState(new int[0], surface(base, radius, scale));
    return result;
  }

  private static int tint(int color, float fraction) {
    return Color.argb(
        Color.alpha(color),
        Math.round(Color.red(color) + (255 - Color.red(color)) * fraction),
        Math.round(Color.green(color) + (255 - Color.green(color)) * fraction),
        Math.round(Color.blue(color) + (255 - Color.blue(color)) * fraction));
  }

  private static int shade(int color, float fraction) {
    return Color.argb(
        Color.alpha(color),
        Math.round(Color.red(color) * (1 - fraction)),
        Math.round(Color.green(color) * (1 - fraction)),
        Math.round(Color.blue(color) * (1 - fraction)));
  }
}
