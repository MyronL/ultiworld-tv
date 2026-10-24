# Releasing

Each release is an APK on the GitHub releases page, built and signed by
[`release.yml`](../.github/workflows/release.yml) when a version tag is pushed. People install it on the TV
with the Downloader app (see the README), and the newest one is always at:

    https://github.com/MyronL/ultiworld-tv/releases/latest/download/ultiworld-tv.apk

## The release key (once)

Every release must be signed with the same key: Android only installs an APK over the app if the signatures
match. If the key is lost, nobody can update without uninstalling first, and logging in again. So make it once
and keep a copy of the file and its password somewhere safe, such as a password manager. Never commit it
(`.gitignore` already skips `*.jks`).

```bash
keytool -genkeypair -keystore ~/ultiworld-tv-release.jks -alias ultiworld-tv -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Ultiworld TV"
```

It asks for a password. The keystore is PKCS12, so the key itself uses the same password.

Then hand it to GitHub Actions as four repository secrets. The two `gh secret set` commands without `--body`
ask for the value, so the password doesn't end up in your shell history:

```bash
base64 < ~/ultiworld-tv-release.jks | gh secret set RELEASE_KEYSTORE_BASE64 -R MyronL/ultiworld-tv
gh secret set RELEASE_KEYSTORE_PASSWORD -R MyronL/ultiworld-tv
gh secret set RELEASE_KEY_PASSWORD -R MyronL/ultiworld-tv
gh secret set RELEASE_KEY_ALIAS -R MyronL/ultiworld-tv --body ultiworld-tv
```

## Each release

1. In [`app/build.gradle.kts`](../app/build.gradle.kts), raise `versionCode` by one and set `versionName`, e.g.
   `versionCode = 2`, `versionName = "0.2"`. Commit and push to `main`, and wait for CI to pass.
2. Tag that commit and push the tag:

   ```bash
   git tag v0.2 && git push origin v0.2
   ```

3. The *Release* workflow checks that the tag matches `versionName` and that `versionCode` is higher than the
   last release's, runs the checks, builds the signed APK and
   publishes the release with `ultiworld-tv.apk`, its SHA-256 and notes generated from the commits. Edit the
   notes on GitHub afterwards if you like.

Within a few hours, TVs with the app show an **Update** button on Home (`Updater` asks GitHub's
`releases/latest`). Only APKs built with the release key do this.

Downloads, and that check, need the repository to be public; while it's private, only you can download the APK.

To build a signed APK on your own computer instead, set the same four values as environment variables, with
`RELEASE_KEYSTORE` pointing at the `.jks` file in place of the base64 one, and run `./gradlew assembleRelease`.

## The Downloader code

The full link is long to type with a remote, so the README also gives the Downloader code **2714454**, made on
AFTVnews's site (go.aftvnews.com) for the link above. It always leads to the newest release, so it never needs
changing.
