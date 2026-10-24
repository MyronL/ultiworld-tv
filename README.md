# Ultiworld TV

An Android TV / Google TV app for watching [Ultiworld](https://ultiworld.com) with a remote: live games,
upcoming streams, replays, event and team packs, search, and today's scores. It uses your own Ultiworld
subscription and plays the same streams the website does.

![The Home screen: Live Now and Upcoming Videos rows](docs/home.jpg)

Unofficial: not made by, endorsed by or affiliated with Ultiworld. You need your own Ultiworld account, and
the app only plays what that account can already watch on the website, under the site's device limit. It
isn't in any app store; you download it onto the TV yourself (below).

## What's on screen

- **Home**: the video library's rows in the site's order (Live Now, Upcoming Videos, Recent Videos, Recent
  Events, Team Packs, Special Features), plus today's games from the score feed: *Games in progress*,
  *Coming up today* and *Today's results*, with team logos, seeds and scores.
- **Search**: find library videos by team, player or event. Results update as you type. Also on the
  remote's search key, if it has one.
- **Hide scores**: a spoiler switch at the top of Home. It's remembered between launches.
- **Events, team packs, features**: open one to see every video in it.
- **Games with two streams** (a game's own camera and the shared Field Pass): pick one.
- **Player**: full screen, with the remote's play/pause and left/right seeking. Live games can be rewound.

## Logging in from a phone

The login screen shows a QR code for a small page served by the TV itself, on your home network, while that
screen is open: `http://<tv>:8765/?k=<one-time code>` (another port if 8765 is taken)
([`PhoneLoginServer`](app/src/main/java/io/github/myronl/ultiworldtv/login/PhoneLoginServer.kt)). What you
type there goes through the same login as the TV's form. Requests without the code are refused, and after 8
tries that code stops working and the TV shows a new one. The page stops after a successful login, when you
leave the screen, or when the TV switches to another app. It's plain HTTP inside your home network, so the
login crosses your Wi-Fi unencrypted.

## How it works

Ultiworld's login, paywall and device limit all run in the site's own JavaScript, and the site sits behind
Cloudflare's browser check. So the app keeps one WebView on an ultiworld.com page
([`WebBridge`](app/src/main/java/io/github/myronl/ultiworldtv/bridge/WebBridge.kt)) and does everything from
inside it, the way the website itself does:

| Need | Where it comes from |
|---|---|
| Login | the app's own form (or a phone, above) fills in and submits Ultiworld's real login form in the WebView; only the cookie is kept |
| Library rows | `/video/`, fetched in the page and read (`.video-grid`), without navigating to it |
| An event's or pack's videos | `/video/?event=…` and friends, fetched and read the same way |
| Search | the site's own search index (Algolia), queried with the search-only key the site publishes on its pages, limited to library videos |
| Scores | the public JSON feed behind the site's score bar, `/wp-json/uw/v1/scorebar/` |
| The stream | the video page: the site's paywall script swaps in a `<mux-player>`, whose playback ID and signed token the app reads, then plays `stream.mux.com/<id>.m3u8` natively with Media3 |
| Device limit | the site's check-in, `POST /wp-json/uw/v1/heartbeat/` with `streaming=true` every 80 s while playing, `false` otherwise. "Too many devices" stops playback here too |

The hidden page doesn't load ads, tracking scripts or its own video player, which a TV doesn't need. All of
the site-specific JavaScript is in [`Scripts.kt`](app/src/main/java/io/github/myronl/ultiworldtv/bridge/Scripts.kt);
when Ultiworld changes its pages, that's the file to fix.

## What it does and doesn't do

- **No way around the paywall.** The app opens your own video pages and waits for Ultiworld's own paywall
  check. It plays a video only when the site hands your account the player and its signed stream link. A
  video your plan doesn't include shows Ultiworld's own "requires…" message.
- **Device limit.** It sends the same check-ins the site's player sends, and stops when Ultiworld says
  you're streaming on too many devices. The hidden page's own script would also send "not streaming" on
  every page load, which tells Ultiworld the TV gave up its stream mid-game, so the app blocks those and
  only its own check-ins speak for the TV. As on the website, a check-in that fails doesn't stop the video.
- **Stream requests** go to Mux with ultiworld.com as the referrer, as they do from the website's player.
- **Not loaded in the hidden page:** ads, tracking scripts and the page's own player (and with it Mux's
  viewing stats), to spare the TV's CPU. The video plays in the app's own player instead.
- **Updates:** the downloaded app asks GitHub every few hours whether there's a newer release, and downloads it
  only when you press **Update**. Android installs it only after you confirm, and only if it's signed with the
  same key as the app you have. Apps you build yourself don't check.
- **Your password** goes only into Ultiworld's own login form and isn't stored; the TV keeps the login
  cookie, as a browser does.

Ultiworld is a trademark of its owners and is used here only to say what the app is for. If you work at
Ultiworld and want something changed, open an issue.

## Install on the TV

It runs on Android TV and Google TV 8.0 or newer. Some TVs from cable companies, hotels or workplaces don't
allow apps from outside their store, and on those it can't be installed.

No computer needed:

1. On the TV, install **Downloader** by AFTVnews from the Play Store.
2. Open it and type this address into its box:

   ```
   https://github.com/MyronL/ultiworld-tv/releases/latest/download/ultiworld-tv.apk
   ```

3. When it has downloaded, choose **Install**. The first time, the TV asks you to let Downloader install
   apps: turn that on and go back. If your TV doesn't offer it (some Google TVs hide it), turn on developer
   options first: open **Settings → System → About** and press OK on **Android TV OS build** seven times,
   then try again. If Google Play Protect warns that it doesn't know the app, choose to install anyway.
4. Open Ultiworld TV from your apps and log in once with your Ultiworld account. Type it with the remote (OK
   on a field opens the keyboard), or scan the QR code with a phone on the same Wi-Fi and type it there.

When there's a new version, an **Update** button appears at the top of Home. Press it and confirm on the TV's
install screen. The first time, the TV asks you to let Ultiworld TV install apps, the same way it asked for
Downloader. The app closes to update; open it again and you're still logged in. Each release and what changed
is on the [releases page](https://github.com/MyronL/ultiworld-tv/releases).

To log out, use **Sign out** at the top of Home. To remove the app, uninstall it like any other app:
**Settings → Apps → Ultiworld TV → Uninstall**.

### Build it yourself

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

`testDebugUnitTest` also writes every page script to `app/build/scripts/*.js`, wrapped the way the app runs
it, so you can `node --check` them or paste one into a browser console on ultiworld.com to test it against
the live site.

Publishing a release is in [docs/releasing.md](docs/releasing.md).

The launcher banner is a vector drawable generated on a Mac by
`swift tools/banner.swift app/src/main/res/drawable/banner.xml`, which turns its lettering into outlines.

## Contributing

Pull requests are welcome. The most useful ones keep the app working when Ultiworld changes its site: almost all
of that lives in [`Scripts.kt`](app/src/main/java/io/github/myronl/ultiworldtv/bridge/Scripts.kt), and each script
can be tried in a browser console on ultiworld.com first (see *Develop*). Before opening one, run
`./gradlew ktlintFormat testDebugUnitTest lintDebug` (CI runs the same checks), and try the change on a TV if it
touches what's on screen. For bigger changes, open an issue first so we can agree on the approach.

The app stays a viewer for people's own subscriptions: changes that get around the paywall, the device limit or
anything else Ultiworld enforces won't be merged.

## License

[MIT](LICENSE). Use it, change it, share it; it comes with no warranty.

Built with [Jetpack Compose for TV](https://developer.android.com/jetpack/androidx/releases/tv),
[Media3 ExoPlayer](https://developer.android.com/media/media3), [Coil](https://coil-kt.github.io/coil/) and
[ZXing](https://github.com/zxing/zxing), all under the Apache License 2.0.
