package com.littletater.notificontheme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 个人头像 + 应用主题图标角标。
 *
 * ColorOS 17 自己就有这套合成器（设置里的"显示应用图标"开关走它），
 * 优先反射调用宿主实现，保证头像圆角、角标描边、阴影跟系统完全一致；
 * 宿主不在或签名变了就退回自绘，效果略糙但不会空图。
 */
final class AvatarBadge {

    private static final String HOST_CLASS =
            "com.oplus.systemui.notification.row.wrapper.LayerDrawableWithAppIconKt";

    private static java.lang.reflect.Method sHost;
    private static boolean sHostTried;

    private AvatarBadge() {
    }

    /** avatar 在底、badge 在右下角。任一为空则原样返回另一个。 */
    static Drawable compose(Context ctx, Drawable avatar, Drawable badge, String pkg, int sizePx) {
        if (avatar == null) {
            return badge;
        }
        if (badge == null) {
            return avatar;
        }
        int size = sizePx > 0 ? sizePx : dp(ctx, 36);
        Drawable host = hostCompose(ctx, avatar, badge, pkg, size);
        if (host != null) {
            return host;
        }
        return new Composed(avatar, badge);
    }

    private static Drawable hostCompose(Context ctx, Drawable avatar, Drawable badge, String pkg,
                                        int size) {
        try {
            java.lang.reflect.Method m = hostMethod(ctx);
            if (m == null) {
                return null;
            }
            Object out = m.invoke(null, ctx, avatar, badge, pkg, Integer.valueOf(size));
            return out instanceof Drawable ? (Drawable) out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized java.lang.reflect.Method hostMethod(Context ctx) {
        if (sHostTried) {
            return sHost;
        }
        sHostTried = true;
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> c = Class.forName(HOST_CLASS, false, cl);
            java.lang.reflect.Method m = c.getDeclaredMethod("createBackgroundWithAppIcon",
                    Context.class, Drawable.class, Drawable.class, String.class, Integer.class);
            m.setAccessible(true);
            sHost = m;
        } catch (Throwable ignored) {
            sHost = null;
        }
        return sHost;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** 自绘兜底：圆形裁头像，右下角贴主题图标，外面一圈白描边。 */
    private static final class Composed extends Drawable {

        private final Drawable avatar;
        private final Drawable badge;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF tmp = new RectF();
        private int alpha = 255;

        Composed(Drawable avatar, Drawable badge) {
            this.avatar = avatar;
            this.badge = badge;
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            int s = Math.min(b.width(), b.height());
            if (s <= 0) {
                return;
            }
            int left = b.left + (b.width() - s) / 2;
            int top = b.top + (b.height() - s) / 2;
            tmp.set(left, top, left + s, top + s);

            clip.reset();
            clip.addCircle(tmp.centerX(), tmp.centerY(), s / 2f, Path.Direction.CW);
            int save = c.save();
            c.clipPath(clip);
            avatar.setBounds(left, top, left + s, top + s);
            avatar.draw(c);
            c.restoreToCount(save);

            int bs = Math.max(10, Math.round(s * 0.54f));
            float cr = bs / 2f;
            float cx = left + s - cr;
            float cy = top + s - cr;
            c.drawCircle(cx, cy, cr, ring);
            int inset = Math.max(1, Math.round(bs * 0.09f));
            badge.setBounds(Math.round(cx - cr) + inset, Math.round(cy - cr) + inset,
                    Math.round(cx + cr) - inset, Math.round(cy + cr) - inset);
            badge.draw(c);
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
            avatar.setAlpha(a);
            badge.setAlpha(a);
            ring.setAlpha(a);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            avatar.setColorFilter(cf);
            badge.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.max(avatar.getIntrinsicWidth(), badge.getIntrinsicWidth());
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.max(avatar.getIntrinsicHeight(), badge.getIntrinsicHeight());
        }
    }
}
