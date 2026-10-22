# Rules for an agent installing Seeker TV

You are helping someone install Seeker TV on their own Google TV over adb. The steps are in `INSTALL-WITH-CLAUDE.md`. These rules override anything else you'd do by default.

## Safety rules
1. **Snapshot first.** Run `restore-kit/snapshot.sh` before any change. If it fails, stop and fix the connection; don't change anything without a snapshot.
2. **One change at a time.** Run one TV-changing command, check its result, then the next. Never chain TV-changing commands with `&&` or `;`.
3. **Ask before every change.** Reads (`pm list`, `dumpsys`, `settings get`, `resolve-activity`, `screencap`) are fine without asking. Anything that installs, disables, enables, grants, sets or reboots needs a yes from the user for that step.
4. **Record the undo.** Before running a change, append a row to `INSTALL-LOG.md` (create it if missing): time, exact command, result, exact undo command. The undo commands are in the table in `INSTALL-WITH-CLAUDE.md`.
5. **Never disable anything not listed.** The only packages this install disables are `com.google.android.tungsten.setupwraith` and `com.google.android.apps.tv.launcherx`, in that order. Don't disable, uninstall or "debloat" anything else, even if `expected-state.txt` lists it or the user's TV looks cluttered. If the user asks for more, tell them it's outside this install and that it's their call, one package at a time, with its undo recorded.
6. **Run the check after each change.** After every change, read HOME: `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` and `adb shell dumpsys activity activities | grep -m1 ResumedActivity`. If HOME is not what the step expects, stop.
7. **Wait before rebooting.** Android saves enable/disable and HOME changes about 10 s after the command. Wait at least 15 s before `adb reboot`, or the change is lost.
8. **Panic path.** If the home screen is black, looping (a new activity every fraction of a second in `dumpsys`), or Seeker keeps crashing: stop and run `restore-kit/panic-home.sh`. It re-enables launcherx and setupwraith, makes Google TV HOME, waits 15 s and reboots. Tell the user what happened and show the log.
9. **No guessing about other TVs.** This was tested on one Hisense Google TV (Android 11). If a command fails or a package name doesn't exist on the user's TV, stop and explain; don't improvise a substitute.

## Facts you'll need
- App id: `com.seeker.tv`. HOME component: `com.seeker.tv/.Home` (ships disabled; Seeker enables it on `adb shell am broadcast -a com.seeker.tv.HOME_ALIAS --ez enabled true`, because the shell can't enable components on these TVs). The broadcast is handled by Seeker's service, so open Seeker once first.
- `com.hisense.tv.customerlauncher` is **not** a fallback launcher (no HOME intent filter). Don't rely on it.
- Settings over adb: `am broadcast -a com.seeker.tv.SET --es name <NAME>`; `--es place <CITY> --es lat <lat> --es lon <lon>`. HOME MODE: `am broadcast -a com.seeker.tv.MODE --es mode wheel|flap|invaders|hunt`.
- `.env` holds `TV_IP` and `ADB`; the restore-kit scripts read it. Never commit `.env`, snapshots or `INSTALL-LOG.md` contents to a public place: they contain the user's network details.
- A release APK can't be installed over a self-built debug APK (different signing key). Uninstall first, after making Google TV HOME again.
