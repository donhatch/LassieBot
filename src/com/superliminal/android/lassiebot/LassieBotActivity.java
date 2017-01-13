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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mServiceIntent = new Intent(LassieBotActivity.this, LassieBotService.class);
        mPrefs = getSharedPreferences(LassieBotService.PREFS_NAME, LassieBotService.PREFS_SHARE_MODE);
        setContentView(R.layout.lert);
        final IntSpinner intSpinner = (IntSpinner) findViewById(R.id.timeout_spinner);
        int timeout_hours = mPrefs.getInt(LassieBotService.PREFS_KEY_TIMEOUT_HOURS, LassieBotService.DEFAULT_TIMEOUT_HOURS);
        boolean configuring = mPrefs.getBoolean(LassieBotService.PREFS_KEY_CONFIGURE, false);
        LassieBotService.CONFIGURE = configuring;
        intSpinner.setAll(LassieBotService.CONFIGURE ? 0 : 1, 24, timeout_hours);
        intSpinner.addListener(new IntSpinnerListener() {
            @Override
            public void valueChanged(int new_val) {
                mPrefs.edit().putInt(LassieBotService.PREFS_KEY_TIMEOUT_HOURS, new_val).commit();
            }
        });
        // Initialize configuration controls. 
        final CheckBox configureCheckBox = (CheckBox) findViewById(R.id.calibrate);
        configureCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                LassieBotService.CONFIGURE = isChecked;
                mPrefs.edit().putBoolean(LassieBotService.PREFS_KEY_CONFIGURE, isChecked).commit();
                updateControls();
            }
        });

        final ToggleButton toggle = ((ToggleButton) findViewById(R.id.toggle));
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean toggleChecked) {
                if(toggleChecked)
                    startService(mServiceIntent);
                else
                    stopService(mServiceIntent);
                updateControls();
            }
        });
        // Setting a dummy click listener enables default button click sound. Go figure.
        toggle.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {}
        });
        // Listen for service running state changes. Most of the time the changes come from
        // this Activity when the user toggles the start/stop button, but the service also
        // updates it when the alarm goes off and it stops itself.
        runningListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                if(!LassieBotService.PREFS_KEY_RUNNING.equals(key))
                    return;
                toggle.setChecked(prefs.getBoolean(key, false));
            }
        };
        mPrefs.registerOnSharedPreferenceChangeListener(runningListener);
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
                updateControls();
            }
        });

        class SeekAdapter implements SeekBar.OnSeekBarChangeListener {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
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
        return numbers;
    }

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
