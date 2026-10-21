# Vitrine Home

**A calm, glass home screen for Android TV and Google TV.**

Vitrine Home replaces your TV's busy home screen with your own photo, a clock, and a floating dock of
clean white app symbols. Everything else (all your apps, settings, menus) sits one button-press away
on frosted-glass panels, and fades away when you stop pressing buttons.

> It's a **launcher** (a home screen app), not an operating system. It runs on top of your TV's
> existing Android TV / Google TV software and can be removed at any time.

---

## Features

**Home**
- Your photo, full screen, with a clock, date and greeting
- A floating **dock** of white app symbols with a liquid-light focus line. Up to 10 apps on one row;
  11–20 apps split into two balanced, centred rows automatically
- **Quiet mode**: after 20 seconds without input, everything but the photo, clock and a dimmed dock fades away
- **Now Playing** pill above the dock while music or video plays (play/pause, previous/next)
- **Device card**: "PS5 is on, press OK to switch" when an HDMI device powers up (needs HDMI-CEC)
- Weather, air quality, calendar, Continue watching and a small system page in two glass widgets

**All apps** (press Down from the dock)
- Every app in a scrolling list with a large symbol preview
- Hold **OK** on any app to add it to the dock, move it, hide it, or open its info

**Glass Settings** (the gear button)
- Volume (Left/Right to change, OK to mute), Wi-Fi, Bluetooth, Inputs, Picture, Sound, Wallpaper,
  Screensaver, Apps, Storage, Backup, Date & time, About, Restart
- **Light / dark** glass (the sun/moon button on Home)
- **Evening mode**: a warm, slight dim from 9 pm to 6 am
- **Reduce motion**: fades only, no slides or stretch

**And**
- A matching **screensaver**: your wallpapers cross-fading slowly with the clock
- **Rename inputs** (Inputs → Rename inputs…) with matching icons
- **Automatic backup** of your setup, and one-command restore
- **Safety net**: after repeated crashes it starts in safe mode, and after more it hands Home back to
  the TV's own launcher. Hold **Back for 5 seconds** at any time to open the TV's own home screen

## Requirements

- An **Android TV or Google TV** device running Android 9 or later
- A computer with **[adb](https://developer.android.com/tools/releases/platform-tools)**
  and either **Android Studio** or **JDK 17+ with the Android SDK** (to build)
- TV and computer on the same network

## Install

1. **On the TV**, turn on developer options: *Settings → System → About*, select *Build* 7 times.
   Then turn on **USB debugging / Network debugging** in *Settings → System → Developer options*.
   Note the TV's IP address (*Settings → Network*).
2. **On your computer**, clone and install:

   ```sh
   git clone https://github.com/vikaskalyan-rgb/vitrine-home.git
   cd vitrine-home
   scripts/vitrine.sh install 192.168.1.50        # macOS / Linux
   scripts\vitrine.bat install 192.168.1.50       # Windows
   ```

   Replace `192.168.1.50` with your TV's IP. The first time, the TV asks to **allow debugging**: tick
   *Always allow* and press *Allow*.
3. Vitrine Home opens as a preview. When you're happy, make it your home screen:

   ```sh
   scripts/vitrine.sh home 192.168.1.50
   ```

## Make it yours

**Wallpapers**: put up to 10 photos in a folder (landscape, 1920×1080 or larger), then:

```sh
scripts/vitrine.sh wallpapers 192.168.1.50 ~/Pictures/tv
```

Choose between them by holding **OK** on the gear button.

**Weather** works out of the box: Vitrine finds an approximate location from your internet
connection (once a day, via [ipwho.is](https://ipwho.is)). To set it exactly, or to turn the lookup off,
use a config file.

**Config file** (optional): copy `config.example.json` to `config.json`, edit it, and send it:

```sh
scripts/vitrine.sh config 192.168.1.50 config.json
```

| Field | What it does |
|---|---|
| `name` | greeting: "Good evening, Sam" |
| `place`, `latitude`, `longitude` | exact weather location and its label |
| `autoLocation` | `false` = never look up a location (the weather card stays hidden without one) |
| `dock` | first-run dock apps, in order (package names) |
| `appOrder` | order of apps in All apps |
| `hideApps` | apps never shown, e.g. your TV's own launcher |
| `homeApps` / `moreApps` | preinstalled apps that belong on Home, or in the More sheet |
| `liveTvPackage` | your TV's Live TV app, if *Inputs → Live TV* doesn't find it |
| `sleepTimerAction` | an intent action that opens your TV's sleep-timer screen (adds a *Sleep timer* row to Settings) |

To find package names: `adb shell pm list packages`.

**Screensaver**: `scripts/vitrine.sh screensaver 192.168.1.50` (it prints the previous value so you can undo).

**Custom app symbols**: Vitrine Home draws each app's own icon as a white symbol automatically. To use
your own artwork in *All apps*, push a PNG named after the app's package (for example
`com.example.app.png`) to `/sdcard/Android/data/app.vitrine.home/files/icons/`.

## Remote cheat sheet

| On Home | Does |
|---|---|
| Left / Right | move along the dock |
| OK | open the app |
| Hold OK on a dock app | move left/right, remove, dock style |
| Down | All apps |
| Up | Now Playing (when shown), then the top buttons |
| 1–9 | open dock app 1–9 |
| Back | close a panel / back to the dock |
| Hold Back 5 s | open the TV's own home screen |

## Backup and restore

Vitrine Home saves your setup (dock, order, hidden apps, wallpaper choice, theme, input names,
switches) on the TV a few seconds after every change. Copy it to your computer, and put it back after
a reset or reinstall:

```sh
scripts/vitrine.sh backup 192.168.1.50           # -> backups/latest.json (+ wallpapers)
scripts/vitrine.sh restore 192.168.1.50          # uses backups/latest.json
```

## Uninstall

```sh
scripts/vitrine.sh uninstall 192.168.1.50
```

The TV returns to its own home screen.

## Privacy

No accounts, no analytics, no tracking. The only network requests are weather and air quality from
[Open-Meteo](https://open-meteo.com), and, unless you set a location or `"autoLocation": false`, one
approximate-location lookup a day from [ipwho.is](https://ipwho.is). Calendar and media information
is read on the TV and never leaves it. Your config, wallpapers and backups stay on your devices
(`config.json`, `wallpapers/` and `backups/` are git-ignored).

## Build from source

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

Kotlin, no third-party libraries, `minSdk 28`, `targetSdk 35`. Glass is drawn with a pre-blurred copy
of the wallpaper (no per-frame blur), so it stays light on TV processors.

| File | What's in it |
|---|---|
| `MainActivity.kt` | Home, dock, All apps, menus, Settings panel |
| `Glass.kt` | Glass material, dock items, focus line, theme |
| `Widgets.kt`, `Stack.kt` | Weather card and the rotating widget stack |
| `Wheel.kt` | All apps list |
| `SettingsSheet.kt` | Settings rows |
| `HomeExtras.kt` | Now Playing pill, device card, input icons |
| `GlassDream.kt` | Screensaver |
| `Backup.kt`, `Config.kt` | Backup/restore, optional `config.json` |
| `GlassApp.kt` | Crash safety net |

## Contributing

Issues and pull requests are welcome. Please test on a real TV with a remote, since that's where
focus and motion problems show up, and describe the device and Android version in your report.

## License

[MIT](LICENSE)
