# Build it yourself

You can also build the app and install it from a computer, over the network with `adb`. If the TV already
has the downloaded app, uninstall that first: the two are signed with different keys.

On your computer (macOS or Linux; on Windows, use WSL) you need:

- **git**.
- **JDK 17 or newer**, e.g. [Temurin](https://adoptium.net/temurin/releases/) or `brew install openjdk`.
  `install.sh` finds it on a Mac by itself; elsewhere, have it on your `PATH` or set `JAVA_HOME` to it.
- **The Android SDK**. The easiest way is [Android Studio](https://developer.android.com/studio), which
  installs it to `~/Library/Android/sdk` (Mac) or `~/Android/Sdk` (Linux). Without Android Studio, get the
  [command-line tools](https://developer.android.com/studio#command-line-tools-only) and run
  `sdkmanager platform-tools`. Then point the build at it: `export ANDROID_HOME=~/Library/Android/sdk` (or
  your path), or a line `sdk.dir=/path/to/sdk` in a `local.properties` file in this folder. Gradle downloads
  the rest of what it needs on the first build.

The computer and the TV must be on the same network.

1. Get the code:

   ```bash
   git clone https://github.com/MyronL/ultiworld-tv.git && cd ultiworld-tv
   ```

2. On the TV, turn on developer options: open **About**, then press OK on its build number seven times.
   - Google TV: **Settings → System → About → Android TV OS build**
   - Older Android TV: **Settings → Device Preferences → About → Build**

   Then, in **Developer options** (next to *About*), turn on **USB debugging**, or *Network debugging* if
   your TV lists it separately.
3. On your computer, with the TV's IP address (Google TV: **Settings → Network & Internet →** your
   network; older Android TV: **Settings → Network**):

   ```bash
   TV_HOST=192.168.1.50 ./install.sh
   ```

   The first time, the TV asks whether to allow this computer: choose *Always allow*.

   If it can't reach the TV and your TV has **Wireless debugging** instead of a plain switch, pair once
   first. On the TV, open **Wireless debugging → Pair device with pairing code**, then run
   `"$ANDROID_HOME/platform-tools/adb" pair <ip>:<pairing port>` and type the code. Then install using the
   port shown on the *Wireless debugging* screen itself, which isn't the pairing port:
   `TV_HOST=<ip> TV_PORT=<port> ./install.sh`.

4. On the TV, log in once with your Ultiworld account.
5. Turn USB debugging off again if you like. The app stays installed and logged in.

To update, turn USB debugging back on, run `git pull`, then `./install.sh` again from the same computer.
The app is signed with that computer's own key, so to install from a different computer, uninstall it from
the TV first (`install.sh` says so if you forget). You'll need to log in again after that.

## Develop

```bash
export JAVA_HOME=/path/to/jdk    # JDK 17 or newer, e.g. $(/usr/libexec/java_home -v 17+) on a Mac
./gradlew ktlintFormat testDebugUnitTest lintDebug    # format, unit tests, Android lint
./gradlew assembleDebug                              # app/build/outputs/apk/debug/app-debug.apk
```

`testDebugUnitTest` also writes the page scripts from `Scripts.kt` to `app/build/scripts/*.js`, so you can
`node --check` them or try one in a browser console.

The launcher banner is a vector drawable generated on a Mac by
`swift tools/banner.swift app/src/main/res/drawable/banner.xml`, which turns its lettering into outlines.
