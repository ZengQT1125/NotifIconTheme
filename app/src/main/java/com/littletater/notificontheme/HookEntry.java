package com.littletater.notificontheme;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.view.View;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口：只在 com.android.systemui 里干活。
 *
 * 状态栏 / 通知小图标
 *   1) StatusBarIconView.getIcon(StatusBarIcon)
 *   2) OplusStatusBarIconViewEx.reloadIconFromContext(int, Context, StatusBarIcon)  ← ColorOS 实际走这条
 *   3) setIconColor / setStaticDrawableColor / setTintAlpha / onDarkChanged —— 抗系统染色
 *
 * 通知中心卡片左侧 app 图标
 *   4) NotificationHeaderViewWrapper.onContentUpdated -> NotificationRowIconView(id=icon)
 *   5) NotificationRowIconView.setImageIcon + ImageView.setImageDrawable/setImageBitmap 回贴
 *   6) 横幅 HeadsUpTempViewCreator.onInflationFinished
 *
 * 锁屏 / 锁屏岛
 *   7) CapsuleNotificationCardView.getRoundedIcon(NotificationIconData)
 *   8) LockScreenNotificationIconData.getIcon()
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String SYSUI = "com.android.systemui";
    private static final String TAG_THEMED = "spud_themed";
    private static final String TAG_THEMED_DRAWABLE = "spud_themed_drawable";

    private static final String CAPSULE_CARD =
            "com.oplus.systemui.notification.lockscreen.notification.CapsuleNotificationCardView";
    private static final String CAPSULE_ICON_DATA =
            "com.oplus.systemui.notification.lockscreen.notification"
                    + ".CapsuleNotificationDataController$NotificationIconData";
    private static final String LOCK_SCREEN_ICON_DATA =
            "com.android.systemui.util.LockScreenNotificationIconData";

    private static boolean sHooked;
    private static final java.util.concurrent.atomic.AtomicInteger sIconErrors =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSUI.equals(lp.packageName)) {
            return;
        }
        IconPackSource.log("module loaded in " + lp.processName);
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                IconPackSource.init((Context) param.args[0]);
                                installHooks(lp.classLoader);
                            } catch (Throwable t) {
                                IconPackSource.log("install hooks failed: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            IconPackSource.log("attach hook failed: " + t);
        }
    }

    private static synchronized void installHooks(ClassLoader cl) {
        if (sHooked) {
            return;
        }
        sHooked = true;
        hookStatusBarIconView(cl);
        hookNotificationHeader(cl);
        hookLockScreen(cl);
    }

    // ---------------------------------------------------------------- 状态栏 / 通知小图标

    private static void hookStatusBarIconView(ClassLoader cl) {
        try {
            Class<?> viewClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.StatusBarIconView", cl);
            Class<?> iconClass = XposedHelpers.findClass(
                    "com.android.internal.statusbar.StatusBarIcon", cl);

            XposedHelpers.findAndHookMethod(viewClass, "getIcon", iconClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object statusBarIcon = param.args[0];
                        if (statusBarIcon == null) {
                            return;
                        }
                        Object pkgObj = XposedHelpers.getObjectField(statusBarIcon, "pkg");
                        String pkg = pkgObj instanceof String ? (String) pkgObj : null;
                        Drawable themed = IconPackSource.themedDrawable(pkg);
                        if (themed == null) {
                            XposedHelpers.removeAdditionalInstanceField(param.thisObject, TAG_THEMED);
                            return;
                        }
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, TAG_THEMED,
                                Boolean.TRUE);
                        XposedHelpers.setAdditionalInstanceField(param.thisObject,
                                TAG_THEMED_DRAWABLE, themed);
                        param.setResult(themed);
                    } catch (Throwable t) {
                        if (sIconErrors.incrementAndGet() <= 5) {
                            IconPackSource.log("getIcon hook error: " + t);
                        }
                    }
                }
            });
            IconPackSource.log("hooked StatusBarIconView.getIcon");

            // ColorOS 侧的图标加载扩展，通知图标走这条路径
            try {
                Class<?> ex = XposedHelpers.findClass(
                        "com.android.systemui.statusbar.OplusStatusBarIconViewEx", cl);
                XposedHelpers.findAndHookMethod(ex, "reloadIconFromContext", int.class, Context.class,
                        iconClass, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object statusBarIcon = param.args[2];
                                    if (statusBarIcon == null) {
                                        return;
                                    }
                                    Object pkgObj = XposedHelpers.getObjectField(statusBarIcon, "pkg");
                                    String pkg = pkgObj instanceof String ? (String) pkgObj : null;
                                    Drawable themed = IconPackSource.themedDrawable(pkg);
                                    if (themed != null) {
                                        param.setResult(themed);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                IconPackSource.log("hooked OplusStatusBarIconViewEx.reloadIconFromContext");
            } catch (Throwable t) {
                IconPackSource.log("oplus reloadIconFromContext hook failed: " + t);
            }

            XC_MethodHook skipTint = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getAdditionalInstanceField(param.thisObject, TAG_THEMED) != null) {
                        param.setResult(null);
                    }
                }
            };
            try {
                XposedHelpers.findAndHookMethod(viewClass, "setIconColor", int.class, boolean.class,
                        skipTint);
            } catch (Throwable t) {
                IconPackSource.log("setIconColor hook failed: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(viewClass, "setStaticDrawableColor", int.class,
                        skipTint);
            } catch (Throwable t) {
                IconPackSource.log("setStaticDrawableColor hook failed: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(viewClass, "setTintAlpha", float.class, skipTint);
            } catch (Throwable t) {
                IconPackSource.log("setTintAlpha hook failed: " + t);
            }
            // 换壁纸 / 亮暗切换会重新刷色，刷完把主题图标贴回去
            try {
                XposedHelpers.findAndHookMethod(viewClass, "onDarkChanged", java.util.ArrayList.class,
                        float.class, int.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object d = XposedHelpers.getAdditionalInstanceField(
                                        param.thisObject, TAG_THEMED_DRAWABLE);
                                if (d instanceof Drawable && param.thisObject instanceof ImageView) {
                                    ((ImageView) param.thisObject).setImageDrawable((Drawable) d);
                                }
                            }
                        });
            } catch (Throwable t) {
                IconPackSource.log("onDarkChanged hook failed: " + t);
            }
        } catch (Throwable t) {
            IconPackSource.log("StatusBarIconView hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 通知中心

    private static void hookNotificationHeader(ClassLoader cl) {
        Class<?> rowClass;
        try {
            rowClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", cl);
        } catch (Throwable t) {
            IconPackSource.log("no ExpandableNotificationRow: " + t);
            return;
        }

        // 通知中心卡片左侧 app 图标 = com.android.internal.widget.NotificationRowIconView(id=icon)
        // 它继承 ImageView，不经过 StatusBarIconView 的图标加载路径，所以在行内容更新后直接替换。
        try {
            Class<?> wrapper = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper",
                    cl);
            XposedHelpers.findAndHookMethod(wrapper, "onContentUpdated", rowClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args[0] instanceof View) {
                                    View row = (View) param.args[0];
                                    String pkg = pkgOfRow(param.args[0]);
                                    av("hdr " + pkg);
                                    applyRowIcon(row, pkg, avatarOfRow(row, pkg));
                                }
                            } catch (Throwable t) {
                                av("hdr err " + t);
                            }
                        }
                    });
            IconPackSource.log("hooked NotificationHeaderViewWrapper.onContentUpdated");
        } catch (Throwable t) {
            IconPackSource.log("NotificationHeaderViewWrapper hook failed: " + t);
        }

        // ColorOS 的分组/聚合通知走 OPLUS 扩展 wrapper，同样在内容更新后补一次贴图。
        try {
            Class<?> oplus = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.notification.row.wrapper.OplusNotificationHeaderViewWrapperExImp",
                    cl);
            XC_MethodHook oplusCb = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object rowObj = param.args[0];
                        if (rowObj instanceof View) {
                            View row = (View) rowObj;
                            String pkg = pkgOfRow(rowObj);
                            av("oplus " + pkg);
                            applyRowIcon(row, pkg, avatarOfRow(row, pkg));
                        }
                    } catch (Throwable t) {
                        av("oplus err " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(oplus, "proxyOnContentUpdated", rowClass, oplusCb);
            XposedHelpers.findAndHookMethod(oplus, "initBind", rowClass, oplusCb);
            IconPackSource.log("hooked OplusNotificationHeaderViewWrapperExImp");
        } catch (Throwable t) {
            IconPackSource.log("oplus wrapper hook failed: " + t);
        }

        // 图标是异步加载的，晚到的 setImageIcon / setImageDrawable / setImageBitmap 会覆盖我们贴的图，
        // 这里在 setter 之后按 tag 回贴。NotificationRowIconView 没重写这些方法，必须挂 ImageView 上的。
        try {
            Class<?> rowIconView = XposedHelpers.findClass(
                    "com.android.internal.widget.NotificationRowIconView", cl);
            XposedHelpers.findAndHookMethod(rowIconView, "setImageIcon", Icon.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            dropAvatarCache((View) param.thisObject);
                            // Icon 自带 resPackage，聚合/分组行也能拿到包名
                            String pkg = null;
                            Object icon = param.args[0];
                            if (icon instanceof Icon) {
                                try {
                                    pkg = ((Icon) icon).getResPackage();
                                } catch (Throwable ignored) {
                                }
                            }
                            applyRowIconToView((View) param.thisObject, pkg);
                        }
                    });
            IconPackSource.log("hooked NotificationRowIconView.setImageIcon");
        } catch (Throwable t) {
            IconPackSource.log("NotificationRowIconView hook failed: " + t);
        }

        hookImageViewSetters();
        hookHeadsUp(cl);
    }

    // 横幅（heads-up）：ColorOS 17 走 HeadsUpTempViewCreator 现 inflate 一条临时通知行，
    // 这条行不一定经过 NotificationHeaderViewWrapper.onContentUpdated，所以在 inflate 完成时就贴图。
    private static final java.util.concurrent.atomic.AtomicInteger sBannerDump =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 横幅的图标不是 NotificationRowIconView，类名各家不一样，
     * 所以不认类名：谁的图跟应用图标长得一样，就换成主题图标。
     */
    private static void replaceAppIconImages(View root, String pkg) {
        if (root == null || pkg == null || pkg.isEmpty()) {
            return;
        }
        try {
            replaceAppIconRecursive(root, pkg, 0, new int[]{40});
        } catch (Throwable t) {
            av("banner err " + t);
        }
    }

    private static void replaceAppIconRecursive(View v, String pkg, int depth, int[] budget) {
        if (v == null || depth > 8 || budget[0] <= 0) {
            return;
        }
        budget[0]--;
        if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            Drawable d = iv.getDrawable();
            if (d instanceof BitmapDrawable) {
                Bitmap bm = ((BitmapDrawable) d).getBitmap();
                Drawable themed = IconPackSource.themedDrawable(pkg);
                if (bm != null && themed != null && d != iv.getTag(TAG_BANNER_ICON)
                        && looksLikeAppIcon(bm, pkg, IconPackSource.appContext(), null)) {
                    sInReapply.set(true);
                    try {
                        iv.setImageDrawable(themed);
                        iv.setTag(TAG_BANNER_ICON, themed);
                    } catch (Throwable ignored) {
                    } finally {
                        sInReapply.set(false);
                    }
                    av("banner icon -> themed " + pkg);
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                replaceAppIconRecursive(g.getChildAt(i), pkg, depth + 1, budget);
            }
        }
    }

    /** 诊断用：把视图树打成一行一行的，只在打开「诊断日志」时调用。 */
    private static void dumpTree(View v, int depth) {
        if (v == null || depth > 8) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getSimpleName());
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                sb.append('#').append(v.getResources().getResourceEntryName(id));
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            sb.append(" drawable=").append(d == null ? "null" : d.getClass().getSimpleName());
        }
        av(sb.toString());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpTree(g.getChildAt(i), depth + 1);
            }
        }
    }

    private static void hookHeadsUp(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass(
                    "com.oplus.systemui.notification.headsup.nssl.HeadsUpTempViewCreator$init$1", cl);
            Class<?> entryClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry", cl);
            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.collection.render.NotifViewController", cl);
            XposedHelpers.findAndHookMethod(c, "onInflationFinished", entryClass, controllerClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String pkg = pkgOfEntry(param.args[0]);
                                View row = viewOfController(param.args[1]);
                                if (row == null) {
                                    row = viewOfEntry(param.args[0]);
                                }
                                if (row != null) {
                                    applyRowIcon(row, pkg, avatarOfEntry(param.args[0], pkg));
                                    replaceAppIconImages(row, pkg);
                                }
                                if (IconPackSource.debug() && sBannerDump.incrementAndGet() <= 2) {
                                    dumpTree(row, 0);
                                }
                            } catch (Throwable t) {
                                IconPackSource.log("headsup apply error: " + t);
                            }
                        }
                    });
            IconPackSource.log("hooked HeadsUpTempViewCreator.onInflationFinished");
        } catch (Throwable t) {
            IconPackSource.log("headsup hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 锁屏 / 锁屏岛

    private static void hookLockScreen(ClassLoader cl) {
        // 锁屏岛胶囊卡片：图标从 getRoundedIcon(NotificationIconData) 出
        try {
            Class<?> card = XposedHelpers.findClass(CAPSULE_CARD, cl);
            Class<?> iconData = XposedHelpers.findClass(CAPSULE_ICON_DATA, cl);
            XposedHelpers.findAndHookMethod(card, "getRoundedIcon", iconData, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!IconPackSource.lockEnabled() || param.args[0] == null) {
                            return;
                        }
                        String pkg = str(XposedHelpers.callMethod(param.args[0], "getPackageName"));
                        Drawable themed = IconPackSource.themedDrawable(pkg);
                        if (themed == null) {
                            return;
                        }
                        Drawable out = themed;
                        Object entry = XposedHelpers.callMethod(param.args[0], "getEntry");
                        Drawable avatar = avatarOfEntry(entry, pkg);
                        if (avatar != null) {
                            out = AvatarBadge.compose(IconPackSource.appContext(), avatar, themed,
                                    pkg, AvatarBadge.dp(IconPackSource.appContext(), 40));
                        }
                        param.setResult(out);
                    } catch (Throwable ignored) {
                    }
                }
            });
            IconPackSource.log("hooked CapsuleNotificationCardView.getRoundedIcon");
        } catch (Throwable t) {
            IconPackSource.log("capsule hook failed: " + t);
        }

        // 锁屏通知列表的图标数据对象：直接改 getIcon() 的返回值
        try {
            Class<?> ls = XposedHelpers.findClass(LOCK_SCREEN_ICON_DATA, cl);
            XposedHelpers.findAndHookMethod(ls, "getIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!IconPackSource.lockEnabled()) {
                            return;
                        }
                        String pkg = str(XposedHelpers.callMethod(param.thisObject, "getPackageName"));
                        Drawable themed = IconPackSource.themedDrawable(pkg);
                        if (themed != null) {
                            param.setResult(themed);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            IconPackSource.log("hooked LockScreenNotificationIconData.getIcon");
        } catch (Throwable t) {
            IconPackSource.log("lockscreen icon data hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 视图工具

    private static View viewOfController(Object controller) {
        for (String m : new String[]{"getView", "getRow"}) {
            try {
                Object o = XposedHelpers.callMethod(controller, m);
                if (o instanceof View) {
                    return (View) o;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static View viewOfEntry(Object entry) {
        try {
            Object o = XposedHelpers.callMethod(entry, "getRow");
            if (o instanceof View) {
                return (View) o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final int TAG_ROW_ICON = 0x5f0d1e01;
    /** 横幅里被我们换成主题图标的 ImageView（用 int key，别去动宿主自己的 tag）。 */
    private static final int TAG_BANNER_ICON = 0x5f0d1e02;
    private static final String ROW_ICON_CLASS = "com.android.internal.widget.NotificationRowIconView";
    private static final ThreadLocal<Boolean> sInReapply = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private static void hookImageViewSetters() {
        Class<?>[] types = {Drawable.class, android.graphics.Bitmap.class};
        String[] names = {"setImageDrawable", "setImageBitmap"};
        for (int i = 0; i < names.length; i++) {
            try {
                java.lang.reflect.Method m = ImageView.class.getDeclaredMethod(names[i], types[i]);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        if (v == null || sInReapply.get()) {
                            return;
                        }
                        Drawable incoming = param.args.length > 0 && param.args[0] instanceof Drawable
                                ? (Drawable) param.args[0] : null;

                        // 横幅这类被我们换过的位置：宿主又写了新图，贴回主题图标
                        Object bannerTag = v.getTag(TAG_BANNER_ICON);
                        if (bannerTag instanceof Drawable) {
                            if (incoming != bannerTag) {
                                putDrawable(v, (Drawable) bannerTag);
                            }
                            return;
                        }
                        if (!ROW_ICON_CLASS.equals(v.getClass().getName())) {
                            return;
                        }
                        // 宿主换了图：息屏时它先给占位图，亮屏后才把真头像写进来。
                        // 这时必须把头像缓存作废重算，否则一直显示那张占位图。
                        if (incoming != null && incoming != v.getTag(TAG_ROW_ICON)) {
                            dropAvatarCache(v);
                        }
                        applyRowIconToView(v, null);
                    }
                });
                IconPackSource.log("hooked ImageView." + names[i]);
            } catch (Throwable t) {
                IconPackSource.log("ImageView." + names[i] + " hook failed: " + t);
            }
        }
    }

    private static void putDrawable(View v, Drawable d) {
        sInReapply.set(true);
        try {
            ((ImageView) v).setImageDrawable(d);
        } catch (Throwable ignored) {
        } finally {
            sInReapply.set(false);
        }
    }

    /** 通知 key：缓存按 (包名|通知 key) 存，宿主换图时只作废这一条。 */
    private static String keyOfAncestorRow(View v) {
        try {
            View row = ancestorRow(v);
            if (row == null) {
                return null;
            }
            Object entry = XposedHelpers.callMethod(row, "getEntry");
            Object sbn = XposedHelpers.callMethod(entry, "getSbn");
            Object key = XposedHelpers.callMethod(sbn, "getKey");
            return key instanceof String ? (String) key : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dropAvatarCache(View v) {
        try {
            String pkg = pkgOfAncestorRow(v);
            String key = keyOfAncestorRow(v);
            if (pkg != null && key != null) {
                AvatarCache.remove(AvatarCache.key(pkg, key));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 纯白 / 全透明的占位图不算头像。 */
    private static boolean isBlank(Bitmap bm) {
        try {
            Bitmap s = Bitmap.createScaledBitmap(bm, 16, 16, true);
            int white = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int c = s.getPixel(x, y);
                    if (android.graphics.Color.alpha(c) < 16) {
                        white++;
                    } else if (android.graphics.Color.red(c) > 240
                            && android.graphics.Color.green(c) > 240
                            && android.graphics.Color.blue(c) > 240) {
                        white++;
                    }
                }
            }
            return white >= 236;
        } catch (Throwable t) {
            return false;
        }
    }


    /**
     * 兜底路径：不管哪条 wrapper 更新了行，只要 NotificationRowIconView#icon 被塞了新图，
     * 就在这里按"自身 Icon 的 resPackage → 祖先 ExpandableNotificationRow 的 entry"顺序取包名，
     * 重新贴主题图标（白名单内再叠头像 + 角标）。抖音这类推送通道的聚合行只走这条。
     */
    private static void applyRowIconToView(View v, String pkgHint) {
        if (v == null || !ROW_ICON_CLASS.equals(v.getClass().getName()) || !isAppIconId(v)) {
            return;
        }
        if (sInReapply.get()) {
            return;
        }
        String pkg = pkgHint == null || pkgHint.isEmpty() ? pkgOfAncestorRow(v) : pkgHint;
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        Drawable themed = IconPackSource.themedDrawable(pkg);
        if (themed == null) {
            return;
        }
        Drawable avatar = avatarOfAncestorRow(v, pkg);
        Drawable shown = themed;
        if (avatar != null) {
            shown = AvatarBadge.compose(IconPackSource.appContext(), avatar, themed, pkg, sizeOf(v));
        }
        if (((ImageView) v).getDrawable() == shown) {
            return;
        }
        sInReapply.set(true);
        try {
            ((ImageView) v).setImageDrawable(shown);
            v.setTag(TAG_ROW_ICON, shown);
        } catch (Throwable t) {
            av("view err " + t);
        } finally {
            sInReapply.set(false);
        }
    }

    private static View ancestorRow(View v) {
        android.view.ViewParent p = v == null ? null : v.getParent();
        while (p instanceof View) {
            View cur = (View) p;
            if (cur.getClass().getName().endsWith("ExpandableNotificationRow")) {
                return cur;
            }
            p = cur.getParent();
        }
        return null;
    }

    private static String pkgOfAncestorRow(View v) {
        View row = ancestorRow(v);
        return row == null ? null : pkgOfRow(row);
    }

    private static Drawable avatarOfAncestorRow(View v, String pkg) {
        View row = ancestorRow(v);
        return row == null ? null : avatarOfRow(row, pkg);
    }

    private static void applyRowIcon(View root, String pkg, Drawable avatar) {
        if (root == null || pkg == null) {
            return;
        }
        Drawable themed = IconPackSource.themedDrawable(pkg);
        av("row " + pkg + " themed=" + (themed != null) + " av=" + (avatar != null)
                + " allow=" + IconPackSource.avatarAllowed(pkg));
        if (themed == null) {
            return;
        }
        applyRowIconRecursive(root, themed, avatar, pkg, 0);
    }

    private static void applyRowIconRecursive(View v, Drawable themed, Drawable avatar, String pkg,
                                              int depth) {
        if (depth > 10) {
            return;
        }
        if (v instanceof ImageView && ROW_ICON_CLASS.equals(v.getClass().getName()) && isAppIconId(v)) {
            Drawable shown = themed;
            if (avatar != null) {
                Context ctx = IconPackSource.appContext();
                shown = AvatarBadge.compose(ctx, avatar, themed, pkg, sizeOf(v));
            }
            ((ImageView) v).setImageDrawable(shown);
            v.setTag(TAG_ROW_ICON, shown);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyRowIconRecursive(g.getChildAt(i), themed, avatar, pkg, depth + 1);
            }
        }
    }

    private static int sizeOf(View v) {
        if (v.getWidth() > 0) {
            return v.getWidth();
        }
        Context ctx = IconPackSource.appContext();
        return ctx == null ? 0 : AvatarBadge.dp(ctx, 28);
    }

    private static boolean isAppIconId(View v) {
        int id = v.getId();
        if (id == View.NO_ID) {
            return false;
        }
        try {
            return "icon".equals(v.getResources().getResourceEntryName(id));
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 数据取值

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String pkgOfEntry(Object entry) {
        try {
            Object sbn = XposedHelpers.callMethod(entry, "getSbn");
            return str(XposedHelpers.callMethod(sbn, "getPackageName"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pkgOfRow(Object row) {
        try {
            Object entry = XposedHelpers.callMethod(row, "getEntry");
            return pkgOfEntry(entry);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通知自带的头像。白名单（默认只放 IM 类）内才取，天猫/淘宝的广告大图进不来。 */
    private static Drawable avatarOfRow(Object row, String pkg) {
        if (row == null) {
            return null;
        }
        try {
            return avatarOfEntry(XposedHelpers.callMethod(row, "getEntry"), pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger sAvLog =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 头像链路诊断日志：只有打开「诊断日志」才写，且最多 200 条。 */
    private static void av(String msg) {
        if (!IconPackSource.debug() || sAvLog.incrementAndGet() > 200) {
            return;
        }
        IconPackSource.log("av " + msg);
    }

    private static Drawable avatarOfEntry(Object entry, String pkg) {
        if (entry == null || pkg == null || !IconPackSource.avatarAllowed(pkg)) {
            return null;
        }
        try {
            Object sbn = XposedHelpers.callMethod(entry, "getSbn");
            Object keyObj = XposedHelpers.callMethod(sbn, "getKey");
            String cacheKey = AvatarCache.key(pkg, keyObj instanceof String ? (String) keyObj : null);
            Drawable cached = AvatarCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Object notif = XposedHelpers.callMethod(sbn, "getNotification");
            // 没有真头像就不补：宁可只显示主题图标，也不要假头像
            Drawable out = avatarFromNotification(notif, pkg);
            av(pkg + " -> " + (out == null ? "null" : out.getClass().getSimpleName()));
            if (out != null) {
                AvatarCache.put(cacheKey, out);
            }
            return out;
        } catch (Throwable t) {
            av(pkg + " entry err " + t);
            return null;
        }
    }

    /**
     * 头像来源，按命中率排：
     *   1) getLargeIcon() —— app 自己发的图（微信/QQ 常见）
     *   2) MessagingStyle 的 Person 头像
     *   3) extras 里的位图 —— 走 opush 推送通道的 IM（抖音私信就是这条），
     *      推送里带的是发送者头像位图，没有 largeIcon、没有 MessagingStyle。
     *      跟应用图标比一下像素，像就说明是 logo，丢掉。
     */
    private static Drawable avatarFromNotification(Object notif, String pkg) {
        Context ctx = IconPackSource.appContext();
        if (ctx == null || notif == null) {
            return null;
        }
        try {
            Object large = XposedHelpers.callMethod(notif, "getLargeIcon");
            av(pkg + " large=" + (large == null ? "null" : large.getClass().getSimpleName()));
            if (large instanceof Icon) {
                Drawable d = ((Icon) large).loadDrawable(ctx);
                if (d instanceof BitmapDrawable && isBlank(((BitmapDrawable) d).getBitmap())) {
                    av(pkg + " large blank");
                    d = null;
                }
                if (d != null) {
                    return d;
                }
            }
        } catch (Throwable t) {
            av(pkg + " large err " + t);
        }
        android.os.Bundle extras = extrasOf(notif);
        if (extras == null) {
            av(pkg + " extras null");
            return null;
        }
        av(pkg + " keys=" + extras.keySet());
        try {
            Drawable d = personIcon(extras.getParcelable("android.messagingUser"), ctx);
            if (d != null) {
                return d;
            }
        } catch (Throwable t) {
            av(pkg + " msgUser err " + t);
        }
        try {
            android.os.Parcelable[] msgs = extras.getParcelableArray("android.messages");
            if (msgs != null && msgs.length > 0) {
                Object last = msgs[msgs.length - 1];
                if (last instanceof android.os.Bundle) {
                    Drawable d = personIcon(
                            ((android.os.Bundle) last).getParcelable("sender_person"), ctx);
                    if (d != null) {
                        return d;
                    }
                }
            }
        } catch (Throwable t) {
            av(pkg + " msgs err " + t);
        }
        return pushAvatar(extras, pkg, ctx, smallIconOf(notif));
    }

    /**
     * Notification.getExtras() 在 ColorOS 17 上被隐藏，XposedHelpers.callMethod 走 #exact 查找
     * 会直接抛 NoSuchMethodError（不查父类、不看隐藏标记）。这里按类层次自己找方法，
     * 再退到 extras 字段，最后退到 sbn.getNotification().extras 的 Bundle。
     */
    private static android.os.Bundle extrasOf(Object notif) {
        if (notif == null) {
            return null;
        }
        for (Class<?> c = notif.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getExtras");
                m.setAccessible(true);
                Object v = m.invoke(notif);
                if (v instanceof android.os.Bundle) {
                    return (android.os.Bundle) v;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                av("extras invoke err " + t);
            }
        }
        for (Class<?> c = notif.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("extras");
                f.setAccessible(true);
                Object v = f.get(notif);
                if (v instanceof android.os.Bundle) {
                    return (android.os.Bundle) v;
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 推送通道通知里翻头像：oplus_small_icon 优先，找不到就扫所有图标/头像型 extras。 */
    private static Drawable pushAvatar(android.os.Bundle extras, String pkg, Context ctx,
                                       Icon smallIcon) {
        Object primary = null;
        try {
            primary = extras.getParcelable("oplus_small_icon");
        } catch (Throwable t) {
            av(pkg + " oplus_small_icon err " + t);
        }
        Drawable d = bitmapAvatar(primary, pkg, ctx, "oplus_small_icon", smallIcon);
        if (d != null) {
            return d;
        }
        for (String key : extras.keySet()) {
            if ("oplus_small_icon".equals(key)) {
                continue;
            }
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (!(lower.contains("icon") || lower.contains("avatar") || lower.contains("head")
                    || lower.contains("portrait") || lower.contains("face"))) {
                continue;
            }
            Object raw;
            try {
                raw = extras.get(key);
            } catch (Throwable t) {
                continue;
            }
            d = bitmapAvatar(raw, pkg, ctx, key, smallIcon);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static Drawable bitmapAvatar(Object raw, String pkg, Context ctx, String key,
                                        Icon smallIcon) {
        if (raw == null) {
            return null;
        }
        try {
            Drawable d;
            if (raw instanceof Icon) {
                d = ((Icon) raw).loadDrawable(ctx);
            } else if (raw instanceof Drawable) {
                d = (Drawable) raw;
            } else if (raw instanceof Bitmap) {
                d = new BitmapDrawable(ctx.getResources(), (Bitmap) raw);
            } else {
                av(pkg + " " + key + " type=" + raw.getClass().getName());
                return null;
            }
            if (!(d instanceof BitmapDrawable)) {
                av(pkg + " " + key + " d=" + (d == null ? "null" : d.getClass().getSimpleName()));
                return null;
            }
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm == null) {
                return null;
            }
            boolean appLike = looksLikeAppIcon(bm, pkg, ctx, smallIcon);
            av(pkg + " " + key + " " + bm.getWidth() + "x" + bm.getHeight()
                    + " appIconLike=" + appLike);
            if (bm.getWidth() < 64 || bm.getWidth() != bm.getHeight()) {
                return null;
            }
            if (appLike) {
                return null;
            }
            return d;
        } catch (Throwable t) {
            av(pkg + " " + key + " err " + t);
            return null;
        }
    }


    /** 32x32 平均色签名比对：跟应用图标长得一样就不是头像。 */
    private static final Map<String, int[]> sAppIconSig = new HashMap<>();

    private static boolean looksLikeAppIcon(Bitmap bm, String pkg, Context ctx, Icon smallIcon) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bm, 32, 32, true);
            int[] sig = signature(small);
            int[] appSig = sAppIconSig.get(pkg);
            if (appSig == null) {
                Drawable appIcon = ctx.getPackageManager().getApplicationIcon(pkg);
                Bitmap appBm = toBitmap(appIcon, 32);
                appSig = appBm == null ? null : signature(appBm);
                if (appSig != null) {
                    sAppIconSig.put(pkg, appSig);
                }
            }
            if (appSig != null && similar(sig, appSig)) {
                return true;
            }
            // 主题包图标也可能是推送里塞的那张"应用图标"，一起当基准
            Drawable themed = IconPackSource.themedDrawable(pkg);
            Bitmap themedBm = toBitmap(themed, 32);
            if (themedBm != null && similar(sig, signature(themedBm))) {
                return true;
            }
            // 通知自己的 smallIcon（抖音 MCS 推送就是拿它当"头像"塞进来的）
            if (smallIcon != null) {
                Bitmap smBm = toBitmap(smallIcon.loadDrawable(ctx), 32);
                if (smBm != null && similar(sig, signature(smBm))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 32x32 亮度签名逐点平均差 < 24 视为同一张图。 */
    private static boolean similar(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff += Math.abs(a[i] - b[i]);
        }
        return diff / a.length < 24;
    }

    /** Notification.getSmallIcon() 同样不能用 Xposed 的 #exact 查找。 */
    private static Icon smallIconOf(Object notif) {
        if (notif == null) {
            return null;
        }
        for (Class<?> c = notif.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("getSmallIcon");
                m.setAccessible(true);
                Object v = m.invoke(notif);
                if (v instanceof Icon) {
                    return (Icon) v;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Bitmap toBitmap(Drawable d, int size) {
        if (d == null) {
            return null;
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        return out;
    }

    private static int[] signature(Bitmap bm) {
        int[] sig = new int[bm.getWidth() * bm.getHeight()];
        int i = 0;
        for (int y = 0; y < bm.getHeight(); y++) {
            for (int x = 0; x < bm.getWidth(); x++) {
                int p = bm.getPixel(x, y);
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                sig[i++] = (r + g + b) / 3;
            }
        }
        return sig;
    }


    private static Drawable personIcon(Object person, Context ctx) {
        if (person == null) {
            return null;
        }
        try {
            Object iconObj = XposedHelpers.callMethod(person, "getIcon");
            if (iconObj instanceof Icon) {
                return ((Icon) iconObj).loadDrawable(ctx);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
