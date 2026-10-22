# Installing Seeker TV with Claude Code

This is for you if you'd rather have an AI agent do the adb work. You run [Claude Code](https://claude.com/claude-code) in this folder; it reads `CLAUDE.md` (the rules it must follow) and walks the steps below with you, one change at a time, writing down how to undo each one.

You stay in charge: Claude Code asks before every command that changes the TV.

## Before you start (5 minutes, by hand)

1. **Download this repo** (green Code button > Download ZIP, then unzip it) and the APK you want from [Releases](../../releases). If you're not sure which: `seeker-all-0.9.0.apk` has every mode; `seeker-wheel-0.9.0.apk` is the smallest. Put the APK in this folder.
2. **Install adb** on your computer. Mac: `brew install android-platform-tools`. Windows/Linux: [platform-tools](https://developer.android.com/tools/releases/platform-tools).
3. **Turn on debugging on the TV:** Settings > System > About > press OK on **Android TV OS build** 7 times ("You are now a developer"). Then Settings > System > **Developer options** > **USB debugging** ON (and **Network debugging** ON if it's there).
4. **Find the TV's IP address:** Settings > Network & Internet > your Wi-Fi network.
5. **Install Claude Code** and open a terminal in this folder: `cd path/to/Seeker-TV-Launcher` then `claude`.

## The first prompt (paste this)

Replace the IP address and the APK name, then paste it as your first message:

```
Read CLAUDE.md and INSTALL-WITH-CLAUDE.md, then install Seeker TV on my TV, following CLAUDE.md's rules.
My TV's IP address is 192.0.2.10. The APK is seeker-all-0.9.0.apk in this folder.
Do the steps in order and ask me before each one that changes the TV:
1. Create .env from .env.example with my TV_IP. Connect with adb and tell me when to accept the prompt on the TV.
2. Run restore-kit/snapshot.sh (read-only) and show me the current HOME and the HOME candidates.
3. Install the APK with adb install -r.
4. Grant the two optional permissions (WRITE_SECURE_SETTINGS, SYSTEM_ALERT_WINDOW app-op) and open Seeker once.
5. Make Seeker HOME: HOME_ALIAS broadcast, disable setupwraith, then launcherx, then set-home-activity. Wait 15 s.
6. Verify: HOME resolves to com.seeker.tv/.Home, press Home, check the resumed activity, reboot, verify again.
7. Ask me for my name and city and set them with the com.seeker.tv.SET broadcast.
Record every change and its undo in INSTALL-LOG.md. If the home screen breaks at any point, stop and run restore-kit/panic-home.sh.
```

## What Claude Code will do (so you can follow along)

| Step | Command (Claude runs it) | Undo |
|---|---|---|
| Connect | `adb connect <TV_IP>:5555`, then `adb devices` shows `device` | `adb disconnect` |
| Snapshot | `restore-kit/snapshot.sh` (read-only, saves to `restore-kit/snapshot-<date>/`) | none needed |
| Install | `adb install -r seeker-<flavor>-0.9.0.apk` | `adb uninstall com.seeker.tv` |
| Animations (optional) | `adb shell pm grant com.seeker.tv android.permission.WRITE_SECURE_SETTINGS` | `adb shell pm revoke com.seeker.tv android.permission.WRITE_SECURE_SETTINGS` |
| Toasts (optional) | `adb shell appops set com.seeker.tv SYSTEM_ALERT_WINDOW allow` | `adb shell appops set com.seeker.tv SYSTEM_ALERT_WINDOW default` |
| First start | `adb shell am start -n com.seeker.tv/.MainActivity` | none needed |
| HOME entry on | `adb shell am broadcast -a com.seeker.tv.HOME_ALIAS --ez enabled true` | `... --ez enabled false` |
| Disable the guard **first** | `adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith` | `adb shell pm enable com.google.android.tungsten.setupwraith` (after launcherx) |
| Disable Google TV's launcher | `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` | `adb shell pm enable com.google.android.apps.tv.launcherx` (first) |
| Make Seeker HOME | `adb shell cmd package set-home-activity --user 0 com.seeker.tv/.Home`, wait 15 s, `adb shell input keyevent KEYCODE_HOME` | `restore-kit/panic-home.sh` |
| Verify | `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` ends in `com.seeker.tv/.Home`; `adb shell dumpsys activity activities \| grep -m1 ResumedActivity` shows `com.seeker.tv`; `adb reboot`, reconnect, check both again | |
| Your name and city | `adb shell am broadcast -a com.seeker.tv.SET --es name ALEX` and `--es place PARIS --es lat 48.8566 --es lon 2.3522` (or use Seeker's settings: NAME, WEATHER LOCATION) | set them again |

Why this order: setupwraith guards launcherx. If launcherx is disabled while setupwraith is on, setupwraith's RecoveryActivity grabs HOME and loops. Re-enabling is the reverse: launcherx first, then setupwraith.

## If something goes wrong

- **Black screen, looping, or the home screen keeps crashing:** run `restore-kit/panic-home.sh` (from this folder). It turns Google TV's home screen back on, makes it HOME, waits 15 s and reboots. Give the TV a minute.
- **adb says `unauthorized`:** look at the TV; accept the "Allow USB debugging?" prompt with "Always allow".
- **adb says `connection refused`:** debugging got turned off (a factory reset or some updates do that). Redo "Before you start" step 3.
- **After a firmware update:** run `restore-kit/post-update-check.sh`. Updates can turn launcherx back on.
- **Want it all back to stock:** `restore-kit/restore-stock.sh`, or a factory reset (`restore-kit/RESET.md`). A factory reset always restores the stock TV.

## Switching modes or flavors later

- Mode: Seeker settings > HOME MODE, or `adb shell am broadcast -a com.seeker.tv.MODE --es mode hunt` (`wheel`, `flap`, `invaders`, `hunt`; only modes built into your APK work).
- Flavor: `adb install -r` a different `seeker-<flavor>` APK. Same app, so HOME and settings stay.
