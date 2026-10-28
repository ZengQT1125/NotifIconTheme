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
 * 自己画，不调宿主 com.oplus...LayerDrawableWithAppIconKt.createBackgroundWithAppIcon：
 * 那个方法在 ColorOS 17.0.0.103 上把两个 Drawable 画反了（大头像画成应用图标、角标画成头像），
 * 用它反而得不到"头像大图 + 主题图标小角标"。这里自己裁圆角方、中心裁切、右下角贴角标，
 * 尺寸比例照抄系统观感（角标 46%、白描边 2dp）。
 */
final class AvatarBadge {

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
        return new Composed(ctx, avatar, badge);
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    /** 圆角方裁头像 + 右下角白圈角标。 */
    private static final class Composed extends Drawable {

        private final Drawable avatar;
        private final Drawable badge;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF tmp = new RectF();
        private final float radiusRatio;
        private final float badgeRatio;
        private final float ringWidth;
        private final float minBadge;
        private int alpha = 255;

        Composed(Context ctx, Drawable avatar, Drawable badge) {
            this.avatar = avatar;
            this.badge = badge;
            this.radiusRatio = 0.30f;
            this.badgeRatio = 0.46f;
            this.ringWidth = Math.max(1f, dp(ctx, 2) * 0.5f);
            this.minBadge = dp(ctx, 14);
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
            float r = s * radiusRatio;
            clip.addRoundRect(tmp, r, r, Path.Direction.CW);
            int save = c.save();
            c.clipPath(clip);
            drawCenterCrop(c, avatar, left, top, s);
            c.restoreToCount(save);

            float bs = Math.max(minBadge, s * badgeRatio);
            float cr = bs / 2f;
            float cx = left + s - cr;
            float cy = top + s - cr;
            c.drawCircle(cx, cy, cr, ring);
            int inset = Math.max(1, Math.round(bs * 0.10f));
            badge.setBounds(Math.round(cx - cr) + inset, Math.round(cy - cr) + inset,
                    Math.round(cx + cr) - inset, Math.round(cy + cr) - inset);
            badge.draw(c);
        }

        private static void drawCenterCrop(Canvas c, Drawable d, int left, int top, int s) {
            int dw = d.getIntrinsicWidth();
            int dh = d.getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) {
                d.setBounds(left, top, left + s, top + s);
                d.draw(c);
                return;
            }
            float scale = Math.max((float) s / dw, (float) s / dh);
            int w = Math.round(dw * scale);
            int h = Math.round(dh * scale);
            int x = left + (s - w) / 2;
            int y = top + (s - h) / 2;
            d.setBounds(x, y, x + w, y + h);
            d.draw(c);
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
