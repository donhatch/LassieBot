package com.superliminal.android.lassiebot;

import java.util.HashSet;
import java.util.Set;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.superliminal.android.lassiebot.IntSpinner.IntSpinnerListener;
import com.superliminal.util.android.EmailUtils;

public class LassieBotActivity extends Activity {
    private OnSharedPreferenceChangeListener runningListener;
    private SharedPreferences mPrefs; // Seems to be important to not instantiate here.
    private boolean mHaveICEs;
    private Intent mServiceIntent;

    private TextView mCountdownTextView;
    private BetterCountDownTimer mCountDownTimer = null;


    //
    // Empirically, if I set a CountDownTimer with millisTilDone=10*1000 and intervalMillis = 1000,
    // I get the following extremely lame sequence of callbacks:
    // Starting .015 seconds after created:
    //    onTick(9970)
    //    onTick(8970)
    //    onTick(7969)
    //    onTick(6969)
    //    onTick(5968)
    //    onTick(4968)
    //    onTick(3968)
    //    onTick(2967)
    //    onTick(1966)
    // and then 1.8 seconds or so later (!?):
    //    onFinish()
    // The 1.8 sec delay at the end is confirmed here:
    //    http://stackoverflow.com/questions/8857590/android-countdowntimer-skips-last-ontick?rq=1
    //
    // The drift is lame, the 1.8 sec final delay is lame,
    // the incomprehensible contract is lame,
    // and I want the ability to start aligned at a multiple of interval
    // prior to the target and stay there.
    //
    // So here's a better countdown timer.
    // The onTick callback gets called with the number of ticks remaining
    // after the current one.
    // Guarantees:
    //    - onTick() will be called with a strictly decreasing series of integers
    //    - the first onTick() gets called immediately
    //    - subsequent onTick()s will be called as close as possible to each
    //      integer multiple of intervalMillis before the target time.
    //    - onTick(0) is guaranteed to be called, as close as possible to the target time
    //      (unless cancelled)
    //    - if the process is delayed such that an onTick() would be called
    //      late by an interval or more, it will be discarded instead
    //      (unless it's the last tick; onTick(0) is guaranteed to be called).
    private static abstract class BetterCountDownTimer
    {
        private Handler mHandler;
        private Runnable mRunnable;
        private boolean mStarted;

        // these are set on start
        private long mTargetTimeMillis;
        private long mIntervalMillis;
        private long mTicksRemaining;

        public BetterCountDownTimer()
        {
            System.out.println("        in BetterCountDownTimer ctor");
            mHandler = new Handler();
            mRunnable = new Runnable() {
                @Override
                public void run() {
                    System.out.println("            in BetterCountDownTimer mRunnable.run");
                    System.out.println("              decrementing mTicksRemaining "+mTicksRemaining+" -> "+(mTicksRemaining-1));
                    // Must decrement by at least one, per contract
                    --mTicksRemaining;
                    if (mTicksRemaining > 0)
                    {
                        // If we've been delayed by a whole interval or more,
                        // and we proceed naively, then onTick will get called
                        // more than once consecutively, potentially
                        // thousands of times. In that case, jump directly
                        // to the last of them.
                        // This is the right thing to do if the timer is being used
                        // to update a countdown display.
                        long nowMillis = System.currentTimeMillis();
                        long millisTilDone = mTargetTimeMillis - nowMillis;
                        // Same logic as in start(), but one less.
                        // TODO(donhatch): this case hasn't beeen tested, and maybe could be made simpler.
                        long ticksRemainingAdjusted = (millisTilDone+mIntervalMillis-1) / mIntervalMillis;
                        if (ticksRemainingAdjusted < mTicksRemaining
                         && ticksRemainingAdjusted >= 0)
                        {
                            System.out.println("              delayed! decrementing mTicksRemaining "+mTicksRemaining+" -> "+ticksRemainingAdjusted);
                            mTicksRemaining = ticksRemainingAdjusted;
                        }
                    }
                    onTick(mTicksRemaining);
                    if (mTicksRemaining > 0)
                    {
                        long nextTickTimeMillis = mTargetTimeMillis - (mTicksRemaining-1)*mIntervalMillis;
                        // Take yet another "now" snapshot,
                        // as close to the postDelayed() as we can get.
                        long nowMillis = System.currentTimeMillis();
                        long millisTilNextTick = Math.max(0, nextTickTimeMillis - nowMillis);
                        System.out.println("          nextTickTimeMillis = "+nextTickTimeMillis);
                        System.out.println("          calling mHandler.postDelayed(millisTilNextTick="+millisTilNextTick+")");
                        mHandler.postDelayed(mRunnable, millisTilNextTick);
                    }
                    System.out.println("            out BetterCountDownTimer mRunnable.run");
                }
            };
            mStarted = false;
            System.out.println("        out BetterCountDownTimer ctor");
        } // ctor

        final void start(long targetTimeMillis, long intervalMillis)
        {
            System.out.println("        in BetterCountDownTimer.start");
            System.out.println("          targetTimeMillis = "+targetTimeMillis);
            System.out.println("          intervalMillis = "+intervalMillis);
            if (mStarted)
                throw new AssertionError("BetterCountDownTimer.started multiple times concurrently!");
            mStarted = true;

            mTargetTimeMillis = targetTimeMillis;
            mIntervalMillis = intervalMillis;

            long nowMillis = System.currentTimeMillis();
            long millisTilDone = mTargetTimeMillis - nowMillis;
            if (millisTilDone < 0)
                mTicksRemaining = 1; // guarantee at least one tick, no matter what
            else
            {
                // E.g.
                //  millisTilDone == 2*mIntervalMillis+1  -> mTicksRemaining = 4
                //  millisTilDone == 2*mIntervalMillis    -> mTicksRemaining = 3
                //  millisTilDone == 2*mIntervalMillis-1  -> mTicksRemaining = 3
                //  ...
                //  millisTilDone ==   mIntervalMillis+1  -> mTicksRemaining = 3
                //  millisTilDone ==   mIntervalMillis    -> mTicksRemaining = 2
                //  millisTilDone ==   mIntervalMillis-1  -> mTicksRemaining = 2
                //  ...
                //  millisTilDone ==   1                  -> mTicksRemaining = 2
                //  millisTilDone ==   0                  -> mTicksRemaining = 1
                // I.e. divide rounding up, and add 1.
                mTicksRemaining = millisTilDone < 0 ? 1 : (millisTilDone+mIntervalMillis-1) / mIntervalMillis + 1;
            }
            long nextTickTimeMillis = mTargetTimeMillis - (mTicksRemaining-1)*mIntervalMillis;
            long millisTilNextTick = Math.max(0, nextTickTimeMillis - nowMillis);

            System.out.println("          mTicksRemaining = "+mTicksRemaining);
            System.out.println("          nextTickTimeMillis = "+nextTickTimeMillis);
            System.out.println("          calling mHandler.postDelayed(millisTilNextTick="+millisTilNextTick+")");
            mHandler.postDelayed(mRunnable, millisTilNextTick);
            System.out.println("        out BetterCountDownTimer.start");
        } // start

        // It's ok to cancel even if not running.
        final void cancel()
        {
            System.out.println("        in BetterCountDownTimer.cancel");
            if (mStarted)
            {
                System.out.println("          actually removing callbacks!");
                mHandler.removeCallbacksAndMessages(null);
                System.out.println("          actually removing callbacks!");
                mStarted = false;
            }
            System.out.println("        out BetterCountDownTimer.cancel");
        } // cancel

        abstract void onTick(long invervalsRemaining);

    } // class BetterCountdownTimer

    protected void onResume() {
        System.out.println("    in LassieBotActivity.onResume");
        super.onResume();
        // Activity became visible.
        // Enable the displayed countdown timer.
        // mCountdownTimer *should* be null at this point,
        // but who knows whether we can trust that onPause/onResume's alternate.
        if (mCountDownTimer != null)
        {
            Log.e(LassieBotService.TAG, "mCountDownTimer was unexpectedly non-null in onResume");
            mCountDownTimer.cancel(); // who knows what state it was in
        }
        else
        {
            mCountDownTimer = new BetterCountDownTimer() {
                @Override
                public void onTick(long numIntervalsRemaining)
                {
                    System.out.println("                in countDownTimer.onTick(numIntervalsRemaining="+numIntervalsRemaining+")");
                    // HHH:MM:SS
                    mCountdownTextView.setText(String.format("%d:%02d:%02d",
                                                             numIntervalsRemaining/(60*60),
                                                             numIntervalsRemaining/(60)%60,
                                                             numIntervalsRemaining%60));
                    System.out.println("                out countDownTimer.onTick(numIntervalsRemaining="+numIntervalsRemaining+")");
                }
            };
        }
        boolean running = mPrefs.getBoolean(LassieBotService.PREFS_KEY_RUNNING, false);
        if (running)
        {
            long alarmTimeMillis = mPrefs.getLong(LassieBotService.PREFS_KEY_ALARMTIME_MILLIS, 0);
            mCountDownTimer.start(alarmTimeMillis, 1000);
        }
        else
        {
            mCountdownTextView.setText(""); // clear possibly stale value
        }
        System.out.println("    out LassieBotActivity.onResume");
    } // onResume

    @Override
    protected void onPause() {
        System.out.println("    in LassieBotActivity.onPause");
        super.onPause();
        // Activity is no longer visible.
        // Disable the displayed countdown timer.
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
            mCountDownTimer = null;
        }
        mCountdownTextView.setText(""); // clear possibly stale value
        System.out.println("    out LassieBotActivity.onPause");
    } // onPause

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        System.out.println("    in LassieBotActivity.onCreate");
        super.onCreate(savedInstanceState);
        mServiceIntent = new Intent(LassieBotActivity.this, LassieBotService.class);
        mPrefs = getSharedPreferences(LassieBotService.PREFS_NAME, LassieBotService.PREFS_SHARE_MODE);
        setContentView(R.layout.lert);
        mCountdownTextView = (TextView) findViewById(R.id.countdown);
        final IntSpinner intSpinner = (IntSpinner) findViewById(R.id.timeout_spinner);
        int timeout_hours = mPrefs.getInt(LassieBotService.PREFS_KEY_TIMEOUT_HOURS, LassieBotService.DEFAULT_TIMEOUT_HOURS);
        boolean configuring = mPrefs.getBoolean(LassieBotService.PREFS_KEY_CONFIGURE, false);
        LassieBotService.CONFIGURE = configuring;
        intSpinner.setAll(LassieBotService.CONFIGURE ? 0 : 1, 24, timeout_hours);
        intSpinner.addListener(new IntSpinnerListener() {
            @Override
            public void valueChanged(int new_val) {
                System.out.println("        in intSpinner valueChanged");
                System.out.println("          new_val = "+new_val);
                mPrefs.edit().putInt(LassieBotService.PREFS_KEY_TIMEOUT_HOURS, new_val).commit();
                System.out.println("        out intSpinner valueChanged");
            }
        });
        // Initialize configuration controls. 
        final CheckBox configureCheckBox = (CheckBox) findViewById(R.id.calibrate);
        configureCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                System.out.println("        in configureCheckBox onCheckChanged");
                LassieBotService.CONFIGURE = isChecked;
                mPrefs.edit().putBoolean(LassieBotService.PREFS_KEY_CONFIGURE, isChecked).commit();
                updateControls();
                System.out.println("        out configureCheckBox onCheckChanged");
            }
        });

        final ToggleButton toggle = ((ToggleButton) findViewById(R.id.toggle));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean toggleChecked) {
                System.out.println("        in toggle onCheckChanged");
                if(toggleChecked)
                    startService(mServiceIntent);
                else
                    stopService(mServiceIntent);
                updateControls();
                System.out.println("        out toggle onCheckChanged");
            }
        });
        // Setting a dummy click listener enables default button click sound. Go figure.
        toggle.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                System.out.println("        in toggle onClick");
                System.out.println("        out toggle onClick");
            }
        });
        // Listen for service running state changes. Most of the time the changes come from
        // this Activity when the user toggles the start/stop button, but the service also
        // updates it when the alarm goes off and it stops itself.
        runningListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                System.out.println("        in onSharedPreferenceChanged");

                // CBB: get this the hell out of here. communicate via broadcast messages or something, not a shared preference!
                if(LassieBotService.PREFS_KEY_ALARMTIME_MILLIS.equals(key)) {
                    System.out.println("          PREFS_KEY_ALARMTIME_MILLIS: reset countdown display if running");

                    // Generally this pref only gets set by the service
                    // so we won't get here unless the service is running,
                    // but double-check.
                    boolean running = mPrefs.getBoolean(LassieBotService.PREFS_KEY_RUNNING, false);
                    if (running) {
                        long alarmTimeMillis = prefs.getLong(LassieBotService.PREFS_KEY_ALARMTIME_MILLIS, 0L);
                        if (mCountDownTimer != null) { // if between onResume() and onPause()
                            mCountDownTimer.cancel();
                            mCountDownTimer.start(alarmTimeMillis, 1000);
                        }
                    }
                    return;
                }

                if(!LassieBotService.PREFS_KEY_RUNNING.equals(key)) {
                    System.out.println("        out onSharedPreferenceChanged (key is not PREFS_KEY_RUNNING)");
                    return;
                }
                System.out.println("          PREFS_KEY_RUNNING: toggling running indicator");

                boolean running = prefs.getBoolean(key, false);
                toggle.setChecked(running);
                if (running) {
                    // It looks like the service just started,
                    // in which case it should momentarily set PREFS_KEY_ALARMTIME_MILLIS
                    // which will cause us to update the countdown display,
                    // but clear it first anyway in case things are not quite
                    // as we expect.
                    mCountdownTextView.setText("");
                } else {
                    mCountDownTimer.cancel();
                    mCountdownTextView.setText("");
                }
                System.out.println("        out onSharedPreferenceChanged");
            }
        };
        mPrefs.registerOnSharedPreferenceChangeListener(runningListener); // XXX does the listener go off even when we're not in foreground? argh, yes! that's not very principled.  however if it was just to check running, then it's probably ok, since the service isn't going to be going up and down much when activity is not in foreground.
        // Initialize service.
        updateControls();
        // Note: The pref_running value can be out of sync with sys_running because the
        // service may have been killed without its onDestroy method being called.
        // In general use, that would be a terrible thing but it does happen 
        // during testing when restarting the app in Eclipse.
        // Log the error and restart service to get back in sync.
        if(shouldBeRunning()) {
            Log.e(LassieBotService.TAG, "Shared pref out of sync with service state!");
            startService(mServiceIntent);
        }

        // Refresh ICEs button.
        ((Button) findViewById(R.id.refresh)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                System.out.println("        in refresh onClick");
                updateControls();
                System.out.println("        out refresh onClick");
            }
        });

        class SeekAdapter implements SeekBar.OnSeekBarChangeListener {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        }
//        LassieBotService.ACCELEROMETER_THRESHOLD = mPrefs.getFloat(LassieBotService.PREFS_KEY_ACCEL_THRESHOLD, (float) LassieBotService.ACCELEROMETER_THRESHOLD_DEFAULT);
//        final SeekBar accelThresh = (SeekBar) findViewById(R.id.accel_threshold);
//        accelThresh.setProgress((int) ((1 - LassieBotService.ACCELEROMETER_THRESHOLD) * 100));
//        accelThresh.setOnSeekBarChangeListener(new SeekAdapter() {
//            @Override
//            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
//                if(!fromUser)
//                    return;
//                double frac = 1 - accelThresh.getProgress() / 100.0;
//                double new_thresh = LassieBotService.ACCELEROMETER_MAX * frac;
//                LassieBotService.ACCELEROMETER_THRESHOLD = new_thresh;mPrefs.edit().putString(LassieBotService.PREFS_KEY_ACCEL_THRESHOLD, "" + new_thresh).commit();
//                mPrefs.edit().putString(LassieBotService.PREFS_KEY_ACCEL_THRESHOLD, "" + new_thresh).commit();
//                Log.e(LassieBotService.TAG, "accel new threshold: " + new_thresh);
//            }
//        });

        LassieBotService.GYROSCOPE_THRESHOLD = mPrefs.getFloat(LassieBotService.PREFS_KEY_GYRO_THRESHOLD, (float) LassieBotService.GYROSCOPE_THRESHOLD_DEFAULT);
        final SeekBar gyroThresh = (SeekBar) findViewById(R.id.gyro_threshold);
        gyroThresh.setProgress((int) ((1 - LassieBotService.GYROSCOPE_THRESHOLD) * 100));
        gyroThresh.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(!fromUser)
                    return;
                double frac = 1 - gyroThresh.getProgress() / 100.0;
                double new_thresh = LassieBotService.GYROSCOPE_MAX * frac;
                LassieBotService.GYROSCOPE_THRESHOLD = new_thresh;
                mPrefs.edit().putFloat(LassieBotService.PREFS_KEY_GYRO_THRESHOLD, (float) new_thresh).commit();
                Log.e(LassieBotService.TAG, "gyro new threshold: " + new_thresh);
            }
        });

        ((Button) findViewById(R.id.test)).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                int last_test_num = mPrefs.getInt(LassieBotService.PREFS_KEY_TEST, 0);
                mPrefs.edit().putInt(LassieBotService.PREFS_KEY_TEST, last_test_num + 1).commit();
            }
        });

        final CheckBox disableCheckBox = (CheckBox) findViewById(R.id.disable_while_charging);
        disableCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mPrefs.edit().putBoolean(LassieBotService.PREFS_KEY_DISABLE_WHILE_CHARGING, isChecked).commit();
                updateControls();
            }
        });
        System.out.println("    out LassieBotActivity.onCreate");
    } // end onCreate()

    private void updateControls() {
        mHaveICEs = updateContacts();
        final ToggleButton toggle = ((ToggleButton) findViewById(R.id.toggle));
        boolean running = mPrefs.getBoolean(LassieBotService.PREFS_KEY_RUNNING, false);
        if(running && !mHaveICEs) // Rare case but possible when user removes last ICE.
            stopService(mServiceIntent);
        toggle.setEnabled(mHaveICEs);
        final CheckBox configureCheckBox = (CheckBox) findViewById(R.id.calibrate);
        boolean configuring = LassieBotService.CONFIGURE;
        configureCheckBox.setChecked(configuring);
        configureCheckBox.setText(configuring && running ? "Uncheck to silence" : "Configure & Test");
        final View configureControls = findViewById(R.id.test_controls);
        configureCheckBox.setEnabled(running);
        boolean configure_checkbox_checked = configureCheckBox.isChecked();
        configureControls.setVisibility((configure_checkbox_checked && running) ? View.VISIBLE : View.GONE);
        toggle.setChecked(running);
        final CheckBox chargingCheckBox = (CheckBox) findViewById(R.id.disable_while_charging);
        chargingCheckBox.setChecked(mPrefs.getBoolean(LassieBotService.PREFS_KEY_DISABLE_WHILE_CHARGING, LassieBotService.DEFAULT_DISABLE_WHILE_CHARGING));
    }

    private boolean isRunning() {
        return isServiceRunning(LassieBotService.class, LassieBotActivity.this);
        //return mPrefs.getBoolean(LassieBotService.PREFS_KEY_RUNNING, false);
    }

    private boolean shouldBeRunning() {
        // not isRunning() ?
        boolean pref_running = mPrefs.getBoolean(LassieBotService.PREFS_KEY_RUNNING, false);
        boolean sys_running = isServiceRunning(LassieBotService.class, this);
        return pref_running && !sys_running;
    }

    private boolean updateContacts() {
        final Set<String> unique_ices = new HashSet<String>();
        String text = "";
        Set<String> ices = getICEPhoneNumbers();
        mPrefs.edit().putStringSet(LassieBotService.PREFS_KEY_ICE_PHONES, ices).commit();
        for(String s : ices) {
            if(unique_ices.contains(s))
                continue;
            String display_contact = s.substring(LassieBotService.ICE_PREFIX.length());
            unique_ices.add(display_contact);
        }
        Object[] uices = unique_ices.toArray();
        boolean have_ices = unique_ices.size() > 0;
        for(int i = 0; i < uices.length; i++)
            text += uices[i] + (i < uices.length - 1 ? "\n" : "");
        if(!have_ices)
            text = "You must add at least one ICE contact to enable " + getString(getApplicationInfo().labelRes);
        TextView contacts_text_view = ((TextView) findViewById(R.id.contacts));
        contacts_text_view.setText(text);
        contacts_text_view.setTextColor(have_ices ? getResources().getColor(R.color.ss_text_variables) : Color.RED);
        return have_ices;
    } // end updateContacts()

    private Set<String> getICEPhoneNumbers() {
        System.out.println("            in getICEPhoneNumbers");
        String phone_kind = ContactsContract.CommonDataKinds.Phone.DATA;
        Cursor contacts = EmailUtils.buildFilteredPhoneCursor(this, LassieBotService.ICE_PREFIX);
        int count = contacts.getCount();
        System.out.println("" + count + " ICE's");
        int nameIdx = contacts.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME);
        int phoneIdx = contacts.getColumnIndexOrThrow(phone_kind);
        Set<String> numbers = new HashSet<String>();
        if(contacts.moveToFirst()) {
            do {
                String name = contacts.getString(nameIdx);
                String phone = contacts.getString(phoneIdx);
                numbers.add(name + LassieBotService.NAME_PHONE_SEPERATOR + " " + phone);
                Log.d(LassieBotService.TAG, phone);
                System.out.println(phone);
            } while(contacts.moveToNext());
        }
        System.out.println("            out getICEPhoneNumbers");
        return numbers;
    }

    // XXX never used at the moment
    private Set<String> getICEAddresses() {
        String email_kind = ContactsContract.CommonDataKinds.Email.DATA;
        Cursor contacts = EmailUtils.buildFilteredEmailCursor(this, LassieBotService.ICE_PREFIX);
        int count = contacts.getCount();
        System.out.println("" + count + " ICE's");
        int nameIdx = contacts.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME);
        int emailIdx = contacts.getColumnIndexOrThrow(email_kind);
        Set<String> addresses = new HashSet<String>();
        if(contacts.moveToFirst()) {
            do {
                String name = contacts.getString(nameIdx);
                String email = contacts.getString(emailIdx);
                if(email.contains("@")) { // TODO: Figure how to add this condition to query.
                    addresses.add(email);
                    Log.d(LassieBotService.TAG, email);
                    System.out.println(email);
                }
            } while(contacts.moveToNext());
        }
        return addresses;
    }

    public static boolean isServiceRunning(Class<? extends Service> serviceClass, Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for(RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if(serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

}
