# Thump

OpenSubsonic music player for Android. Works with any OpenSubsonic server. Optional support for Pulse-specific endpoints when connected to a Pulse server.

See `Docs/spec.md` for the full specification.

## Screenshots

| Phone home | Android Auto |
| --- | --- |
| ![Phone home screen](Docs/homeScreen.png) | ![Android Auto browse](Docs/androidAudio.png) |

## Build

Requires JDK 17+ and the Android SDK (compileSdk 34, minSdk 26).

First-time setup — generate the Gradle wrapper:

```
gradle wrapper --gradle-version 8.9
```

Then build:

```
./gradlew assembleDebug
```

## License

MIT — see `LICENSE`.
