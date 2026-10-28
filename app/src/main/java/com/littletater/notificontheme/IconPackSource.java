package com.littletater.notificontheme;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图标包解析器：appfilter.xml -> {包名: drawable 名}，再按名字取图标包里的 drawable。
 * 只跑在 SystemUI 进程里。
 *
 * 配置读 5 秒缓存一次，签名（包名/缩放/开关）变了就重建索引，
 * 这样在模块界面里改完设置不用等 SystemUI 重启也能生效。
 */
final class IconPackSource {

    /** 图标包通用的桌面主题 intent action。 */
    private static final String[] THEME_ACTIONS = {
            "org.adw.launcher.THEMES",
            "org.adwfreak.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.bb.launcher.theme"
    };

    private static final Object NULL_MARK = new Object();
    private static final long CONFIG_TTL_MS = 5_000L;

    /**
     * 白名单为空时的默认集合：只放真正会带人像/联系人头像的 IM 类应用。
     * 天猫、淘宝、京东这类把促销图塞进 largeIcon 的包不在里面，避免"广告大图 + 角标"。
     */
    static final String[] DEFAULT_AVATAR_PACKS = {
            "com.tencent.mm",             // 微信
            "com.tencent.mobileqq",       // QQ
            "com.tencent.wework",         // 企业微信
            "com.alibaba.android.rimet",  // 钉钉
            "com.ss.android.lark",        // 飞书
            "org.telegram.messenger",     // Telegram
            "com.whatsapp",               // WhatsApp
            "com.instagram.android",      // Instagram
            "com.discord",                // Discord
            "com.android.mms",            // 短信
            "com.coloros.mms"             // ColorOS 短信
    };

    private static Context sAppCtx;
    private static IconPackSource sInstance;
    private static String sSignature;
    private static ConfigStore.State sState;
    private static long sStateAt;

    private final String packPkg;
    private final Resources packRes;
    private final float scale;
    private final boolean debug;
    private final boolean avatar;
    private final boolean lock;
    private final java.util.Set<String> avatarPacks;
    private final HashMap<String, String> appFilter = new HashMap<>();
    private final HashMap<String, Object> drawableCache = new HashMap<>();

    private IconPackSource(String packPkg, Resources res, ConfigStore.State st) {
        this.packPkg = packPkg;
        this.packRes = res;
        this.scale = st.scale;
        this.debug = st.debug;
        this.avatar = st.avatar;
        this.lock = st.lock;
        this.avatarPacks = ConfigStore.parsePacks(st.avatarPacks);
    }

    static void init(Context ctx) {
        // attach 阶段 getApplicationContext() 还是 null，别直接拿来用
        Context app = ctx.getApplicationContext();
        sAppCtx = app != null ? app : ctx;
        sInstance = null;
        sSignature = null;
        sState = null;
        sStateAt = 0L;
    }

    static Context appContext() {
        return sAppCtx;
    }

    static void log(String msg) {
        try {
            de.robv.android.xposed.XposedBridge.log("[SpudIcon] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 开关一律读最新配置，不要用构造时的快照：安装后第一次 provider 查询可能失败，
     * 快照会把 avatar 永久钉在 false 上，直到用户再动一次设置。
     */
    private static ConfigStore.State live() {
        if (sAppCtx == null) {
            return null;
        }
        try {
            return state();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否开了"头像 + 角标"。 */
    static boolean avatarEnabled() {
        ConfigStore.State st = live();
        return st != null && st.avatar && instance() != null;
    }

    /**
     * 这个包是否在白名单里。用户配了列表就只认列表，没配就认默认 IM 集合。
     * 天猫 / 淘宝 这类广告大图包默认进不来。
     */
    static boolean avatarAllowed(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        ConfigStore.State st = live();
        if (st == null || !st.avatar || instance() == null) {
            return false;
        }
        java.util.Set<String> packs = ConfigStore.parsePacks(st.avatarPacks);
        if (!packs.isEmpty()) {
            return packs.contains(pkg);
        }
        for (String p : DEFAULT_AVATAR_PACKS) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** 是否接管锁屏与锁屏岛。 */
    static boolean lockEnabled() {
        ConfigStore.State st = live();
        return st != null && st.lock && instance() != null;
    }

    static float scale() {
        ConfigStore.State st = live();
        return st == null ? 0.90f : st.scale;
    }

    static Drawable themedDrawable(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        try {
            IconPackSource s = instance();
            Drawable d = s == null ? null : s.get(pkg);
            if (!"com.android.systemui".equals(pkg) && sResolveCount.incrementAndGet() <= 10) {
                log("resolve " + pkg + " -> " + (d != null) + " (src=" + (s != null) + ")");
            }
            return d;
        } catch (Throwable t) {
            if (sErrorCount.incrementAndGet() <= 5) {
                log("resolve error: " + t);
            }
            return null;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger sResolveCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger sErrorCount =
            new java.util.concurrent.atomic.AtomicInteger();

    private static ConfigStore.State state() {
        long now = System.currentTimeMillis();
        if (sState != null && now - sStateAt < CONFIG_TTL_MS) {
            return sState;
        }
        sState = ConfigStore.readFromProvider(sAppCtx);
        sStateAt = now;
        return sState;
    }

    private static synchronized IconPackSource instance() {
        if (sAppCtx == null) {
            return null;
        }
        ConfigStore.State st = state();
        if (!st.enabled) {
            sInstance = null;
            sSignature = null;
            return null;
        }
        String pack = st.pack.isEmpty() ? autodetect() : st.pack;
        if (pack.isEmpty()) {
            sInstance = null;
            sSignature = null;
            return null;
        }
        String sig = pack + "|" + st.signature();
        if (sInstance != null && sig.equals(sSignature)) {
            return sInstance;
        }
        try {
            Resources res = sAppCtx.getPackageManager().getResourcesForApplication(pack);
            IconPackSource src = new IconPackSource(pack, res, st);
            src.loadAppFilter();
            log("pack=" + pack + " entries=" + src.appFilter.size()
                    + " scale=" + st.scale + " shade=" + st.shade
                    + " avatar=" + st.avatar + " lock=" + st.lock);
            sInstance = src;
            sSignature = sig;
            AvatarCache.clear();
        } catch (Throwable t) {
            log("load pack " + pack + " failed: " + t);
            sInstance = null;
            sSignature = null;
        }
        return sInstance;
    }

    /** 没配置时，从装了主题 intent 的包里挑第一个。 */
    private static String autodetect() {
        Set<String> found = new LinkedHashSet<>();
        for (String action : THEME_ACTIONS) {
            try {
                List<ResolveInfo> list = sAppCtx.getPackageManager()
                        .queryIntentActivities(new Intent(action), 0);
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                        found.add(ri.activityInfo.packageName);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        found.remove("com.littletater.notificontheme");
        return found.isEmpty() ? "" : found.iterator().next();
    }

    private void loadAppFilter() throws Exception {
        InputStream in = packRes.getAssets().open("appfilter.xml");
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, null);
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event != XmlPullParser.START_TAG) {
                continue;
            }
            String tag = parser.getName();
            if (!"item".equals(tag)) {
                continue;
            }
            String component = parser.getAttributeValue(null, "component");
            String drawable = parser.getAttributeValue(null, "drawable");
            if (component == null || drawable == null) {
                continue;
            }
            String pkg = packageOf(component);
            if (pkg != null && !pkg.isEmpty() && !appFilter.containsKey(pkg)) {
                appFilter.put(pkg, drawable);
            }
        }
        in.close();
    }

    /** ComponentInfo{com.tencent.mm/com.tencent.mm.ui.LauncherUI} / com.tencent.mm/... / com.tencent.mm */
    private static String packageOf(String component) {
        String s = component.trim();
        int lb = s.indexOf('{');
        if (lb >= 0) {
            int rb = s.indexOf('}', lb);
            s = rb > lb ? s.substring(lb + 1, rb) : s.substring(lb + 1);
        }
        int slash = s.indexOf('/');
        if (slash > 0) {
            s = s.substring(0, slash);
        }
        s = s.trim();
        if (s.isEmpty() || s.charAt(0) == ':') {
            return null;
        }
        return s;
    }

    private Drawable get(String pkg) {
        String name = appFilter.get(pkg);
        if (name == null) {
            return null;
        }
        Object cached = drawableCache.get(name);
        if (cached == NULL_MARK) {
            return null;
        }
        Drawable.ConstantState state;
        if (cached instanceof Drawable.ConstantState) {
            state = (Drawable.ConstantState) cached;
        } else {
            int id;
            try {
                id = packRes.getIdentifier(name, "drawable", packPkg);
            } catch (Throwable t) {
                id = 0;
            }
            if (id == 0) {
                drawableCache.put(name, NULL_MARK);
                if (debug) {
                    log("missing drawable " + name);
                }
                return null;
            }
            Drawable d = packRes.getDrawable(id, null);
            state = d == null ? null : d.getConstantState();
            if (state == null) {
                drawableCache.put(name, NULL_MARK);
                return null;
            }
            drawableCache.put(name, state);
        }
        Drawable fresh = state.newDrawable(packRes, null);
        if (fresh == null) {
            return null;
        }
        return new ThemedIconDrawable(fresh, scale);
    }
}
