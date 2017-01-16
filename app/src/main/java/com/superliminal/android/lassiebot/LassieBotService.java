package com.superliminal.android.lassiebot;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.OrientationListener;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class LassieBotService extends Service {
    static final String
        PREFS_NAME = "com.superliminal.android.lassiebot",
        PREFS_KEY_TIMEOUT_HOURS = "imeout_hours",
        PREFS_KEY_RUNNING = "running",
        PREFS_KEY_CONFIGURE = "configuring",
        PREFS_KEY_DISABLE_WHILE_CHARGING = "disable while charging",
        PREFS_KEY_ACCEL_THRESHOLD = "accel threshold val",
        PREFS_KEY_GYRO_THRESHOLD = "gyro threshold val",
        PREFS_KEY_ICE_PHONES = "Phones",
        PREFS_KEY_ICE_ADDRESSES = "Emails",
        PREFS_KEY_TEST = "do test",
        PREFS_KEY_ALARMTIME_MILLIS = "time dead man switch will go off";

    static final String ICE_PREFIX = "ICE:";
    static final String TAG = "lert";
    static final char NAME_PHONE_SEPERATOR = '\u2013';
    static final int PREFS_SHARE_MODE = android.content.Context.MODE_WORLD_READABLE;
    static final int DEFAULT_TIMEOUT_HOURS = 12;
    static boolean CONFIGURE = false; // Whether to play sound on strong events.
    static final boolean DEFAULT_DISABLE_WHILE_CHARGING = true;
    static final double ACCELEROMETER_MAX = 20;
    static final double ACCELEROMETER_THRESHOLD_DEFAULT = ACCELEROMETER_MAX / 2.; //9.8; // Accelerometer event sensitivity.
    static double ACCELEROMETER_THRESHOLD = ACCELEROMETER_THRESHOLD_DEFAULT;
    static final double GYROSCOPE_MAX = 1;
    static final double GYROSCOPE_THRESHOLD_DEFAULT = GYROSCOPE_MAX / 2.; //.05;
    static double GYROSCOPE_THRESHOLD = GYROSCOPE_THRESHOLD_DEFAULT; // Gyroscope event sensitivity.
    private final static boolean DEBUG = false;
    private final static int COUNTDOWN_SECONDS = 30;
    private static final int LERT_SERVICE_ID = 666;
    private long TIMEOUT_MILLIS = DEFAULT_TIMEOUT_HOURS * 60 * 60 * 1000;
    private static final int DURATION_THRESHOLD_MILLIS = 1500; // Minimum time between resets.
    private static final int NAME_LIMIT = 20;
    private MediaPlayer dink, beep, buzz, tick;
    private static Timer deadManSwitch = new Timer(); // Must be static or we get lots of them!
    private WakeLock wakeLock;
    private OnSharedPreferenceChangeListener mPrefListener; // Must be retained as a member or it can be GC'ed.
    private Vibrator vibes;

    private static final String WARNING_MESSAGE = // This plus max name length must be less than 140.
    " identified you as an emergency contact. " +
        "Their mobile device has not moved in a long time. " +
        "You should contact them now. ";

    private static final int mVerboseLevel = 1; // 1: just major control functions. 2: and onSensorChanged (a lot of verbosity)

    // Code to be run when the dead-man's-switch is triggered.
    private class LertAlarm extends TimerTask {
        private boolean doCountdown = true;
        public LertAlarm() {}
        public LertAlarm(boolean doCountdown){
            this.doCountdown = doCountdown;
        }
        @Override
        public void run() {
            if (mVerboseLevel >= 1) System.out.println("        in LertAlarm.run");
            // If the timer goes off while charging and not "disabled while charging", just quietly restart it.
            // This is so alarms will not be triggered while the user is asleep
            // or otherwise not occasionally interacting physically with their device.
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, PREFS_SHARE_MODE);
            if(prefs.getBoolean(PREFS_KEY_DISABLE_WHILE_CHARGING, true)) {
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = registerReceiver(null, ifilter);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
                    reschedule();
                    if (mVerboseLevel >= 1) System.out.println("        out LertAlarm.run (early because battery charging or full or something)");
                    return;
                }
            }

            if(doCountdown) {
                // Start the countdown sound.
                tick.start();
                vibes.vibrate(new long[] {500, 500}, 0);
                // Block while giving user a chance to nudge the phone to cancel.
                final long counting_start = System.currentTimeMillis();
                for(int i=0; i<COUNTDOWN_SECONDS; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {}
                    Log.w(TAG, "diff = " + (counting_start - mShakeSensor.mLastStrongShake));
                    if(mShakeSensor.mLastStrongShake > counting_start) {
                        // Phone moved during countdown so abort the alert.
                        tick.pause();
                        vibes.cancel();
                        if (mVerboseLevel >= 1) System.out.println("        out LertAlarm.run (early because phone moved during countdown)");
                        return;
                    }
                }
                vibes.cancel();
                tick.pause();
            }

            // This is what it's all about. We are here because there has been no motion
            // for the maximum time allowed. That could indicate an incapacitated user
            // so it's time to send scary notifications to their chosen ICE recipients.
            String user_name = "A Lert user"; // Hopefully will always be replaced below.
            Cursor c = getContentResolver().query(ContactsContract.Profile.CONTENT_URI, new String[]{Phone.DISPLAY_NAME,}, null, null, null);
            if(c != null && c.getCount() > 0) {
                c.moveToFirst();
                String got_name = c.getString(0);
                int name_length = got_name.length();
                if(name_length != 0)
                    user_name = got_name;
                if(name_length > NAME_LIMIT) // needed to guarantee the message is not too long.
                    user_name = user_name.substring(0, NAME_LIMIT);
                c.close();
            }
            // TODO: Also get the user's phone number if possible and send it the alert too so that user can see false positives immediately.
            Set<String> ice_phones = new HashSet<String>();
            ice_phones = prefs.getStringSet(PREFS_KEY_ICE_PHONES, ice_phones);
            for(String contact : ice_phones) {
                int separator = contact.indexOf(LassieBotService.NAME_PHONE_SEPERATOR);
                if(separator < 0)
                    continue; // Invalid contact.
                String name = contact.substring(LassieBotService.ICE_PREFIX.length(), separator);
                String phone = contact.substring(separator + 1);
                Log.d(TAG, "alerting " + contact);
                SmsManager.getDefault().sendTextMessage(phone, null, user_name + WARNING_MESSAGE, null, null);
            }
            // We can't release the MediaPlayer resources until after the buzzer finishes.
            // Ideally we would do this in onDestroy() but the sound can complete
            // *after* the service has been stopped, but we can do in an onCompletionListener.
            buzz.setOnCompletionListener(new OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    buzz.release();
                    beep.release();
                    dink.release();
                    tick.release();
                }
            });
            buzz.start(); // One last, loud "time-out" buzzer announcing that the messages were sent.
            SensorManager sensorMgr = (SensorManager) getSystemService(SENSOR_SERVICE);
            sensorMgr.unregisterListener(mShakeSensor);
            stopSelf(); // My work here is done. I hope they're OK.
            if (mVerboseLevel >= 1) System.out.println("        out LertAlarm.run");
        } // run
    }; // LertAlarm

    private class MyShakeSensor implements SensorEventListener {
        public long mLastShakeNanos = System.nanoTime();
        public long mLastShakeTimestampNanos = System.nanoTime();
        public long mLastStrongShake = System.currentTimeMillis();

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        private String nanosSeparated(long nanos)
        {
            String answer = String.format("%d.%03d %03d %03d",
                                          nanos/(1000*1000*1000),
                                          nanos/(1000*1000)%1000,
                                          nanos/1000%1000,
                                          nanos%1000);
            return answer;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            long nowNanos = System.nanoTime(); // as soon as possible
            if (mVerboseLevel >= 2) System.out.println("        in onSensorChanged");
            long nowTimestampNanos = event.timestamp;
            long now = System.currentTimeMillis();
            long nanosSinceLastShake = nowNanos - mLastShakeNanos;
            long dur = now - mLastStrongShake;
            if (mVerboseLevel >= 2) System.out.println("          SENSOR_DELAY_NORMAL = "+SensorManager.SENSOR_DELAY_NORMAL);
            if (mVerboseLevel >= 2) System.out.println("          "+nanosSeparated(nowNanos-mLastShakeNanos)+" seconds since last shake from timestamp");
            if (mVerboseLevel >= 2) System.out.println("          "+nanosSeparated(nowTimestampNanos-mLastShakeTimestampNanos)+" seconds since last shake from System.nanoTime()");
            if (mVerboseLevel >= 2) System.out.println("          dur = "+dur+" (since last strong shake)");
            {
                int type = event.sensor.getType();
                SensorManager sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
                Sensor sensor = sensorManager.getDefaultSensor(type);
                if (mVerboseLevel >= 2) System.out.println("          event.sensor.getType() = "+type+" (\""+sensor.getName()+")\"");
            }
            mLastShakeNanos = nowNanos;
            mLastShakeTimestampNanos = nowTimestampNanos;

            if(dur < DURATION_THRESHOLD_MILLIS)
            {
                if (mVerboseLevel >= 2) System.out.println("        out onSensorChanged (early because \"There's no point doing everything below many times a second.\"");
                return; // There's no point doing everything below many times a second.
            }
            boolean do_reset = false;
            float force = (float) Math.sqrt(Vec_h._NORMSQRD3(event.values));
            if (mVerboseLevel >= 2) System.out.println("          force = "+force);
            if(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                // Linear force in m/s^2. BTW, what is a square second anyway?
                if(force > ACCELEROMETER_THRESHOLD) {
                    do_reset = true;
                    reschedule();
                    if(CONFIGURE) {
                        dink.start();
                        Log.d(LassieBotService.TAG, "accel force: " + force);
                    }
                }
            } // end if(accelerometer)
            else
            if(event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                if(force > GYROSCOPE_THRESHOLD) {
                    do_reset = true;
                    reschedule();
                    if(CONFIGURE) {
                        beep.start();
                        Log.d(LassieBotService.TAG, "gyro force: " + force);
                    }
                }
            } // end if(gyroscope)
            if(do_reset)
                mLastStrongShake = now;
            if (mVerboseLevel >= 2) System.out.println("        out onSensorChanged");
        }
    }

    private MyShakeSensor mShakeSensor = new MyShakeSensor();

    private void reschedule() {
        if (mVerboseLevel >= 1) System.out.println("            in LassieBotService.reschedule");
        // Synchronizing below may do nothing but the alarm has gone off during testing and
        // one possible reason could be a race condition where an old timer was not canceled
        // before being replaced, So long as this is the only place that creates the timers,
        // synchronizing on them should disallow that error.

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, PREFS_SHARE_MODE); // CBB: put this in a member
        long alarmTimeMillis = System.currentTimeMillis() + TIMEOUT_MILLIS;
        prefs.edit().putLong(LassieBotService.PREFS_KEY_ALARMTIME_MILLIS, alarmTimeMillis).commit();

        synchronized(deadManSwitch) {
            deadManSwitch.cancel();
            deadManSwitch.purge();
            deadManSwitch = new Timer();
            deadManSwitch.schedule(new LertAlarm(), TIMEOUT_MILLIS);
        }
        if (mVerboseLevel >= 1) System.out.println("            out LassieBotService.reschedule");
    }

    private void test() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                (new LertAlarm(DEBUG)).run();
                return null;
            }
        }.execute();
    }


    @Override
    public void onCreate() {
        if (mVerboseLevel >= 1) System.out.println("    in LassieBotService.onCreate");
        Log.d(TAG, "obtaining wake lock");
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lert Tag");
        dink = MediaPlayer.create(this, R.raw.dink);
        beep = MediaPlayer.create(this, R.raw.beep8);
        buzz = MediaPlayer.create(this, R.raw.buzzer_x);
        tick = MediaPlayer.create(this, R.raw.tick);
        tick.setLooping(true);
        SensorManager sensorMgr = (SensorManager) getSystemService(SENSOR_SERVICE);
        List<Sensor> sensors;
//        sensors = sensorMgr.getSensorList(Sensor.TYPE_ACCELEROMETER);
//        if(sensors.size() > 0) {
//            sensorMgr.registerListener(mShakeSensor, sensors.get(0), SensorManager.SENSOR_DELAY_NORMAL);
//            reschedule();
//        }
        sensors = sensorMgr.getSensorList(Sensor.TYPE_GYROSCOPE);
        if(sensors.size() > 0) {
            sensorMgr.registerListener(mShakeSensor, sensors.get(0), SensorManager.SENSOR_DELAY_NORMAL);
            reschedule();
        }
        mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                // Handlers for preferences that must run when changed. Other uses can query when needed.
                if(PREFS_KEY_TEST.equals(key)) {
                    test();
                    return;
                }
                if(PREFS_KEY_TIMEOUT_HOURS.equals(key)) {
                    int new_timeout_hours = prefs.getInt(key, DEFAULT_TIMEOUT_HOURS);
                    TIMEOUT_MILLIS = new_timeout_hours * 1000 * 60 * 60; // 30000 is good for testing.
                    if(prefs.getBoolean(PREFS_KEY_RUNNING, false)) {
                        reschedule();
                        vibes.cancel();
                        if(tick.isPlaying())
                            tick.pause();
                    }
                }
            }
        };
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, PREFS_SHARE_MODE);
        prefs.registerOnSharedPreferenceChangeListener(mPrefListener);
        vibes = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (mVerboseLevel >= 1) System.out.println("    out LassieBotService.onCreate");
    } // end onCreate()

    @Override
    public void onDestroy() {
        if (mVerboseLevel >= 1) System.out.println("    in LassieBotService.onDestroy");
        Log.d(TAG, "releasing wake lock");
        wakeLock.release();
        //Toast.makeText(this, "Lert Service stopped", Toast.LENGTH_LONG).show();
        ((SensorManager) getSystemService(SENSOR_SERVICE)).unregisterListener(mShakeSensor);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, PREFS_SHARE_MODE);
        prefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        prefs.edit().putBoolean(PREFS_KEY_RUNNING, false).commit();
        if (mVerboseLevel >= 1) System.out.println("    out LassieBotService.onDestroy");
    }

    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        if (mVerboseLevel >= 1) System.out.println("    in LassieBotService.onStartCommand");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, PREFS_SHARE_MODE);
        prefs.edit().putBoolean(PREFS_KEY_RUNNING, true).commit();
        //Toast.makeText(this, "Lert Service started", Toast.LENGTH_LONG).show();
        wakeLock.acquire();

        // The following sets the service in the "foreground". That term is a bit confusing
        // because services are always run in the background. To Android, a foreground service
        // simply means it's *not* OK with us for the system to ever kill it unless in desperation.
        // From http://stackoverflow.com/questions/3687200/implement-startforeground-method-in-android
        Intent intent = new Intent(this, LassieBotActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendIntent = PendingIntent.getActivity(this, 0, intent, 0);
        String activity_name = getString(getApplicationInfo().labelRes);
        Notification notice = new Notification(R.drawable.dog_robot_orig48,
            activity_name + " activated", System.currentTimeMillis());
        notice.setLatestEventInfo(this, activity_name, "is on the job, yo!", pendIntent);
        notice.flags |= Notification.FLAG_NO_CLEAR;
        startForeground(LERT_SERVICE_ID, notice);

        // Immediately pick up any stored timeout preference.
        mPrefListener.onSharedPreferenceChanged(prefs, PREFS_KEY_TIMEOUT_HOURS);

        int answer = super.onStartCommand(startIntent, flags, startId);
        if (mVerboseLevel >= 1) System.out.println("    out LassieBotService.onStartCommand");
        return answer;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (mVerboseLevel >= 1) System.out.println("    in LassieBotService.onBind");
        if (mVerboseLevel >= 1) System.out.println("    out LassieBotService.onBind");
        return null;
    }
}
