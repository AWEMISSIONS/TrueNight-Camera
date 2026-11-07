# TrueNight Camera
Native Android Camera2 prototype with Natural-to-Eye and Enhanced Night modes.

Natural mode uses approximately -0.7 EV exposure compensation. Enhanced mode uses approximately +1 EV and camera-supported noise reduction. Saves JPEG images to Pictures/TrueNight.

## Build
GitHub Actions > Build Android APK > Run workflow, then download the APK artifact. Or open in Android Studio with JDK 17 and Android SDK 35.

## Limitations
This is an untested prototype, not a computational night photography competitor. Enhanced mode is single-exposure, not multi-frame stacking. No RAW capture, calibrated perceptual matching, manual ISO/shutter, or astrophotography yet. JPEG orientation currently assumes portrait. Camera2 controls depend on device support.
