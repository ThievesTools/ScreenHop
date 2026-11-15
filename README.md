# ScreenHop

A small app for the AYN Thor that moves apps and the controller between the two screens.
It is only tested on the Thor. It uses standard Android APIs, so it may work on other
dual-screen Android 11+ devices, but that is untested.

**Select is the shift key by default**: hold Select
and press a button. (Back can be chosen as shift key instead.)

| Hold Select + | Action (default button, changeable) |
|---|---|
| Y | Controller to the top screen |
| A | Controller to the bottom screen |
| L1 | Move the app on the screen you're using to the other screen |
| R1 | Swap the apps on both screens |

- The shortcut button is kept from the app or game; Select itself passes through as usual.
  With Back as shift key, Back on its own still works as Back, sent when you let go.
- Every other press goes straight through, so games keep all their controls.
- Confirmations are drawn as a small overlay, so they show even when ScreenHop is hidden.
- No dependencies, no internet permission, no root.

## Getting the APK

**Option A – GitHub (no software to install)**
1. Fork or clone this repository on GitHub.
2. Every push builds the app and publishes it as a release named after its version,
   for example **v1.4.0**. On the Thor, open the repository's **Releases** page and download
   **ScreenHop.apk**, or let [Obtainium](https://github.com/ImranR98/Obtainium) track the
   repository URL for automatic updates.

Every build is signed with the same key (`app/screenhop.keystore`), so a new build installs
over the old one and keeps your settings and the accessibility permission.

Versions: `versionName` and `versionCode` in `app/build.gradle.kts` are bumped for every
release, and `RELEASE_NOTES.md` becomes the release's description.

**Option B – Android Studio**
Open this folder in Android Studio, let it sync, then *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
Or connect the Thor with USB debugging on and press Run.

## Setup on the Thor

1. Install the APK and open ScreenHop.
2. Tap **Turn on ScreenHop** and enable it in Accessibility settings.
   - If Android says **"Restricted setting"**: go to *Settings → Apps → ScreenHop*,
     tap the ⋮ menu (top right) → **Allow restricted settings**, then try again.
     Android 13 does this for every sideloaded app that asks for accessibility access.
3. Back in ScreenHop, press a few buttons (Select, Y, A, L1, R1). The "Last button seen" line
   tells you which buttons ScreenHop can actually see.
4. To change a shortcut, tap **Change** and press the new button on its own, without Back.
   A button can only belong to one shortcut; assigning it elsewhere turns the old one off.

## How it works

- `HopService.kt` – an accessibility service that watches button presses and handles the shift key.
- `Banner.kt` – the short confirmation/error message shown on a screen.
- `FocusSwitcher.kt` – moves the controller to a screen: it briefly shows a tiny invisible
  window there and taps it with a simulated touch (Android moves focus to the screen you
  tap). No app is touched.
- `ScreenMover.kt` – finds the focused screen and the top app on each screen
  (launchers and System UI are skipped), then re-launches that app with
  `ActivityOptions.setLaunchDisplayId()`. Android moves the existing app to the
  other screen instead of opening a second copy.
- `MainActivity.kt` – the settings screen.

Details that matter on the Thor:
- Every screen reports a "focused" window, so ScreenHop finds the screen you're using
  from Android's single *active* window, falling back to the screen you last interacted with.
- The AYN button sends the same key code as Home. ScreenHop remembers the hardware
  scan code of the button you record, so the two are never confused.
- ScreenHop deliberately uses no root, Shizuku or hidden system APIs.

## Known limits

- When an app moves, the screen size changes. Most apps handle this smoothly;
  some games restart or reload. Firefox-based browsers are known to crash later
  (on selecting text or opening a menu) after being moved; reopen them if that happens.
- Some apps refuse to change screens. ScreenHop checks every move and tells you
  when an app didn't move, instead of claiming success.
- Apps without a launcher icon can't be moved.
- Only the app that's visible on each screen moves. Apps waiting in the background
  (in Recents) stay where they are: Android doesn't let regular apps move those.
- Back is sent when you let go, not when you press it, so holding Back inside an app
  or game does nothing special. Games that need Back held should use another button for that.
- The D-pad and analog sticks can't be used in shortcuts: the Thor reports them as
  joystick movement, which Android doesn't show to accessibility services.
- AYN's virtual mouse appears to work on the top screen only.

## License

[MIT](LICENSE)
