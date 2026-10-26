package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * The screensaver view on demand: the same clock, date and prayer times as ClockDream, opened
 * from the moon button on Home. A real dream can only start when the TV goes idle, and there is
 * no supported way for an app to start one itself, so this activity shows the same layout
 * (dream_clock.xml) instead. Any key press — or a tap — closes it and returns to Home, just as
 * any key wakes the real screensaver.
 *
 * <p>Kept in step with {@link ClockDream}: same layout, same sizing from the screen height, same
 * prayer block from the cache. The sizing and prayer code here is a copy of ClockDream's, not a
 * shared helper, so the working dream is untouched; if one changes, change the other.
 */
public class SleepActivity extends Activity {

    private static final long MOVE_MS = 60_000L;
    private static final float SCREEN_UNITS = 380f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private ClockFace face;
    /** Prayer times (JAKIM e-solat, see PrayerTimes): the zone, whether they show, and the views. */
    private String prayerZone;
    private boolean showPrayerTimes;
    private View prayerDivider, prayerRow;
    private TextView prayerNext;
    private final TextView[] prayerNames = new TextView[5];
    private final TextView[] prayerTimes = new TextView[5];
    private boolean prayerRefreshRunning;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            refreshPrayerIfDue();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        face = new ClockFace(this);
        ((ViewGroup) content).addView(face, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextClock date = findViewById(R.id.dreamDate);
        // The month in short and the year (in capitals, see dream_clock.xml): OCT 21, 2026.
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdyyyy");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        Config cfg = Config.load(this);
        prayerZone = cfg.prayerZone;
        showPrayerTimes = cfg.showPrayerTimes;
        prayerDivider = findViewById(R.id.dreamPrayerDivider);
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
        content.post(this::placeClock);
        // Any press wakes it: the moon button is a shortcut to the screensaver, not a new screen.
        content.setOnClickListener(v -> finish());
        refreshPrayerIfDue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        face.start();
        handler.postDelayed(tick, MOVE_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        face.stop();
        super.onPause();
    }

    /** Any key closes the view and returns to Home, as any key wakes the real screensaver. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }

    /** Lays the clock out for a screen {@code height} pixels tall. */
    private void size(int height) {
        float u = height / SCREEN_UNITS;
        face.setRimRadius(83.6f * u);
        TextView time = findViewById(R.id.dreamTime);
        TextView amPm = findViewById(R.id.dreamAmPm);
        TextView date = findViewById(R.id.dreamDate);
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, 44f * u);
        amPm.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * u);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);

        // The tops of the digits sit 34 units under the rim, below the room the dial keeps for its shadow.
        LinearLayout.LayoutParams row = (LinearLayout.LayoutParams) findViewById(R.id.dreamTimeRow).getLayoutParams();
        row.topMargin = Math.round(34f * u - face.shadowRoom() - ClockDream.capInset(time, "0"));
        // AM/PM's capitals start where the digits do.
        LinearLayout.LayoutParams ampm = (LinearLayout.LayoutParams) amPm.getLayoutParams();
        ampm.leftMargin = Math.round(5f * u);
        amPm.setTranslationY(ClockDream.capInset(time, "0") - ClockDream.capInset(amPm, "PM"));
        // The date's capitals start 16.5 units under the digits' baseline.
        LinearLayout.LayoutParams dateRow = (LinearLayout.LayoutParams) date.getLayoutParams();
        dateRow.topMargin = Math.round(16.5f * u - time.getPaint().getFontMetrics().descent - ClockDream.capInset(date, "O"));
        // The prayer times under the date, in the same units as the clock.
        prayerNext.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * u);
        for (int i = 0; i < 5; i++) {
            prayerNames[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, 8.5f * u);
            prayerTimes[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, 9f * u);
        }
        content.requestLayout();
    }

    /** Fills the prayer times from the cache, or hides them when there is nothing to show. */
    private void updatePrayer() {
        if (!showPrayerTimes || prayerRow == null) return;
        PrayerTimes.Data data = PrayerTimes.load(this);
        if (data == null) {
            setPrayerVisible(false);
            return;
        }
        Calendar now = Calendar.getInstance();
        int next = PrayerTimes.nextIndex(data, now);
        prayerNext.setText(PrayerTimes.countdownLabel(data, now).toUpperCase(Locale.ROOT));
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
        prayerNext.setVisibility(v);
        prayerRow.setVisibility(v);
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
                if (isFinishing() || isDestroyed()) return;
                updatePrayer();
            });
        }, "prayer-times").start();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
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
