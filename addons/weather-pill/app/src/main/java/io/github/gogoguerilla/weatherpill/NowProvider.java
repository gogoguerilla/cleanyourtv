package io.github.gogoguerilla.weatherpill;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The weather now, for the launcher's pill: one row, columns "temp" (text such as "61°") and "icon" (a drawable
 * resource id in this package, white line art for the launcher to tint). No row while there is no reading, or the
 * last one is over three hours old, so the pill hides rather than show old weather.
 *
 * There is no background work: a query whose reading is over 30 minutes old starts one fetch from Open-Meteo on a
 * thread, waits up to WAIT_MS for it, and answers with what it has. A failed fetch is retried after RETRY_MS, on a
 * later query: the launcher asks each minute while Home is on screen.
 */
public class NowProvider extends ContentProvider {
    private static final long FRESH_MS = 30 * 60_000L, RETRY_MS = 60_000L, MAX_AGE_MS = 3 * 3_600_000L;
    /** How long a query waits for the fetch it started, so a stale or missing reading is replaced on this query. */
    private static final long WAIT_MS = 5_000L;
    private static volatile boolean fetching;
    private static volatile long lastTry;

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("now", 0);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"temp", "icon"});
        if (getContext().getString(R.string.latitude).isEmpty() || getContext().getString(R.string.longitude).isEmpty()) {
            return c;   // no place set (res/values/config.xml): nothing to fetch, the pill stays hidden
        }
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        long at = p.getLong("at", 0);
        if (now - at > FRESH_MS && !fetching && now - lastTry > RETRY_MS) {
            fetching = true;
            lastTry = now;
            Thread t = new Thread(this::fetch, "weather-pill");
            t.start();
            // The launcher queries off its UI thread, so waiting here is fine. Without it a pill hidden after boot
            // or a long time away from Home stays hidden until the launcher's next query, a minute later.
            try {
                t.join(WAIT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            at = p.getLong("at", 0);
        }
        if (at > 0 && now - at < MAX_AGE_MS) c.addRow(new Object[]{p.getString("temp", ""), p.getInt("icon", 0)});
        return c;
    }

    private void fetch() {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + getContext().getString(R.string.latitude)
                    + "&longitude=" + getContext().getString(R.string.longitude)
                    + "&current=temperature_2m,weather_code,is_day&temperature_unit="
                    + getContext().getString(R.string.unit);
            JSONObject current = new JSONObject(get(url)).getJSONObject("current");
            long temp = Math.round(current.getDouble("temperature_2m"));
            int icon = icon(current.getInt("weather_code"), current.optInt("is_day", 1) == 1);
            prefs().edit().putLong("at", System.currentTimeMillis()).putString("temp", temp + "°")
                    .putInt("icon", icon).apply();
        } catch (Exception ignored) {
            // no network or an odd answer: keep the last reading, try again after RETRY_MS
        } finally {
            fetching = false;
        }
    }

    /** WMO weather code (Open-Meteo's weather_code) to a line icon. */
    static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.w_sun : R.drawable.w_moon;
        if (code <= 2) return day ? R.drawable.w_cloud_sun : R.drawable.w_cloud_moon;
        if (code == 3) return R.drawable.w_cloud;
        if (code == 45 || code == 48) return R.drawable.w_fog;
        if (code >= 51 && code <= 57) return R.drawable.w_drizzle;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.drawable.w_snow;
        if (code >= 95) return R.drawable.w_storm;
        if (code >= 61) return R.drawable.w_rain;
        return R.drawable.w_cloud;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
