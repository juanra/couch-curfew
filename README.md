# Couch Curfew

Per-app daily time limits and a kids mode for Android TVs running [Free My TV](https://github.com/simbasjungles/free-my-tv).

Couch Curfew is an **unofficial companion** to Free My TV, not part of it. Free My TV replaces the TV's ad-filled home
screen; Couch Curfew adds what a family TV also needs: "YouTube, 60 minutes a day" while the kids are watching.

## What it does

- **Kids mode, behind a parent PIN.** Limits only count while kids mode is on, so grown-ups watch without a timer.
- **A daily limit per app**: 15 minutes to 3 hours, or no limit. Time resets at midnight.
- **When time is up** the app closes, the TV goes home and the app stays paused (`pm suspend`) until midnight. One
  minute before, a warning shows.
- **+30 minutes today** for one app, without changing its limit.
- **Settings is locked in kids mode**, so the limits can't be switched off from the TV's menus. The Play Store is
  locked too: Android refuses to pause it, so Couch Curfew sends the TV home whenever it opens.

Everything is set up on the TV itself, behind the PIN. No account, no cloud, no tracking: the only network use is the
TV's own ADB on `127.0.0.1`.

## How it works

Like Free My TV, Couch Curfew talks to the TV's own ADB (network debugging, `127.0.0.1:5555`) to run a few fixed
commands: `pm suspend`/`pm unsuspend` and `am force-stop` on the apps you limit, and turning its accessibility service
on. The accessibility service only receives window changes (which app is in front); it never reads the screen. The
ADB client is adapted from Free My TV's, under its MIT license.

## Install

1. On the TV, turn on **Developer options → Network debugging** (Free My TV's setup already did this if you use it).
2. Build the APK (below) and install it from a computer on the same network:
   ```sh
   adb connect <tv-ip>:5555
   adb install -r CouchCurfew.apk
   ```
3. Open **Couch Curfew** from the TV's apps, create the parent PIN and pick **Setup**. The TV asks once to allow USB
   debugging for Couch Curfew: check **Always allow** and accept.
4. Pick a limit for each app you want to limit, then turn **Kids mode** on.

TCL and other TVs that kill background apps: Setup also grants `APP_AUTO_START`, so the midnight reset and the restore
after a reboot can run.

## Build

Needs a JDK 17+ and the Android SDK command-line tools (build-tools 34+, platform android-34). No Gradle.

```sh
./test.sh    # the time and PIN rules, on your computer
./build.sh   # CouchCurfew.apk, signed with couchcurfew.keystore (created on the first build)
```

Keep `couchcurfew.keystore`: updates must be signed with the same key.

## Limits (read this)

Couch Curfew is a speed bump for young kids, not a lock for a determined teenager. It can't stop:

- **HDMI devices** (a console, a streaming stick): they don't go through the TV's apps.
- **A factory reset**, or turning off network debugging from Developer options before kids mode is on.
- **Free My TV's own settings**, since Free My TV is the home screen. They don't switch Couch Curfew off.
- **Anything during the first seconds** after the TV boots, before the service starts.

## Recovery

Forgot the PIN? From a computer: `adb shell pm clear cc.nunez.couchcurfew` (this also clears the limits). If the app
is removed while apps are paused, resume them with `adb shell pm unsuspend <package>`; Settings is
`com.android.tv.settings` on most Android TVs.

## Not yet

Bedtime schedules, a combined daily total, HDMI inputs.

## License

MIT. See [LICENSE](LICENSE).
