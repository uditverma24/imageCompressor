# Image Compressor (Android)

Offline Android app that compresses photos to a target file size (20 KB to 2 MB or a custom size),
for one image or a whole batch (the target applies to **each** image individually).

Kotlin · Jetpack Compose · Material 3 · minSdk 26 (Android 8.0) · no internet permission, no backend.

> **Build status:** the source was written in an environment without the Android SDK, so it has
> **not been compiled yet** and no APK is included. Follow "Build the APK" below. If Gradle reports
> any compile error, send it to me and I will fix it.

## Build the APK (Android Studio, easiest)

1. Install **Android Studio** (Ladybug 2024.2 or newer). It brings its own JDK 17 and the Android SDK.
2. `File > Open` and select this `ImageCompressor` folder. Let Gradle sync finish (first sync downloads
   dependencies, so be online).
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
4. Click **locate** in the notification, or open
   `app/build/outputs/apk/debug/app-debug.apk`.

### Or from the command line

```bash
# needs JDK 17 and the Android SDK (ANDROID_HOME set, or sdk.dir in local.properties)
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Install on your phone

1. Copy `app-debug.apk` to the phone (USB, Drive, email, WhatsApp to yourself...).
2. Open it from the Files app. When prompted, allow **Install unknown apps** for that app.
3. Tap **Install**, then **Open**.

Or with USB debugging enabled: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

The debug APK is signed with Android's debug key, so it installs directly. It is fine for personal
use but cannot be uploaded to Google Play.

## Release APK and Google Play AAB

1. Create a keystore once (keep it and its passwords safe, you need the same one for every update):

   ```bash
   keytool -genkeypair -v -keystore imagecompressor.jks -alias release \
           -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Create `keystore.properties` in the project root (next to `settings.gradle.kts`):

   ```properties
   storeFile=imagecompressor.jks
   storePassword=YOUR_STORE_PASSWORD
   keyAlias=release
   keyPassword=YOUR_KEY_PASSWORD
   ```

   Both files are already listed in `.gitignore`. Never commit them.

3. Build:

   ```bash
   ./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk (signed, installable)
   ./gradlew bundleRelease      # -> app/build/outputs/bundle/release/app-release.aab (upload to Play Console)
   ```

   Or in Android Studio: `Build > Generate Signed Bundle / APK`.

Before publishing, raise `versionCode` / `versionName` in `app/build.gradle.kts` for every update and
change `applicationId` if you want your own package name.

## How the compression works

* Every size is **measured** by really encoding the JPEG; nothing is estimated.
* Resolution is reduced only when needed (aspect ratio kept), then a binary search finds the highest
  JPEG quality (40 to 95) that fits under the target. If that quality would be low, it trades a bit of
  resolution for better quality.
* Results land at roughly 90-100% of the target. If the target is unreachable above a minimum
  resolution (about 320 px) the closest result is shown with a warning, never claimed as exact.
* A JPEG that is already smaller than the target is kept unchanged.
* EXIF orientation is applied, transparency is flattened to white, originals are never modified.
* Batches run strictly one image at a time; a failed image is reported and the rest continue.

## Saving and permissions

* Saved to `Pictures/ImageCompressor` through MediaStore.
* Android 10+: no permission at all. Android 8-9: asks for storage write permission only when you tap Save.
* Images are picked with the system photo picker (no read permission needed).

## Project layout

```
app/src/main/java/com/imagecompressor/app/
  MainActivity.kt          navigation, save-permission handling
  engine/ImageCompressor.kt target-size compression engine
  engine/Storage.kt        MediaStore saving + share sheet
  vm/MainViewModel.kt      state, sequential batch processing
  ui/                      Theme, HomeScreen, ResultsScreen, shared components
```
