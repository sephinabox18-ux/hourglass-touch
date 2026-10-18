# Hourglass Touch

A build of [melonDS Android](https://github.com/rafaelvcaetano/melonDS-android) with phone-native touch controls for *The Legend of Zelda: Phantom Hourglass*. It installs as its own app ("Hourglass Touch") next to any normal melonDS.

No game files are included. You load your own dump of the USA version, patched with the D-pad patch (see below).

## Controls

| Where | What it does |
|---|---|
| Left side | Thumb down anywhere for a joystick. A light push walks slowly. |
| Right side, tap | Sword slash. It waits 0.2 s first, so a double-tap never swings. |
| Right side, swipe | Long slash in that direction |
| Right side, circle | Spin attack |
| Right side, double-tap | Roll |
| A button | Talk, open, read, advance text |
| Game's own buttons at the top and bottom edges | Pass straight through as stylus taps. Afterwards the right side works as a stylus for 3 s, so you can draw item paths or use menus. |
| Pen button | Stylus mode on or off, for sailing, map notes, minigames and bosses |
| Minimap (top left) | Tap to swap the map to the big view and back |
| Mic | Hold, then blow into the phone |
| Lid | Hold to close the DS lid |
| ☰ | melonDS menu: save states, settings, quit |

## Building the APK

GitHub builds the app automatically every time this repository changes. Open the **Actions** tab to watch a build. When it finishes, the APK appears under **Releases** as `HourglassTouch.apk`.

## Preparing the game

1. Get the D-pad patch for the US version (`zelda_phantom_hourglass_dpad_us.xdelta`, by Greiga Master).
2. Patch your `.nds` with [Rom Patcher JS](https://www.marcrobledo.com/RomPatcher.js/). It runs in your phone's browser.
3. In Hourglass Touch, add the folder containing the patched game.

## Notes

- Widescreen is the next step. This version shows the game in 4:3 with the controls and map in the side areas.
- `signing/hourglass.jks` is a throwaway signing key, so updates install over each other. It isn't secret.
- Licensed GPLv3, like melonDS Android.
