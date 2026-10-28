package com.littletater.notificontheme;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 侧：拦住 NotificationManagerService 投递前的图片裁剪。
 *
 * SystemUI 收到的是 {@link Notification#maybeCloneStrippedForDelivery} 之后的副本，
 * 里面 android.reduced.images=true，大图（largeIcon / oplus_small_icon / messagingUser 头像）
 * 全被扒掉，只剩 4 个基础键。要保住头像只能在裁剪发生的地方放行。
 *
 * 白名单与"头像 + 角标"开关走模块自己的 ConfigProvider，和 SystemUI 侧同一份配置。
 */
final class FrameworkHooks {

    private static final String[] DEBUG_DUMP_PACKAGES = {"com.ss.android.ugc.aweme"};

    private static Context sCtx;

    private FrameworkHooks() {
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> notif = XposedHelpers.findClass("android.app.Notification", cl);
            XposedBridge.hookAllMethods(notif, "maybeCloneStrippedForDelivery", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object n = param.args.length > 0 ? param.args[0] : null;
                    if (n == null) {
                        return;
                    }
                    dumpIfWanted(n);
                    if (keep(n)) {
                        param.setResult(n);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(notif, "reduceImageSizes", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && param.args[0] instanceof Context) {
                                sCtx = (Context) param.args[0];
                            }
                            if (keep(param.thisObject)) {
                                param.setResult(null);
                            }
                        }
                    });
            IconPackSource.log("framework hooks installed (system_server)");
        } catch (Throwable t) {
            IconPackSource.log("framework hook failed: " + t);
        }
    }

    /** 白名单内的包保留原图；读不到配置就按系统默认裁剪，不冒险。 */
    private static boolean keep(Object notif) {
        try {
            String pkg = packageOf(notif);
            if (pkg == null || pkg.isEmpty()) {
                return false;
            }
            Context ctx = ctx();
            if (ctx == null) {
                return false;
            }
            ConfigStore.State st = ConfigStore.readOrNull(ctx);
            if (st == null || !st.enabled || !st.avatar) {
                return false;
            }
            java.util.Set<String> packs = ConfigStore.parsePacks(st.avatarPacks);
            if (!packs.isEmpty()) {
                return packs.contains(pkg);
            }
            for (String p : IconPackSource.DEFAULT_AVATAR_PACKS) {
                if (p.equals(pkg)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Context ctx() {
        if (sCtx != null) {
            return sCtx;
        }
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app instanceof Context) {
                sCtx = (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return sCtx;
    }

    private static String packageOf(Object notif) {
        try {
            Bundle extras = (Bundle) XposedHelpers.callMethod(notif, "getExtras");
            if (extras == null) {
                return null;
            }
            android.content.pm.ApplicationInfo ai =
                    extras.getParcelable("android.appInfo");
            return ai == null ? null : ai.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只给排查用的包落一张 PNG 到 /data/local/tmp，方便确认推送里到底是头像还是应用 logo。 */
    private static void dumpIfWanted(Object notif) {
        try {
            String pkg = packageOf(notif);
            if (pkg == null) {
                return;
            }
            boolean wanted = false;
            for (String p : DEBUG_DUMP_PACKAGES) {
                if (p.equals(pkg)) {
                    wanted = true;
                    break;
                }
            }
            if (!wanted) {
                return;
            }
            Bundle extras = (Bundle) XposedHelpers.callMethod(notif, "getExtras");
            if (extras == null) {
                return;
            }
            Object raw = extras.get("oplus_small_icon");
            Drawable d = null;
            if (raw instanceof android.graphics.drawable.Icon) {
                Context ctx = ctx();
                if (ctx != null) {
                    d = ((android.graphics.drawable.Icon) raw).loadDrawable(ctx);
                }
            } else if (raw instanceof Drawable) {
                d = (Drawable) raw;
            } else if (raw instanceof Bitmap) {
                d = new BitmapDrawable(null, (Bitmap) raw);
            }
            if (!(d instanceof BitmapDrawable)) {
                return;
            }
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm == null) {
                return;
            }
            File dir = new File("/data/local/tmp");
            File out = new File(dir, "spud_avatar_" + pkg + "_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(out);
            bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            IconPackSource.log("dumped " + out.getAbsolutePath() + " " + bm.getWidth() + "x"
                    + bm.getHeight());
        } catch (Throwable t) {
            IconPackSource.log("dump failed: " + t);
        }
    }
}
