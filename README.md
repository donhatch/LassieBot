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
