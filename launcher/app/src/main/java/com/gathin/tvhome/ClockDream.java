package com.gathin.tvhome;

import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: a big digital clock with the date, day and Hijri date beneath, in Malay, on
 * the left, and the prayer times on the right. It sits in the middle of the screen and every
 * minute shifts a few pixels around the centre, so nothing burns in while it still reads as
 * centred.
 * No analog dial and no network, no images: cheap enough for a 1 GB TV. (The dial went away
 * in v4: its second hand redrew every second and stuttered on the 1 GB stick.)
 *
 * <p>Everything is sized from the screen's height, in the units the design was drawn in: a screen
 * 380 units tall.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    private static final float SCREEN_UNITS = 380f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private TextView day;
    /** Hijri date from JAKIM (e.g. 16 Jamadilawal 1448 Hijri); shown only with prayer data. */
    private TextView hijri;
    /** Prayer times (JAKIM e-solat, see PrayerTimes): the zone, whether they show, and the views. */
    private String prayerZone;
    private boolean showPrayerTimes;
    private View prayerDivider, prayerRow, prayerBlock;
    private TextView prayerNext, prayerTitle;
    private final TextView[] prayerNames = new TextView[5];
    private final TextView[] prayerTimes = new TextView[5];
    private boolean dreaming;
    private boolean prayerRefreshRunning;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            updateDay();
            refreshPrayerIfDue();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        TextClock date = findViewById(R.id.dreamDate);
        // Malay locale, so the month renders in Malay (e.g. 27 OKT 2026).
        String pattern = DateFormat.getBestDateTimePattern(new Locale("ms"), "MMMdyyyy");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        day = findViewById(R.id.dreamDay);
        updateDay();
        hijri = findViewById(R.id.dreamHijri);
        Config cfg = Config.load(this);
        prayerZone = cfg.prayerZone;
        showPrayerTimes = cfg.showPrayerTimes;
        prayerDivider = findViewById(R.id.dreamPrayerDivider);
        prayerBlock = findViewById(R.id.dreamPrayerBlock);
        prayerTitle = findViewById(R.id.dreamPrayerTitle);
        prayerNext = findViewById(R.id.dreamPrayerNext);
        prayerRow = findViewById(R.id.dreamPrayerRow);
        int[] nameIds = {R.id.dreamPrayerName0, R.id.dreamPrayerName1, R.id.dreamPrayerName2,
                R.id.dreamPrayerName3, R.id.dreamPrayerName4};
        int[] timeIds = {R.id.dreamPrayerTime0, R.id.dreamPrayerTime1, R.id.dreamPrayerTime2,
                R.id.dreamPrayerTime3, R.id.dreamPrayerTime4};
        for (int i = 0; i < 5; i++) {
            prayerNames[i] = findViewById(nameIds[i]);
            prayerTimes[i] = findViewById(timeIds[i]);
        }
        View screen = (View) content.getParent();
        screen.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) size(b - t);
        });
        // Re-centre once the clock has its size for this screen.
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) placeClock();
        });
    }

    /** Lays the clock out for a screen {@code height} pixels tall. */
    private void size(int height) {
        float u = height / SCREEN_UNITS;
        TextView time = findViewById(R.id.dreamTime);
        TextView amPm = findViewById(R.id.dreamAmPm);
        TextView date = findViewById(R.id.dreamDate);
        // Digital only: time and AM/PM are the same size, on one baseline.
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, 48f * u);
        amPm.setTextSize(TypedValue.COMPLEX_UNIT_PX, 48f * u);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        hijri.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);

        // The digital time sits at the top of the clock block: no dial above it any more.
        LinearLayout.LayoutParams row = (LinearLayout.LayoutParams) findViewById(R.id.dreamTimeRow).getLayoutParams();
        row.topMargin = 0;
        // AM/PM sits right after the digits, same size, same baseline.
        LinearLayout.LayoutParams ampm = (LinearLayout.LayoutParams) amPm.getLayoutParams();
        ampm.leftMargin = Math.round(5f * u);
        // The date's capitals start 16.5 units under the digits' baseline.
        LinearLayout.LayoutParams dateRow = (LinearLayout.LayoutParams) date.getLayoutParams();
        dateRow.topMargin = Math.round(16.5f * u - time.getPaint().getFontMetrics().descent - capInset(date, "O"));
        // The day sits just under the date.
        LinearLayout.LayoutParams dayRow = (LinearLayout.LayoutParams) day.getLayoutParams();
        dayRow.topMargin = Math.round(8f * u);
        // The Hijri date sits just under the day, same size.
        LinearLayout.LayoutParams hijriRow = (LinearLayout.LayoutParams) hijri.getLayoutParams();
        hijriRow.topMargin = Math.round(8f * u);
        // The prayer times on the right, in the same units as the clock, big enough to read
        // from the sofa.
        prayerTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * u);
        prayerNext.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * u);
        for (int i = 0; i < 5; i++) {
            prayerNames[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * u);
            prayerTimes[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * u);
        }
        content.requestLayout();
    }

    /** The day of the week in Malay (e.g. Selasa), refreshed each minute so it rolls over
     * at midnight. */
    private void updateDay() {
        if (day == null) return;
        day.setText(new java.text.SimpleDateFormat("EEEE", new Locale("ms")).format(new java.util.Date()));
    }

    /** Fills the prayer times from the cache, or hides them when there is nothing to show. */
    private void updatePrayer() {
        if (!showPrayerTimes || prayerRow == null) return;
        PrayerTimes.Data data = PrayerTimes.load(this);
        if (data == null) {
            setPrayerVisible(false);
            return;
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        int next = PrayerTimes.nextIndex(data, now);
        hijri.setText(data.hijriMalay());
        prayerNext.setText(PrayerTimes.countdownLabel(data, now).toUpperCase(java.util.Locale.ROOT));
        for (int i = 0; i < 5; i++) {
            prayerNames[i].setText(PrayerTimes.NAMES[i]);
            prayerTimes[i].setText(PrayerTimes.formatTime12(data.minutes[i]));
            boolean isNext = i == next;
            prayerNames[i].setTextColor(isNext ? 0xFF4ADE80 : 0xFFD4D4D6);
            prayerTimes[i].setTextColor(isNext ? 0xFF4ADE80 : 0xFF8B8C90);
        }
        setPrayerVisible(true);
    }

    private void setPrayerVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        prayerDivider.setVisibility(v);
        prayerBlock.setVisibility(v);
        prayerTitle.setVisibility(v);
        prayerNext.setVisibility(v);
        prayerRow.setVisibility(v);
        // The Hijri date lives in the clock block, but it comes with the prayer data, so it
        // shows and hides with the prayer times.
        hijri.setVisibility(v);
    }

    /** Fetches today's times when due; the row updates from the cache in the meantime. */
    private void refreshPrayerIfDue() {
        if (!showPrayerTimes || prayerRefreshRunning) return;
        if (!PrayerTimes.due(this, prayerZone)) {
            updatePrayer();
            return;
        }
        prayerRefreshRunning = true;
        new Thread(() -> {
            PrayerTimes.refresh(this, prayerZone);
            handler.post(() -> {
                prayerRefreshRunning = false;
                if (!dreaming) return;
                updatePrayer();
            });
        }, "prayer-times").start();
    }

    /** How far below the top of {@code view} the tops of {@code letters} are drawn. */
    static float capInset(TextView view, String letters) {
        Paint paint = view.getPaint();
        Rect bounds = new Rect();
        paint.getTextBounds(letters, 0, letters.length(), bounds);
        return bounds.top - paint.getFontMetrics().ascent;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        refreshPrayerIfDue();
        handler.postDelayed(tick, MOVE_MS);
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        handler.removeCallbacks(tick);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    /** Shifts the whole block a few pixels, with no fade: the old fade-out and fade-in every
     * minute read as the screen flashing. A nudge this small is invisible as a jump. */
    private void moveClock() {
        placeClock();
    }

    /** The middle of the screen, nudged up to 1.5 % either way. */
    private void placeClock() {
        View parent = (View) content.getParent();
        float nudgeX = parent.getWidth() * 0.015f * (2f * random.nextFloat() - 1f);
        float nudgeY = parent.getHeight() * 0.015f * (2f * random.nextFloat() - 1f);
        float x = (parent.getWidth() - content.getWidth()) / 2f + nudgeX;
        float y = (parent.getHeight() - content.getHeight()) / 2f + nudgeY;
        content.setX(x);
        content.setY(y);
    }
}
