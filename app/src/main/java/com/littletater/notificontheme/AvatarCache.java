package com.littletater.notificontheme;

import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 头像 Drawable 缓存。通知刷新很勤（每条消息都会 onContentUpdated），
 * 每次都 Icon.loadDrawable 一遍是白开销，按 (包名|通知 key) 缓存。
 * 配置签名变了（换图标包 / 改缩放 / 开关白名单）就整体清掉。
 */
final class AvatarCache {

    private static final int MAX = 48;

    private static final LinkedHashMap<String, Drawable> CACHE =
            new LinkedHashMap<String, Drawable>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) {
                    return size() > MAX;
                }
            };

    private AvatarCache() {
    }

    static String key(String pkg, String notificationKey) {
        return pkg + "|" + notificationKey;
    }

    static synchronized Drawable get(String key) {
        return key == null ? null : CACHE.get(key);
    }

    static synchronized void put(String key, Drawable d) {
        if (key != null && d != null) {
            CACHE.put(key, d);
        }
    }

    /** 宿主换了新图（比如息屏期间拿到占位图、亮屏后才拿到真头像）时作废这一条。 */
    static synchronized void remove(String key) {
        if (key != null) {
            CACHE.remove(key);
        }
    }

    static synchronized void clear() {
        CACHE.clear();
    }

    static synchronized int size() {
        return CACHE.size();
    }
}
