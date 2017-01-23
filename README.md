Issues:

  1. Don't stop the countdown racket unless stopping the countdown.

     This code in the listener for TIMEOUT_HOURS changes
     turns off the countdown racket but it doesn't stop the countdown:

         'vibes.cancel();
         if(tick.isPlaying())
             tick.pause();'

     Probably the only place the racket should be cancelled is where the countdown is actually stopped.

  2. Changing Hours does not stop an in-progress 30-second countdown

     I'm guessing changing Hours should stop the 30-second countdown;
     it *appears* to stop it due to Issue #1, but it doesn't actually stop it.
     To reproduce:
       0. Set gyro sensitivity to low, and place phone on table so no shakes will be detected.
       1. Set Hours spinner to 0.  Racket starts, signifiying there's a 30-second countdown in progress.
       2. Set Hours spinner to 1.  Racket stops (due to Issue #1), but 30-second countdown continues silently!
       3. Wait for now-silent 30-second countdown to expire.  Text gets sent.

  3. Multiple 30-second countdown threads can happen simultaneously.

     This is an extension of Issue #2 ("Changing Hours does not stop an in-progress 30-second countdown").
     To reproduce:
       0. Set gyro sensitivity to low, and place phone on table so no shakes will be detected.
       1. Set Hours spinner to 0.  Racket starts, signifiying there's a 30-second countdown in progress.
       2. Set Hours spinner to 1.  Racket stops, but 30-second countdown continues silently.
       3. Set Hours spinner to 0.  Racket starts again. Now there are 2 30-second countdowns in progress.
       4. Set Hours spinner to 1.  Racket stops, the two 3-second countdowns continue silently.
       (repeat several times if desired, to get as many simultaneous countdowns as desired).

      You can verify that there are multiple countdowns happening at once by looking at the logcat screen;
      the "diff = " warning will come out several at a time.

      Then additional bad things happen when the first of the threads counts down to 0 and sends the text:
      it then calls stopService() which causes onDestroy() to be called, which causes tick.release()
      to get called.  Then the second timer thread gets to the end of the countdown
      and calls tick.pause(), which crashes the app because tick has been released.

  4. Stopping service does not stop the deadManSwitch.

     To reproduce:
       0. Tweak source code so Hours is interpreted as minutes.  (remove a "* 60").
       1. Start the app (activity).
       2. Set Timeout Hours (really minutes) to 1.
       3. Turn on the service.
       4. Turn off the service.
       5. Optionally, close the activity.
       6. Wait 1 minute for timeout to expire.
     The racket starts, signifying the 30-second countdown, and the text gets sent,
     which is a surprise since the service isn't even running!
     And shaking the phone doesn't stop the 30-second countdown,
     since the sensor listener has been deactivated.

  5. App crashes when 30-second countdown starts when text already sent.

     To reproduce, follow the reproduction steps for 2 ("Changing Hours does not stop an in-progress
     30-second countdown"), through when the text gets sent and the button turns red.
     At that point the services's onDestroy() gets called
     which shuts down its listening for sensors, but its deadManSwitch is still
     running and is impervious to shakes (see Issue #4).

     Then wait another minute (assuming you tweaked the source code so "Hours" = minutes)
     until it tries to start another countdown.
     The application crashes here:

         'E/AndroidRuntime: FATAL EXCEPTION: Timer-5
           Process: com.superliminal.android.lassiebot, PID: 20571
           java.lang.IllegalStateException
               at android.media.MediaPlayer._start(Native Method)
               at android.media.MediaPlayer.start(MediaPlayer.java:1213)
               at com.superliminal.android.lassiebot.LassieBotService$LertAlarm.run(LassieBotService.java:119)
               at java.util.TimerThread.mainLoop(Timer.java:555)
               at java.util.TimerThread.run(Timer.java:505)'

     This is the call to tick.start() in LertAlarm.run(),
     which is illegal since tick.release() was called from onDestroy() earlier.

  6. Document which code can be run in the Timer thread.

     It would be good to go over all code reachable in the thread (i.e. reachable from LertAlarm.run())
     and label all of it with comments saying the intent, either:
     "called in Timer-created-thread only" or "called in both main thread and Timer-created thread".

  7. Review code callable from Timer thread for thread safety.

     After Issue #6 (identify which code is intended to be callable from Timer thread) is done,
     review that code for thread-safety, e.g. variables being accessed
     from both main thread and timer thread without needed synchronization.

  8. mShakeSensor.mLastStrongShake is accessed from both threads without synchronization.

  9. Are the MediaPlayer calls thread-safe?

     There are calls to things like tick.pause(), vibes.cancel() etc. in both the main thread and the Timer thread.
     Are these all known to be thread safe?  I didn't see anything about that when I briefly scanned the MediaPlayer documentation.
     If they are not thread safe, they need to be surrounded by appropriate synchronized().

  10. "Off when charging" + Hours=0 = unfriendly cpu-consuming loop.
     If you have "Off when charging" checked and set the Hours spinner to 0,
     it goes into an unfriendly cpu-consuming loop, continually calling reschedule()
     and then going off immedietely.

==========================================================================

Build recipes known to work:

  * On Linux:

    * In Android Studio:
      * Build -> Build APK

        If you get an error like "Failed to find target with hash string 'android-14' ...":
          * Tools -> Android -> SDK Manager

            Find the SDK Platform with API level matching the error message, check the checkbox, and install it.

    * Command line:

      The gradlew script in the repo won't work out of the box--
      it gives the following error:
        > SDK location not found. Define location with sdk.dir in the
        local.properties file or with an ANDROID_HOME environment variable.

      You gotta do what it says.  The simplest way is to open
      the project in Android Studio (no need to build it there),
      which will create an appropriate local.properties file.
      Or, you can try to create local.properties yourself-- the contents should
      describe a location that exists, something like this if you've run
      Android Studio some time in the past on Linux:

        > `sdk.dir=/home/joe/Android/Sdk`

      or, on Windows, maybe something like this:

        > `sdk.dir=C\:\\Users\\Joe Blow\\AppData\\Local\\Android\\Sdk`

      or on mac:

        > `sdk.dir=/Users/joe/Library/Android/sdk`

      If you get that right, commands like the following should work:
        * `./gradlew assembleDebug`
        * `./gradlew installDebug`

  * On Mac:

    * Android Studio: Similar to linux.

    * Command line: Similar to Linux.

      At first gradle failed for me with errors like:
       > `ava.lang.UnsupportedClassVersionError: com/android/build/gradle/AppPlugin : Unsupported major.minor version 52.0`

      and:
       > `Buildtools 25.0.2 requires Java 1.8 or above.  Current JDK version is 1.7.`

      until I installed Java8 on my machine; then it worked fine.
