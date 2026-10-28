package com.littletater.notificontheme;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** 只读配置出口：SystemUI 进程靠它拿到当前选中的图标包和各项开关。 */
public class ConfigProvider extends ContentProvider {

    private static final String[] COLUMNS =
            {"enabled", "pack", "scale", "shade", "debug", "avatar", "lock", "avatar_packs"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        SharedPreferences sp = ConfigStore.prefs(getContext());
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[]{
                sp.getBoolean(ConfigStore.KEY_ENABLED, true) ? 1 : 0,
                sp.getString(ConfigStore.KEY_PACK, ""),
                sp.getFloat(ConfigStore.KEY_SCALE, 0.90f),
                sp.getBoolean(ConfigStore.KEY_SHADE, true) ? 1 : 0,
                sp.getBoolean(ConfigStore.KEY_DEBUG, false) ? 1 : 0,
                sp.getBoolean(ConfigStore.KEY_AVATAR, false) ? 1 : 0,
                sp.getBoolean(ConfigStore.KEY_LOCK, true) ? 1 : 0,
                sp.getString(ConfigStore.KEY_AVATAR_PACKS, "")
        });
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/notif-icon-state";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
