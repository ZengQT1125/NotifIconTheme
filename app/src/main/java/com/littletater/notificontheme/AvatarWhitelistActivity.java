package com.littletater.notificontheme;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 头像白名单：只有勾上的应用才做"头像 + 主题图标角标"。
 *
 * 列表分两段：
 *   1) 已勾选的包（不管本机装没装都显示，没装的标"未安装"）—— 一进来就能看到白名单本身
 *   2) 其余已安装应用
 */
public class AvatarWhitelistActivity extends Activity {

    private static final String TAG = "SpudWhitelist";

    private static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        boolean installed;
    }

    private final List<AppEntry> installedApps = new ArrayList<>();
    private final Map<String, AppEntry> installedByPkg = new HashMap<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();

    private boolean dark;
    private int colorBg;
    private int colorCard;
    private int colorText;
    private int colorSecond;
    private int colorDivider;
    private int colorAccent;

    private AppAdapter adapter;
    private EditText search;
    private TextView countLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        colorBg = dark ? 0xFF000000 : 0xFFF7F7F7;
        colorCard = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        colorText = dark ? 0xFFFFFFFF : 0xE6000000;
        colorSecond = dark ? 0xFF98989F : 0xFF8A8A8E;
        colorDivider = dark ? 0x1FFFFFFF : 0x12000000;
        colorAccent = dark ? 0xFF5B9DFF : 0xFF3482FF;

        getWindow().setBackgroundDrawable(new ColorDrawable(colorBg));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorBg);

        // ---- 标题
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(18), dp(24), dp(10));

        TextView back = new TextView(this);
        back.setText("‹ 设置");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        back.setTextColor(colorAccent);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back);

        TextView bigTitle = new TextView(this);
        bigTitle.setText("头像白名单");
        bigTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        bigTitle.setTextColor(colorText);
        bigTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        bigTitle.setPadding(0, dp(4), 0, dp(2));
        header.addView(bigTitle);

        countLabel = new TextView(this);
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        countLabel.setTextColor(colorSecond);
        header.addView(countLabel);
        root.addView(header);

        // ---- 搜索框
        LinearLayout searchCard = new LinearLayout(this);
        searchCard.setBackground(roundRect(dp(12), colorCard));
        searchCard.setPadding(dp(14), dp(2), dp(14), dp(2));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        searchParams.leftMargin = dp(12);
        searchParams.rightMargin = dp(12);
        searchParams.topMargin = dp(6);
        searchParams.bottomMargin = dp(8);
        root.addView(searchCard, searchParams);

        search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setHintTextColor(colorSecond);
        search.setTextColor(colorText);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(0, 0, 0, 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                rebuildShown();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchCard.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        // ---- 列表
        ListView list = new ListView(this);
        list.setBackgroundColor(colorBg);
        list.setDivider(null);
        list.setPadding(dp(12), 0, dp(12), dp(24));
        list.setClipToPadding(false);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        applySystemBars();
        applyInsets(root);

        loadSelected();
        loadApps();
        rebuildShown();
    }

    /** 内容让开状态栏，否则"‹ 设置"会顶到时间上。 */
    private void applyInsets(final LinearLayout root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
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
                root.setPadding(0, top, 0, bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    @Override
    protected void onPause() {
        super.onPause();
        persist();
    }

    // ---------------------------------------------------------------- 数据

    private void loadSelected() {
        selected.clear();
        SharedPreferences sp = ConfigStore.prefs(this);
        Set<String> parsed = ConfigStore.parsePacks(
                sp.getString(ConfigStore.KEY_AVATAR_PACKS, ""));
        if (parsed.isEmpty()) {
            for (String p : IconPackSource.DEFAULT_AVATAR_PACKS) {
                selected.add(p);
            }
        } else {
            selected.addAll(parsed);
        }
        Log.i(TAG, "selected=" + selected.size() + " " + selected);
    }

    private void loadApps() {
        installedApps.clear();
        installedByPkg.clear();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            Log.w(TAG, "queryIntentActivities failed", t);
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null || ri.activityInfo.packageName == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = ri.loadLabel(pm).toString();
            e.installed = true;
            try {
                e.icon = ri.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            installedApps.add(e);
            installedByPkg.put(pkg, e);
        }
        final java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINA);
        Collections.sort(installedApps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return collator.compare(a.label, b.label);
            }
        });
        Log.i(TAG, "launcher apps=" + installedApps.size());
    }

    /** 已勾选包补全：只有本机装了的才返回，没装的不进列表。 */
    private AppEntry entryOf(String pkg) {
        AppEntry e = installedByPkg.get(pkg);
        if (e != null) {
            return e;
        }
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            AppEntry made = new AppEntry();
            made.pkg = pkg;
            made.label = getPackageManager().getApplicationLabel(ai).toString();
            made.icon = getPackageManager().getApplicationIcon(ai);
            made.installed = true;
            installedByPkg.put(pkg, made);
            return made;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void rebuildShown() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase();
        shown.clear();

        // 第一段：已勾选（只列本机装了的）
        List<AppEntry> pinned = new ArrayList<>();
        for (String pkg : selected) {
            AppEntry e = entryOf(pkg);
            if (e != null && matches(e, q)) {
                pinned.add(e);
            }
        }
        Collections.sort(pinned, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                if (a.installed != b.installed) {
                    return a.installed ? -1 : 1;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        shown.addAll(pinned);

        // 第二段：其余已安装应用
        for (AppEntry e : installedApps) {
            if (!selected.contains(e.pkg) && matches(e, q)) {
                shown.add(e);
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateCount();
    }

    private static boolean matches(AppEntry e, String q) {
        return q.isEmpty() || e.label.toLowerCase().contains(q) || e.pkg.contains(q);
    }

    private void updateCount() {
        if (countLabel == null) {
            return;
        }
        int installed = 0;
        for (String pkg : selected) {
            if (installedByPkg.containsKey(pkg)) {
                installed++;
            }
        }
        countLabel.setText("已勾选 " + installed + " 个应用 · 只有这些应用做头像 + 角标");
    }

    private void persist() {
        SharedPreferences.Editor ed = ConfigStore.prefs(this).edit();
        ed.putString(ConfigStore.KEY_AVATAR_PACKS, ConfigStore.joinPacks(selected));
        ed.apply();
    }

    // ---------------------------------------------------------------- 列表

    private final class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final AppEntry e = shown.get(position);
            LinearLayout row = new LinearLayout(AvatarWhitelistActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(60));
            row.setPadding(dp(14), dp(8), dp(14), dp(8));
            row.setBackground(roundRect(dp(14), colorCard));

            ImageView icon = new ImageView(AvatarWhitelistActivity.this);
            if (e.icon != null) {
                icon.setImageDrawable(e.icon);
            } else {
                icon.setBackground(roundRect(dp(11), dark ? 0xFF2C2C2E : 0xFFEDEDF0));
            }
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
            iconParams.rightMargin = dp(14);
            row.addView(icon, iconParams);

            LinearLayout text = new LinearLayout(AvatarWhitelistActivity.this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(AvatarWhitelistActivity.this);
            label.setText(e.label);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            label.setTextColor(colorText);
            label.setSingleLine(true);
            text.addView(label);
            TextView sub = new TextView(AvatarWhitelistActivity.this);
            sub.setText(e.installed ? e.pkg : e.pkg + " · 未安装");
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            sub.setTextColor(colorSecond);
            sub.setSingleLine(true);
            text.addView(sub);
            row.addView(text, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(AvatarWhitelistActivity.this);
            sw.setChecked(selected.contains(e.pkg));
            sw.setShowText(false);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (((Switch) v).isChecked()) {
                        selected.add(e.pkg);
                    } else {
                        selected.remove(e.pkg);
                    }
                    persist();
                    updateCount();
                    rebuildShown();
                }
            });
            row.addView(sw);

            LinearLayout wrap = new LinearLayout(AvatarWhitelistActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, dp(3), 0, dp(3));
            wrap.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return wrap;
        }
    }

    // ---------------------------------------------------------------- 系统栏 / 工具

    private void applySystemBars() {
        try {
            View dv = getWindow().getDecorView();
            if (dv == null) {
                return;
            }
            int flags = dv.getSystemUiVisibility();
            flags = dark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                    : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            if (Build.VERSION.SDK_INT >= 26) {
                flags = dark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                        : (flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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
        }
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
