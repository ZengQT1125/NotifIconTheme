package com.littletater.notificontheme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.HashMap;

/**
 * 通知里没有头像时的补齐：按发送者昵称生成一张字母头像。
 *
 * 有些推送（抖音 MCS 私信就是）只带昵称和正文，头像位要么是应用 logo，要么被系统裁掉。
 * 与其空着或者拿 logo 冒充，不如用昵称首字生成一张和主题图标搭得上的色块头像。
 */
final class LetterAvatar {

    private static final int[] PALETTE = {
            0xFF4E8A5A, 0xFF2F6FB5, 0xFFB5562F, 0xFF6C4BB5,
            0xFFB53F6A, 0xFF2F8A8A, 0xFF8A6C2F, 0xFF555F6B
    };

    private static final HashMap<String, Drawable> CACHE = new HashMap<>();

    private LetterAvatar() {
    }

    static Drawable of(Context ctx, String label) {
        if (ctx == null) {
            return null;
        }
        String key = label == null ? "" : label;
        Drawable cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String initial = initialOf(key);
        if (initial.isEmpty()) {
            return null;
        }
        int size = AvatarBadge.dp(ctx, 96);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(PALETTE[Math.abs(key.hashCode()) % PALETTE.length]);
        float r = size * 0.30f;
        c.drawRoundRect(new RectF(0, 0, size, size), r, r, p);
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.52f);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = size / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(initial, size / 2f, y, p);
        Drawable d = new BitmapDrawable(ctx.getResources(), bm);
        CACHE.put(key, d);
        return d;
    }

    private static String initialOf(String label) {
        if (label == null) {
            return "";
        }
        String s = label.trim();
        if (s.isEmpty()) {
            return "";
        }
        int cp = s.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase();
    }
}
