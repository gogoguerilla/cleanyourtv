package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

/**
 * weather_pill_authority (config.xml): a pill beside Settings with the weather icon and temperature that a local
 * add-on's provider supplies (columns temp and icon, a drawable id in that app). The launcher only reads it, off the
 * UI thread; the add-on does any network. No row, or no provider, hides the pill.
 */
final class WeatherPill {
    private final Context ctx;
    private final TextView pill;
    private final String authority;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int shownIcon;

    WeatherPill(Context ctx, TextView pill, String authority) {
        this.ctx = ctx;
        this.pill = pill;
        this.authority = authority;
    }

    void refresh() {
        new Thread(() -> {
            String temp = null;
            Drawable icon = null;
            int iconId = 0;
            try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://" + authority), null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    temp = c.getString(0);
                    iconId = c.getInt(1);
                    ProviderInfo info = ctx.getPackageManager().resolveContentProvider(authority, 0);
                    if (iconId != 0 && iconId != shownIcon && info != null) {
                        icon = ctx.getPackageManager().getResourcesForApplication(info.packageName).getDrawable(iconId, null);
                    }
                }
            } catch (Exception ignored) {
                // add-on missing or broken: hide
            }
            String t = temp;
            Drawable d = icon;
            int id = iconId;
            main.post(() -> {
                pill.setVisibility(t == null ? View.GONE : View.VISIBLE);
                if (t == null) return;
                pill.setText(t);
                if (d != null) {
                    pill.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
                    shownIcon = id;
                }
            });
        }, "weather-pill").start();
    }
}
