# Thump

OpenSubsonic music player for Android. Works with any OpenSubsonic server. Optional support for Pulse-specific endpoints when connected to a Pulse server.

Pulse server available here
https://github.com/therobm/Pulse

See `Docs/spec.md` for the full specification.

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
