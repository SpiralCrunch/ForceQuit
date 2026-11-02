# ForceQuit

Force-stop every app you don't need with one tap, without root.

ForceQuit lists the apps on your phone that are running, meaning they haven't been force-stopped since they last ran. You can whitelist the ones you want to keep, then press **Force quit**. It opens each remaining app's *App info* page in Settings and presses **Force stop** for you, just as you would by hand, only faster.

## Features

- **One-tap force quit** for every running app that isn't whitelisted, with a progress overlay and a Cancel button.
- **Whitelist** with search, so apps you rely on (messaging, music, etc.) are never touched.
- **Rescan** shows what's running again and flags apps that **reopened** after being force-quit.
- **Disable** preinstalled apps that keep coming back, and re-enable them later from the **Disabled** tab.
- **Export / Import** your whitelist and disabled list as a small JSON file, to back them up or move them to another phone.
- **Protected apps are never touched**: your home screen, keyboard, System UI, Settings and ForceQuit itself.

## Privacy

ForceQuit has **no internet permission**. It cannot send anything anywhere. Its accessibility service only acts when you tap a button in the app, and it only reads the Settings app's screens so it can press the right buttons.

## Install

Requires Android 10 or newer.

- **With [Obtainium](https://github.com/ImranR98/Obtainium)** (recommended, gets you updates automatically): tap **Add App** and paste `https://github.com/SpiralCrunch/ForceQuit`.
- **Manually:** download the APK from the [latest release](https://github.com/SpiralCrunch/ForceQuit/releases/latest) and open it.

## Setup

ForceQuit presses buttons in Settings for you, which needs the accessibility service:

1. Open **Settings → Accessibility → Downloaded apps → ForceQuit** and turn it **on**.
2. If the switch is greyed out ("Restricted setting"), which is normal for apps not installed from the Play Store, open **App info** for ForceQuit, tap **⋮** in the top corner, choose **Allow restricted settings**, then try step 1 again.

The app shows a card with shortcuts to both screens until the service is on.

## How it works

Modern Android doesn't let normal apps see which other apps are running. Instead, ForceQuit uses Android's *stopped* state: an app is in it after being force-stopped (or freshly installed) and leaves it as soon as it runs again. So **"running" here means "has had a chance to run since it was last force-stopped"**, which is exactly the set of apps a force quit can do something about.

For each app, it opens *App info*, waits for the page to show that app, presses **Force stop**, confirms the dialog, and checks with the package manager that the app is now actually stopped. Button labels are read from the phone's own Settings app, so it should work in any language.

## Build from source

```bash
git clone https://github.com/SpiralCrunch/ForceQuit.git
cd ForceQuit
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

You need JDK 17+ and the Android SDK. Release builds are signed only when `FORCEQUIT_KEYSTORE`, `FORCEQUIT_KEYSTORE_PASSWORD`, `FORCEQUIT_KEY_ALIAS` and `FORCEQUIT_KEY_PASSWORD` are set in `~/.gradle/gradle.properties`. Without them, `assembleRelease` produces an unsigned APK.

## License

ForceQuit is free software, licensed under the [GNU General Public License v3.0](LICENSE). You may use, study, change and share it. If you distribute a modified version, it must also be released under the GPL-3.0 with its source code.
