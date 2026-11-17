# HomeStream Android & Android TV

Native Kotlin + Jetpack Compose + Media3 client for Android phones, tablets, and Android TV.

Part of the [HomeStream](https://github.com/Eckobrew/homestream) platform.

## Features

- **Media3 Playback**: Hardware-accelerated HLS/direct playback with subtitle, audio track, and media version switching, plus Skip Intro/Credits.
- **Dual UI**: Touch-first phone/tablet navigation and D-pad TV layout.
- **Profiles & Parental Controls**: Local profile switcher, Kids mode enforcement, and PIN-gated admin controls.
- **Music, Books, YouTube & Requests**: Full support for library browsing, music playback, and media requests.

## Building a Release APK

Only release APKs (`assembleRelease`) should be distributed on GitHub Releases:

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`.

## Verification

```bash
./gradlew compileDebugKotlin testDebugUnitTest
```
