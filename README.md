# Ultiworld TV

An Android TV / Google TV app for watching [Ultiworld](https://ultiworld.com) with a remote: live games,
upcoming streams, replays, event and team packs, search, and today's scores. It uses your own Ultiworld
subscription and plays the same streams the website does.

Unofficial: not made by, endorsed by or affiliated with Ultiworld. You need your own Ultiworld account, and
the app only plays what that account can already watch on the website, under the site's device limit. It
isn't in any app store; you build it and install it on your TV yourself (below).

## What's on screen

- **Home**: the video library's rows in the site's order (Live Now, Upcoming Videos, Recent Videos, Recent
  Events, Team Packs, Special Features), plus today's games from the score feed: *Games in progress*,
  *Coming up today* and *Today's results*, with team logos, seeds and scores.
- **Search**: teams, players, events. Results update as you type. Also on the remote's search key, if it
  has one.
- **Hide scores**: a spoiler switch at the top of Home. It's remembered between launches.
- **Events, team packs, features**: open one to see every video in it.
- **Games with two streams** (a game's own camera and the shared Field Pass): pick one.
- **Player**: full screen, with the remote's play/pause and left/right seeking. Live games can be rewound.

## Logging in from a phone

The login screen shows a QR code for a small page served by the TV itself, on your home network, while that
screen is open: `http://<tv>:8765/?k=<one-time code>`
([`PhoneLoginServer`](app/src/main/java/local/ultiworldtv/login/PhoneLoginServer.kt)). What you type there
goes through the same login as the TV's form. Requests without the code are refused, it takes 8 tries at
most, and it stops after a successful login or when you leave the screen. It's plain HTTP inside your home
network, so the login crosses your Wi-Fi unencrypted.

## How it works

Ultiworld is a WordPress site behind Cloudflare, so a plain HTTP client gets turned away. The app keeps one
WebView on an ultiworld.com page ([`WebBridge`](app/src/main/java/local/ultiworldtv/bridge/WebBridge.kt)) and
does everything from inside it, the way the website itself does:

| Need | Where it comes from |
|---|---|
| Login | the app's own form (or a phone, below) fills in and submits Ultiworld's real login form in the WebView; only the cookie is kept. Ultiworld's page itself is the fallback |
| Library rows | `/video/`, fetched in the page and read (`.video-grid`), without navigating to it |
| An event's or pack's videos | `/video/?event=…` and friends, fetched and read the same way |
| Search | the site's own search index (Algolia), queried with the search-only key the site publishes on its pages, limited to library videos |
| Scores | the public JSON feed behind the site's score bar, `/wp-json/uw/v1/scorebar/` |
| The stream | the video page: the site's paywall script swaps in a `<mux-player>`, whose playback ID and signed token the app reads, then plays `stream.mux.com/<id>.m3u8` natively with Media3 |
| Device limit | the site's check-in, `POST /wp-json/uw/v1/heartbeat/` with `streaming=true` every 80 s while playing, `false` otherwise. "Too many devices" stops playback here too |

The WebView blocks ads, analytics and the page's own video player, which a TV doesn't need. All of the
site-specific JavaScript is in [`Scripts.kt`](app/src/main/java/local/ultiworldtv/bridge/Scripts.kt); when
Ultiworld changes its pages, that's the file to fix.

## What it does and doesn't do

- **No way around the paywall.** The app opens your own video pages and waits for Ultiworld's own paywall
  check. It plays a video only when the site hands your account the player and its signed stream link. A
  video your plan doesn't include shows Ultiworld's own "requires…" message.
- **Device limit.** It sends the same check-ins the site's player sends, and stops when Ultiworld says
  you're streaming on too many devices.
- **Stream requests** go to Mux with ultiworld.com as the referrer, as they do from the website's player.
- **Blocked in the hidden page:** ads, analytics (including Mux's viewing stats) and the page's own player,
  to spare the TV's CPU. Ultiworld doesn't see these plays in its analytics.
- **Your password** goes only into Ultiworld's own login form and isn't stored; the TV keeps the login
  cookie, as a browser does.

Ultiworld is a trademark of its owners and is used here only to say what the app is for. If you work at
Ultiworld and want something changed, open an issue.

## Install on the TV

You need JDK 17 and the Android SDK (its `platform-tools` for `adb`), with `ANDROID_HOME` set or `sdk.dir`
in `local.properties`.

1. On the TV: turn on developer options (**Settings → System → About**, press OK on *Android TV OS build*
   seven times), then **Settings → System → Developer options → USB debugging** (or *Network debugging*).
2. On your computer, with the TV's IP address from **Settings → Network**:

   ```bash
   TV_HOST=192.168.1.50 ./install.sh
   ```

   The first time, the TV asks whether to allow this computer: choose *Always allow*.

3. On the TV, log in once with your Ultiworld account: type it with the remote (OK on a field opens the
   keyboard), or scan the QR code with your phone and type it there.
4. Turn USB debugging off again if you like. The app stays installed and logged in.

## Develop

```bash
export JAVA_HOME=/path/to/jdk-17    # e.g. $(/usr/libexec/java_home -v 17) on a Mac
./gradlew ktlintFormat testDebugUnitTest lintDebug    # format, unit tests, Android lint
./gradlew assembleDebug                              # app/build/outputs/apk/debug/app-debug.apk
```

`testDebugUnitTest` also writes every page script to `app/build/scripts/*.js`, wrapped the way the app runs
it, so you can `node --check` them or paste one into a browser console on ultiworld.com to test it against
the live site.

The launcher banner is drawn by `swift tools/banner.swift app/src/main/res/drawable-xhdpi/banner.png`.

## License

[MIT](LICENSE). Use it, change it, share it; it comes with no warranty.
