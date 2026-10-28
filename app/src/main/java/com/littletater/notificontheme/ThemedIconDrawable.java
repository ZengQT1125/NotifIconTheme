package com.littletater.notificontheme;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.content.res.ColorStateList;

/**
 * 包一层主题图标：等比缩放居中，并且吞掉系统对通知图标的染色。
 *
 * 状态栏小图标在 AOSP/ColorOS 里会被 setIconColor / setStaticDrawableColor / 视图 tint
 * 反复刷成单色，不吞掉的话图标包里的彩色图标会直接变成一团白影。
 */
final class ThemedIconDrawable extends DrawableWrapper {

    private final float scale;

    ThemedIconDrawable(Drawable inner, float scale) {
        super(inner);
        this.scale = (scale <= 0.3f || scale > 1.0f) ? 0.90f : scale;
    }

    @Override
    public void draw(Canvas canvas) {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        int side = Math.round(Math.min(w, h) * scale);
        int left = b.left + (w - side) / 2;
        int top = b.top + (h - side) / 2;
        d.setBounds(left, top, left + side, top + side);
        d.draw(canvas);
    }

    @Override
    public void setTintList(ColorStateList tint) {
        // 保留原色
    }

    @Override
    public void setTint(int tintColor) {
        // 保留原色
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // 保留原色
    }
}
