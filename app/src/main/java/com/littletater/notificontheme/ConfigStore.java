package com.littletater.notificontheme;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

/**
 * 配置读写。模块进程写 SharedPreferences，SystemUI 进程通过 ConfigProvider 读，
 * 因为 Android 10+ 已经不允许跨进程直接读对方 shared_prefs 目录。
 */
public final class ConfigStore {

    public static final String AUTHORITY = "com.littletater.notificontheme.config";
    public static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");
    public static final String PREFS = "cfg";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_PACK = "pack";
    public static final String KEY_SCALE = "scale";
    public static final String KEY_SHADE = "shade";
    public static final String KEY_DEBUG = "debug";
    public static final String KEY_AVATAR = "avatar";
    public static final String KEY_LOCK = "lock";
    public static final String KEY_AVATAR_PACKS = "avatar_packs";

    private ConfigStore() {
    }

    /** "com.tencent.mm\ncom.tencent.mobileqq" -> Set。空串返回空集合。 */
    public static java.util.LinkedHashSet<String> parsePacks(String raw) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("[\n,]")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public static String joinPacks(java.util.Collection<String> packs) {
        StringBuilder sb = new StringBuilder();
        for (String p : packs) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** SystemUI 进程侧的读取结果。读取失败时 pack 为空串，由调用方自动探测。 */
    public static final class State {
        public boolean enabled = true;
        public String pack = "";
        public float scale = 0.90f;
        public boolean shade = true;
        public boolean debug = false;
        public boolean avatar = false;
        public boolean lock = true;
        public String avatarPacks = "";

        public String signature() {
            return pack + "|" + scale + "|" + avatar + "|" + lock + "|" + shade + "|" + debug
                    + "|" + avatarPacks;
        }
    }

    public static State readFromProvider(Context ctx) {
        State s = new State();
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            c = cr.query(STATE_URI, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                s.enabled = c.getInt(0) != 0;
                s.pack = c.getString(1) == null ? "" : c.getString(1);
                s.scale = c.getFloat(2);
                s.shade = c.getInt(3) != 0;
                s.debug = c.getInt(4) != 0;
                s.avatar = c.getInt(5) != 0;
                s.lock = c.getInt(6) != 0;
                s.avatarPacks = c.getString(7) == null ? "" : c.getString(7);
            }
        } catch (Throwable ignored) {
            // provider 还没装上 / 被停用，走自动探测
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (s.scale <= 0.3f || s.scale > 1.0f) {
            s.scale = 0.90f;
        }
        return s;
    }
}
