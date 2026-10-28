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
    /** 配置镜像：Settings.Global 的 key。SystemUI 直接读，不依赖会被省电策略冻结的模块进程。 */
    public static final String MIRROR_KEY = "spud_icon_theme_cfg";

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
        State s = readOrNull(ctx);
        return s == null ? new State() : s;
    }

    /**
     * 读失败返回 null（provider 进程还没起来、刚装完被 force-stop 等）。
     * 调用方拿到 null 必须重试，不能把默认值当结果缓存下来，否则 avatar 会被永久钉成 false。
     */
    public static State readOrNull(Context ctx) {
        State m = readMirror(ctx);
        if (m != null) {
            return m;
        }
        return readProvider(ctx);
    }

    /** 模块自己的进程读自己的 SharedPreferences。 */
    public static State fromPrefs(Context ctx) {
        State s = new State();
        SharedPreferences p = prefs(ctx);
        s.enabled = p.getBoolean(KEY_ENABLED, true);
        s.pack = p.getString(KEY_PACK, "");
        s.scale = p.getFloat(KEY_SCALE, 0.90f);
        s.shade = p.getBoolean(KEY_SHADE, true);
        s.debug = p.getBoolean(KEY_DEBUG, false);
        s.avatar = p.getBoolean(KEY_AVATAR, false);
        s.lock = p.getBoolean(KEY_LOCK, true);
        s.avatarPacks = p.getString(KEY_AVATAR_PACKS, "");
        return s;
    }

    /** 把当前配置镜像进 Settings.Global，需要 WRITE_SECURE_SETTINGS（adb 一次性授予）。 */
    public static boolean writeMirror(Context ctx, State s) {
        try {
            String v = "v1|enabled=" + (s.enabled ? 1 : 0)
                    + "|pack=" + s.pack
                    + "|scale=" + s.scale
                    + "|shade=" + (s.shade ? 1 : 0)
                    + "|debug=" + (s.debug ? 1 : 0)
                    + "|avatar=" + (s.avatar ? 1 : 0)
                    + "|lock=" + (s.lock ? 1 : 0)
                    + "|packs=" + s.avatarPacks.replace('\n', ',');
            return android.provider.Settings.Global.putString(
                    ctx.getContentResolver(), MIRROR_KEY, v);
        } catch (Throwable t) {
            return false;
        }
    }

    public static State readMirror(Context ctx) {
        try {
            String v = android.provider.Settings.Global.getString(
                    ctx.getContentResolver(), MIRROR_KEY);
            if (v == null || !v.startsWith("v1|")) {
                return null;
            }
            State s = new State();
            for (String kv : v.split(java.util.regex.Pattern.quote("|"))) {
                int i = kv.indexOf('=');
                if (i <= 0) {
                    continue;
                }
                String k = kv.substring(0, i);
                String val = kv.substring(i + 1);
                if ("enabled".equals(k)) {
                    s.enabled = "1".equals(val);
                } else if ("pack".equals(k)) {
                    s.pack = val;
                } else if ("scale".equals(k)) {
                    s.scale = Float.parseFloat(val);
                } else if ("shade".equals(k)) {
                    s.shade = "1".equals(val);
                } else if ("debug".equals(k)) {
                    s.debug = "1".equals(val);
                } else if ("avatar".equals(k)) {
                    s.avatar = "1".equals(val);
                } else if ("lock".equals(k)) {
                    s.lock = "1".equals(val);
                } else if ("packs".equals(k)) {
                    s.avatarPacks = val.replace(',', '\n');
                }
            }
            if (s.scale <= 0.3f || s.scale > 1.0f) {
                s.scale = 0.90f;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    public static State readProvider(Context ctx) {
        State s = new State();
        Cursor c = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            c = cr.query(STATE_URI, null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            s.enabled = c.getInt(0) != 0;
            s.pack = c.getString(1) == null ? "" : c.getString(1);
            s.scale = c.getFloat(2);
            s.shade = c.getInt(3) != 0;
            s.debug = c.getInt(4) != 0;
            s.avatar = c.getInt(5) != 0;
            s.lock = c.getInt(6) != 0;
            s.avatarPacks = c.getString(7) == null ? "" : c.getString(7);
        } catch (Throwable t) {
            return null;
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
