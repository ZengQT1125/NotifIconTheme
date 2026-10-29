package com.littletater.notificontheme;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置界面，miuix 风格：大标题 TopAppBar + 12dp 边距圆角卡片 + 56dp 行高，
 * 卡片里不放分类小字标题（MIUIX 规范）。
 *
 * 系统栏跟着深浅色走：浅色深图标、深色白图标，内容按 insets 让开状态栏，
 * 不再出现白底 + 白图标看不见的情况。
 */
public class SettingsActivity extends Activity {

    private static final String[] THEME_ACTIONS = {
            "org.adw.launcher.THEMES",
            "org.adwfreak.launcher.THEMES",
            "com.gau.go.launcherex.theme",
            "com.anddoes.launcher.THEME",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.bb.launcher.theme"
    };

    private static final String[] PREVIEW_PKGS = {
            "com.tencent.mm", "com.tencent.mobileqq", "com.eg.android.AlipayGphone",
            "com.taobao.taobao", "com.ss.android.ugc.aweme"
    };

    private final List<String> packs = new ArrayList<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private final Map<String, Drawable> previewCache = new HashMap<>();
    private String selectedPack = "";

    private Switch enableSwitch;
    private Switch shadeSwitch;
    private Switch lockSwitch;
    private Switch avatarSwitch;
    private Switch debugSwitch;
    private TextView avatarPacksSummary;
    private SeekBar scaleBar;
    private TextView scaleValue;
    private LinearLayout packList;

    private boolean dark;
    private int colorBg;
    private int colorCard;
    private int colorText;
    private int colorSecond;
    private int colorDivider;
    private int colorAccent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 每次打开都把配置镜像进 Settings.Global：SystemUI 那边不依赖本进程，本进程会被省电策略冻结
        try {
            ConfigStore.writeMirror(this, ConfigStore.fromPrefs(this));
        } catch (Throwable ignored) {
        }
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        colorBg = dark ? 0xFF000000 : 0xFFF7F7F7;
        colorCard = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        colorText = dark ? 0xFFFFFFFF : 0xE6000000;
        colorSecond = dark ? 0xFF98989F : 0xFF8A8A8E;
        colorDivider = dark ? 0x1FFFFFFF : 0x12000000;
        colorAccent = dark ? 0xFF5B9DFF : 0xFF3482FF;

        getWindow().setBackgroundDrawable(new ColorDrawable(colorBg));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 大标题（miuix TopAppBar：上面小字 app 名，下面大标题）
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(6), dp(24), dp(14));
        TextView appName = new TextView(this);
        appName.setText("通知图标跟随主题");
        appName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        appName.setTextColor(colorSecond);
        header.addView(appName);
        TextView bigTitle = new TextView(this);
        bigTitle.setText("通知图标");
        bigTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        bigTitle.setTextColor(colorText);
        bigTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        bigTitle.setPadding(0, dp(2), 0, 0);
        header.addView(bigTitle);
        root.addView(header);

        // ---- 开关卡片
        LinearLayout switches = card();
        enableSwitch = switchRow(switches, "启用模块", "关掉立刻恢复系统默认图标", true);
        divider(switches);
        shadeSwitch = switchRow(switches, "通知中心", "通知卡片左侧图标跟随主题", true);
        divider(switches);
        lockSwitch = switchRow(switches, "锁屏与锁屏岛", "锁屏通知与胶囊卡片跟随主题", true);
        divider(switches);
        avatarSwitch = switchRow(switches, "个人头像角标",
                "有头像的通知显示头像 + 主题图标角标", false);
        divider(switches);
        avatarPacksSummary = linkRow(switches, "头像白名单", "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AvatarWhitelistActivity.class));
            }
        });
        divider(switches);
        debugSwitch = switchRow(switches, "诊断日志",
                "排查问题用：往 LSPosed 日志打横幅视图树与头像判定过程", false);
        root.addView(switches);

        // ---- 图标包卡片
        LinearLayout packCard = card();
        packList = new LinearLayout(this);
        packList.setOrientation(LinearLayout.VERTICAL);
        packCard.addView(packList);
        root.addView(packCard);

        // ---- 缩放卡片
        LinearLayout scaleCard = card();
        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setOrientation(LinearLayout.HORIZONTAL);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        scaleRow.setPadding(dp(16), dp(14), dp(16), dp(2));
        TextView scaleTitle = new TextView(this);
        scaleTitle.setText("图标缩放");
        scaleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        scaleTitle.setTextColor(colorText);
        scaleRow.addView(scaleTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scaleValue = new TextView(this);
        scaleValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        scaleValue.setTextColor(colorSecond);
        scaleRow.addView(scaleValue);
        scaleCard.addView(scaleRow);

        scaleBar = new SeekBar(this);
        scaleBar.setMax(40);
        scaleBar.setPadding(dp(16), 0, dp(16), dp(12));
        scaleBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scaleValue.setText(String.format("%.2f", 0.6f + progress / 100f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        scaleCard.addView(scaleBar);
        root.addView(scaleCard);

        // ---- 动作卡片
        LinearLayout actions = card();
        actionRow(actions, "保存", colorAccent, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        divider(actions);
        actionRow(actions, "重启系统界面", colorAccent, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartSystemUi();
            }
        });
        root.addView(actions);

        TextView tip = new TextView(this);
        tip.setText("保存后 5 秒内自动生效。重启系统界面只重启 SystemUI（屏幕黑一下），不重启手机。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(colorSecond);
        tip.setPadding(dp(24), dp(14), dp(24), 0);
        root.addView(tip);

        applyInsets(scroll, root);
        setContentView(scroll);
        // 系统栏外观必须等 decorView 存在之后再设：onCreate 里直接 getInsetsController()
        // 会拿到 null 的 mDecor 直接 NPE（这就是上一版一开就闪退的原因）。
        applySystemBars();
        loadPacks();
        loadState();
    }

    // ---------------------------------------------------------------- 系统栏 / insets

    private void applySystemBars() {
        try {
            Window w = getWindow();
            View dv = w.getDecorView();
            if (dv == null) {
                return;
            }
            int flags = dv.getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                if (dark) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            dv.setSystemUiVisibility(flags);

            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = dv.getWindowInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(dark ? 0 : mask, mask);
                }
            }
        } catch (Throwable ignored) {
            // 某些 ROM 的 DecorView 拿不到 controller，主题里的 windowLightStatusBar 兜底
        }
    }

    private void applyInsets(final ScrollView scroll, final LinearLayout root) {
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                root.setPadding(0, top + dp(10), 0, bottom + dp(28));
                return insets;
            }
        });
        scroll.requestApplyInsets();
    }

    // ---------------------------------------------------------------- 状态

    private void loadState() {
        SharedPreferences sp = ConfigStore.prefs(this);
        enableSwitch.setChecked(sp.getBoolean(ConfigStore.KEY_ENABLED, true));
        shadeSwitch.setChecked(sp.getBoolean(ConfigStore.KEY_SHADE, true));
        lockSwitch.setChecked(sp.getBoolean(ConfigStore.KEY_LOCK, true));
        avatarSwitch.setChecked(sp.getBoolean(ConfigStore.KEY_AVATAR, false));
        debugSwitch.setChecked(sp.getBoolean(ConfigStore.KEY_DEBUG, false));
        selectedPack = sp.getString(ConfigStore.KEY_PACK, "");
        float scale = sp.getFloat(ConfigStore.KEY_SCALE, 0.90f);
        scaleBar.setProgress(Math.round((scale - 0.6f) * 100f));
        scaleValue.setText(String.format("%.2f", scale));
        rebuildPackRows();
        updatePacksSummary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePacksSummary();
    }

    /** 白名单行副标题：只数本机装了的包，和白名单页面的口径一致。 */
    private void updatePacksSummary() {
        if (avatarPacksSummary == null) {
            return;
        }
        String raw = ConfigStore.prefs(this).getString(ConfigStore.KEY_AVATAR_PACKS, "");
        java.util.Set<String> parsed = ConfigStore.parsePacks(raw);
        boolean isDefault = parsed.isEmpty();
        java.util.Set<String> source = isDefault
                ? new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
                        IconPackSource.DEFAULT_AVATAR_PACKS))
                : parsed;
        int n = installedCount(source);
        avatarPacksSummary.setText(isDefault
                ? "默认 " + n + " 个已安装 IM 应用，点进去自己挑"
                : "已勾选 " + n + " 个应用，点进去调整");
    }

    /** 与 AvatarWhitelistActivity 的 entryOf 判定保持一致：getApplicationInfo 成功才算装了。 */
    private int installedCount(java.util.Collection<String> packs) {
        PackageManager pm = getPackageManager();
        int count = 0;
        for (String pkg : packs) {
            try {
                pm.getApplicationInfo(pkg, 0);
                count++;
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    private void save() {
        SharedPreferences.Editor ed = ConfigStore.prefs(this).edit();
        ed.putBoolean(ConfigStore.KEY_ENABLED, enableSwitch.isChecked());
        ed.putBoolean(ConfigStore.KEY_SHADE, shadeSwitch.isChecked());
        ed.putBoolean(ConfigStore.KEY_LOCK, lockSwitch.isChecked());
        ed.putBoolean(ConfigStore.KEY_AVATAR, avatarSwitch.isChecked());
        ed.putBoolean(ConfigStore.KEY_DEBUG, debugSwitch.isChecked());
        ed.putString(ConfigStore.KEY_PACK, selectedPack);
        ed.putFloat(ConfigStore.KEY_SCALE, 0.6f + scaleBar.getProgress() / 100f);
        ed.apply();
        ConfigStore.writeMirror(this, ConfigStore.fromPrefs(this));
        Toast.makeText(this, "已保存，5 秒内生效", Toast.LENGTH_SHORT).show();
    }

    private void restartSystemUi() {
        save();
        boolean ok = false;
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "killall com.android.systemui"});
            ok = pr.waitFor() == 0;
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, ok ? "系统界面已重启" : "没有 root 权限，请手动重启系统界面",
                ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- 图标包列表

    private void loadPacks() {
        PackageManager pm = getPackageManager();
        Map<String, String> found = new LinkedHashMap<>();
        for (String action : THEME_ACTIONS) {
            try {
                List<ResolveInfo> list = pm.queryIntentActivities(new Intent(action), 0);
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                        continue;
                    }
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || found.containsKey(pkg)) {
                        continue;
                    }
                    found.put(pkg, ri.loadLabel(pm).toString());
                }
            } catch (Throwable ignored) {
            }
        }
        packs.clear();
        labels.clear();
        packs.add("");
        labels.put("", "自动");
        for (Map.Entry<String, String> e : found.entrySet()) {
            packs.add(e.getKey());
            labels.put(e.getKey(), e.getValue());
        }
    }

    private void rebuildPackRows() {
        packList.removeAllViews();
        for (int i = 0; i < packs.size(); i++) {
            if (i > 0) {
                divider(packList);
            }
            packList.addView(packRow(packs.get(i), labels.get(packs.get(i))));
        }
    }

    private View packRow(final String pack, String label) {
        boolean selected = pack.equals(selectedPack);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedPack = pack;
                rebuildPackRows();
            }
        });

        ImageView icon = new ImageView(this);
        Drawable d = preview(pack);
        if (d != null) {
            icon.setImageDrawable(d);
        } else {
            icon.setBackground(roundRect(dp(11), dark ? 0xFF2C2C2E : 0xFFEDEDF0));
        }
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconParams.rightMargin = dp(14);
        row.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(colorText);
        text.addView(name);
        TextView id = new TextView(this);
        id.setText(pack.isEmpty() ? "第一个检测到的图标包" : pack);
        id.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        id.setTextColor(colorSecond);
        id.setPadding(0, dp(2), 0, 0);
        text.addView(id);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = new TextView(this);
        check.setText(selected ? "✓" : "");
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        check.setTextColor(colorAccent);
        row.addView(check);
        return row;
    }

    /** 取图标包里微信/QQ/支付宝…任意一个的图标当预览。 */
    private Drawable preview(String pack) {
        if (previewCache.containsKey(pack)) {
            return previewCache.get(pack);
        }
        Drawable out = null;
        if (!pack.isEmpty()) {
            try {
                Resources res = getPackageManager().getResourcesForApplication(pack);
                InputStream in = res.getAssets().open("appfilter.xml");
                XmlPullParser parser = android.util.Xml.newPullParser();
                parser.setInput(in, null);
                int ev;
                while ((ev = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (ev != XmlPullParser.START_TAG || !"item".equals(parser.getName())) {
                        continue;
                    }
                    String component = parser.getAttributeValue(null, "component");
                    String drawable = parser.getAttributeValue(null, "drawable");
                    if (component == null || drawable == null) {
                        continue;
                    }
                    String pkg = packageOf(component);
                    if (pkg == null || !isPreviewPkg(pkg)) {
                        continue;
                    }
                    int id = res.getIdentifier(drawable, "drawable", pack);
                    if (id != 0) {
                        out = res.getDrawable(id, null);
                        break;
                    }
                }
                in.close();
            } catch (Throwable ignored) {
            }
        }
        previewCache.put(pack, out);
        return out;
    }

    private static boolean isPreviewPkg(String pkg) {
        for (String p : PREVIEW_PKGS) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

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
        return s.isEmpty() || s.charAt(0) == ':' ? null : s;
    }

    // ---------------------------------------------------------------- 视图工具

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(dp(16), colorCard));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);
        return card;
    }

    private Switch switchRow(LinearLayout parent, String title, String summary, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(colorText);
        text.addView(t);
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        s.setTextColor(colorSecond);
        s.setPadding(0, dp(2), 0, 0);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(def);
        sw.setShowText(false);
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    /** 可点进二级页面的行，返回副标题 TextView 方便回填数量。 */
    private TextView linkRow(LinearLayout parent, String title, String summary,
                             View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setOnClickListener(listener);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(colorText);
        text.addView(t);
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        s.setTextColor(colorSecond);
        s.setPadding(0, dp(2), 0, 0);
        text.addView(s);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        arrow.setTextColor(colorSecond);
        row.addView(arrow);

        parent.addView(row);
        return s;
    }

    private void actionRow(LinearLayout parent, String title, int color,
                           View.OnClickListener listener) {
        TextView row = new TextView(this);
        row.setText(title);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setTextColor(color);
        row.setGravity(Gravity.CENTER);
        row.setMinHeight(dp(52));
        row.setClickable(true);
        row.setOnClickListener(listener);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void divider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(colorDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(16);
        parent.addView(v, lp);
    }

    private GradientDrawable roundRect(float radius, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setColor(color);
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
