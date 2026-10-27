package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Prayer times from JAKIM's e-solat, for one zone (config.xml, prayer_zone; JHR03 is Kluang,
 * Johor). Fetches today's times once a day and keeps them in the app's private files, so the
 * home screen and the screensaver both work offline from the cache. No new background service:
 * callers refresh from a background thread and read from the cache on the main thread.
 *
 * The times are Malaysia's official ones, not a calculation: JAKIM publishes them per zone, and
 * the Hijri date comes with them. The five prayers shown are Subuh, Zohor, Asar, Maghrib, Isyak.
 */
final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    private static final String FILE_NAME = "prayer_times.json";
    private static final String API = "https://www.e-solat.gov.my/index.php?r=esolatApi/takwimsolat&period=today&zone=";
    private static final int MAX_BYTES = 64 * 1024;
    /** A failed fetch is not retried for this long, so a TV with no network does not keep asking. */
    private static final long RETRY_MS = 15L * 60 * 1000;

    /** The five prayers, in the order JAKIM lists them and the home screen shows them. */
    static final String[] NAMES = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};
    /** JAKIM's field names for the five prayers, in the same order. */
    private static final String[] FIELDS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    /** Hijri month names as JAKIM spells them in Malay. */
    static final String[] HIJRI_MONTHS = {
            "Muharram", "Safar", "Rabiulawal", "Rabiulakhir", "Jamadilawal", "Jamadilakhir",
            "Rejab", "Syaaban", "Ramadhan", "Syawal", "Zulkaedah", "Zulhijjah"};

    private PrayerTimes() {
    }

    /** Today's times for a zone, as cached: the Hijri date and the five prayers in minutes after midnight. */
    static final class Data {
        final String zone;
        final String hijri;
        final int[] minutes;

        Data(String zone, String hijri, int[] minutes) {
            this.zone = zone;
            this.hijri = hijri;
            this.minutes = minutes;
        }

        /** e.g. "15 Jamadilawal 1448 Hijri", from JAKIM's "1448-05-15". */
        String hijriMalay() {
            try {
                String[] p = hijri.split("-");
                int y = Integer.parseInt(p[0]);
                int m = Integer.parseInt(p[1]);
                int d = Integer.parseInt(p[2]);
                if (m < 1 || m > 12) return hijri;
                return d + " " + HIJRI_MONTHS[m - 1] + " " + y + " Hijri";
            } catch (Exception e) {
                return hijri;
            }
        }
    }

    /** The label under "Waktu Solat": the town where it is known, otherwise just the zone code. */
    static String zoneLabel(String zone) {
        if ("JHR03".equals(zone)) return "Kluang (JHR03)";
        return zone;
    }

    /** Minutes after midnight for "HH:mm:ss", or -1 when it does not parse. */
    private static int parseTime(String s) {
        try {
            String[] p = s.split(":");
            int h = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    /** e.g. 339 -> "5:39 AM", 773 -> "12:53 PM", 971 -> "4:11 PM". */
    static String formatTime12(int minutes) {
        int h24 = minutes / 60;
        int m = minutes % 60;
        int h12 = h24 % 12;
        if (h12 == 0) h12 = 12;
        return h12 + ":" + String.format(Locale.ROOT, "%02d", m) + (h24 < 12 ? " AM" : " PM");
    }

    /** Which prayer is next at this moment, 0..4; 0 when the day's last prayer has passed, for tomorrow's Subuh. */
    static int nextIndex(Data data, Calendar now) {
        int at = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        for (int i = 0; i < 5; i++) {
            if (data.minutes[i] > at) return i;
        }
        return 0;
    }

    /** Minutes until the next prayer; tomorrow's Subuh counts from today's Subuh, a minute or so out at most. */
    static int minutesUntil(Data data, Calendar now, int next) {
        int at = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (next == 0 && data.minutes[4] <= at) return (24 * 60 - at) + data.minutes[0];
        return data.minutes[next] - at;
    }

    /** e.g. "Asar dalam 41 minit", "Subuh dalam 6 jam 9 minit", or "Maghrib sekarang" at the minute itself. */
    static String countdownLabel(Data data, Calendar now) {
        int next = nextIndex(data, now);
        int left = minutesUntil(data, now, next);
        if (left <= 0) return NAMES[next] + " sekarang";
        if (left < 60) return NAMES[next] + " dalam " + left + " minit";
        int h = left / 60, m = left % 60;
        if (m == 0) return NAMES[next] + " dalam " + h + " jam";
        return NAMES[next] + " dalam " + h + " jam " + m + " minit";
    }

    /** Today's times from the cache, or null when nothing usable has been fetched yet. */
    static Data load(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE_NAME);
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return parse(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "ignoring cache: " + e.getMessage());
            return null;
        }
    }

    /** True when today's times for this zone still need fetching: no cache, a stale day, or another zone. */
    static boolean due(Context ctx, String zone) {
        SharedPreferences prefs = prefs(ctx);
        String today = todayKey();
        boolean fresh = today.equals(prefs.getString("fetched_on", ""))
                && zone.equals(prefs.getString("fetched_zone", ""))
                && load(ctx) != null;
        if (fresh) return false;
        // A failed fetch is not retried for a while.
        return System.currentTimeMillis() - prefs.getLong("last_attempt", 0) >= RETRY_MS;
    }

    /**
     * Downloads today's times for this zone and replaces the cache. Blocking: call from a
     * background thread. Returns true when fresh times were stored; every failure just leaves the
     * previous cache in place.
     */
    static boolean refresh(Context ctx, String zone) {
        prefs(ctx).edit().putLong("last_attempt", System.currentTimeMillis()).apply();
        try {
            String url = API + URLEncoder.encode(zone, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            try {
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                byte[] body;
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16 * 1024];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        out.write(buf, 0, r);
                        if (out.size() > MAX_BYTES) throw new IllegalStateException("response too large");
                    }
                    body = out.toByteArray();
                }
                Data data = parse(new String(body, StandardCharsets.UTF_8));
                if (data == null) throw new IllegalStateException("unusable response");
                File dir = ctx.getFilesDir();
                File tmp = new File(dir, FILE_NAME + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(body);
                }
                if (!tmp.renameTo(new File(dir, FILE_NAME))) throw new IllegalStateException("cache write failed");
                prefs(ctx).edit().putString("fetched_on", todayKey()).putString("fetched_zone", zone).apply();
                return true;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "refresh failed: " + e);
            return false;
        }
    }

    /** Parses one e-solat response; null when it is not today's times with all five prayers. */
    private static Data parse(String text) {
        try {
            JSONObject root = new JSONObject(text);
            if (!"OK!".equals(root.optString("status"))) return null;
            org.json.JSONArray times = root.optJSONArray("prayerTime");
            if (times == null || times.length() == 0) return null;
            JSONObject day = times.optJSONObject(0);
            if (day == null) return null;
            String hijri = day.optString("hijri", "").trim();
            String zone = root.optString("zone", "").trim();
            int[] minutes = new int[5];
            for (int i = 0; i < 5; i++) {
                minutes[i] = parseTime(day.optString(FIELDS[i], ""));
                if (minutes[i] < 0) return null;
            }
            if (hijri.isEmpty() || zone.isEmpty()) return null;
            return new Data(zone, hijri, minutes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().getTime());
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("prayer_times", Context.MODE_PRIVATE);
    }
}
