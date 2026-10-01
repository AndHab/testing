# CubeLens

**Snap. Solve. Twist.** An Android app that reads a scrambled Rubik's cube through the camera and
walks you back to solved, one animated turn at a time.

## Features

- **Camera scanning.** Line a face up with the on-screen guide and the app reads all nine stickers
  live. It can capture automatically once the reading is steady. A guided order shows how to hold
  the cube for each of the six faces.
- **Robust color reading.** All 54 stickers are classified together, so each color is used exactly
  nine times. This copes with tricky lighting, including red vs. orange and white vs. yellow under
  warm light. Faces are placed by their center color, and faces scanned at the wrong rotation are
  straightened automatically. Any sticker that is a close call gets flagged for a quick check.
- **Check and edit.** An unfolded cube net plus a 3D preview: tap a sticker to fix it. The checks
  tell you in plain words when something can't be right, e.g. a corner that doesn't exist or two
  pieces that look swapped.
- **Short solutions.** A from-scratch Kotlin implementation of Herbert Kociemba's two-phase
  algorithm. It usually finds a solution of 20 moves or fewer in a fraction of a second.
- **Animated playback.** A glossy, real-time 3D cube turns each layer for you. You can step
  forward and back, play or pause, change the speed, or jump to any move.
- Manual color entry when you don't have the camera handy, and a random-scramble demo.

## Install

Every push builds the app on GitHub Actions (`.github/workflows/android.yml`). Open the latest
run under **Actions → Android build** and download the **CubeLens-apk** artifact. The release APK
is signed with a debug key, so it is sideloadable but not for the Play Store.

## Build locally

Requirements: JDK 17+ and the Android SDK (platform 37).

```sh
./gradlew :core:test                 # cube model, solver, color vision (fast JVM tests)
./gradlew :app:testDebugUnitTest     # app logic + Robolectric/Roborazzi screenshots
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

Screenshot tests write PNGs to `app/build/outputs/roborazzi/`.

## How it works

```
camera frame ─▶ GridSampler ─▶ LiveClassifier (preview)
                    │
          6 scans ──┴─▶ ScanResolver ─▶ balanced color clustering ─▶ OrientationFixer ─▶ CubeValidator
                                                                                              │
                                    Cube3D playback ◀── TwoPhaseSolver ◀── FaceletCube ◀───────┘
```

| Module | What's inside |
|---|---|
| `:core` (pure Kotlin) | `cube/`: facelet geometry, the 18 face turns (derived from 3D rotations, so the solver and the renderer can never disagree), cubie model, validator with per-sticker diagnostics. `solver/`: two-phase solver with cached tables, scrambler. `vision/`: sRGB→CIELAB, CIEDE2000, grid sampling, classification, joint 54-sticker assignment, orientation fixing. |
| `:app` (Compose + CameraX) | `ui/theme` and `ui/components`: the "sunset on ink" design system (see [docs/DESIGN.md](docs/DESIGN.md)). `ui/cube`: Canvas-based 3D cube and net. `ui/scan`, `ui/review`, `ui/solve`, `ui/home`: the screens. |

Facelets follow the standard Kociemba layout (`U R F D L B`, nine stickers each). The app assumes
the standard color scheme: white top, green front, red right.

## Credits

- Two-phase algorithm by Herbert Kociemba. This is an independent implementation.
- Fonts: [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and
  [Outfit](https://github.com/Outfitio/Outfit-Fonts), both under the SIL Open Font License (see
  `licenses/`).
