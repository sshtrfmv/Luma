package ru.sasha.tvlauncher;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.widget.*;

/** Keep artwork sharp at the right; feather a lightly blurred copy beneath the text. */
final class FrostedBackdrop extends FrameLayout {
  private final ImageView blurred;
  private final ImageView sharp;

  FrostedBackdrop(Context context) {
    super(context);
    setFocusable(false);
    setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    blurred = new ImageView(context);
    blurred.setScaleType(ImageView.ScaleType.CENTER_CROP);
    sharp =
        new ImageView(context) {
          @Override
          public void setImageBitmap(Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            if (blurred != null) blurred.setImageBitmap(bitmap);
          }

          @Override
          public void setImageDrawable(Drawable drawable) {
            super.setImageDrawable(drawable);
            if (blurred != null)
              blurred.setImageDrawable(
                  drawable == null
                      ? null
                      : drawable.getConstantState() != null
                          ? drawable.getConstantState().newDrawable(getResources())
                          : drawable);
          }
        };
    sharp.setScaleType(ImageView.ScaleType.CENTER_CROP);
    addView(sharp, new FrameLayout.LayoutParams(-1, -1));
    if (Build.VERSION.SDK_INT >= 31) {
      FeatherLayer layer = new FeatherLayer(context);
      layer.addView(blurred, new FrameLayout.LayoutParams(-1, -1));
      addView(layer, new FrameLayout.LayoutParams(-1, -1));
    }
    // A continuous low-opacity tint keeps white captions readable without an opaque block.
    View tint =
        new View(context) {
          final Paint paint = new Paint();

          @Override
          protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            if (w > 0)
              paint.setShader(
                  new LinearGradient(
                      0,
                      0,
                      w,
                      0,
                      new int[] {0xa018181e, 0x6818181e, 0x0018181e},
                      new float[] {0, .38f, .82f},
                      Shader.TileMode.CLAMP));
          }

          @Override
          protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
          }
        };
    addView(tint, new FrameLayout.LayoutParams(-1, -1));
  }

  ImageView image() {
    return sharp;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    updateLayer();
    if (Build.VERSION.SDK_INT >= 31 && w > 0) {
      float radius = Math.max(2f, w * .006f);
      blurred.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }
  }

  private void updateLayer() {
    boolean cache =
        Build.VERSION.SDK_INT >= 31 && LauncherLogic.cacheBackdrop(getWidth(), getHeight());
    int type = cache ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
    if (getLayerType() != type) {
      setLayerType(type, null);
      android.util.Log.i(
          AppDiagnostics.TAG,
          "backdrop_layer cached=" + cache + " pixels=" + (long) getWidth() * getHeight());
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateLayer();
  }

  @Override
  protected void onDetachedFromWindow() {
    // Release the extra texture when this page is discarded, retaining no static View cache.
    setLayerType(View.LAYER_TYPE_NONE, null);
    super.onDetachedFromWindow();
  }

  private static final class FeatherLayer extends FrameLayout {
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);

    FeatherLayer(Context context) {
      super(context);
      mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      super.onSizeChanged(w, h, oldw, oldh);
      if (w > 0)
        mask.setShader(
            new LinearGradient(
                0,
                0,
                w,
                0,
                new int[] {0xff000000, 0xee000000, 0x00000000, 0x00000000},
                new float[] {0, .43f, .76f, 1},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
      int saved = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
      super.dispatchDraw(canvas);
      canvas.drawRect(0, 0, getWidth(), getHeight(), mask);
      canvas.restoreToCount(saved);
    }
  }
}
