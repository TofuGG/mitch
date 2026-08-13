# Mitchy

**Mitchy** is an unofficial port/fork of [Mitch](https://github.com/gardenappl/mitch) by [gardenapple](https://gardenapple.itch.io/mitch), an Android client for [itch.io](https://itch.io), the indie game storefront.

The app allows you to install Android games from the store and keep them updated, acting as an alternative to the Google Play Store for indie game developers and enthusiasts.

  * Download games from itch.io and keep them updated
  * Support for APK downloads as well as HTML5 games
  * itch.io account is not required
  * Blocks trackers and analytics by default
  * Checks for its own updates via GitHub releases
  * Beautiful Material UI adapts to custom color schemes set by game developers
  * Full functionality of itch.io's mobile website

The app is still in development, some features are planned but not yet implemented.

## Installing

Get the latest APK from the [GitHub Releases](https://github.com/TofuGG/mitch/releases) page.

Other ways to get this app:

* Compile from source (see below)

## Compiling from source

This is a standard Android Studio project, which relies on Gradle.

**Unix-y systems:**

```
git clone https://github.com/TofuGG/mitch.git
cd mitch
./gradlew assembleRelease
```

**Windows:**

```
git clone https://github.com/TofuGG/mitch.git
cd mitch
gradlew.bat assembleRelease
```

The release APK will be written to `app/build/outputs/apk/release/`.

## Updating

Mitchy lists itself in your library and checks the project's [GitHub releases](https://github.com/TofuGG/mitch/releases) for updates, so you'll be notified when a new version is available.

## Contributing

Upstream bug reports and translations for the original Mitch project:

* Upstream repository: <https://github.com/gardenappl/mitch>
* Issue tracker: <https://todo.sr.ht/~gardenapple/mitch>
* Translations: <https://hosted.weblate.org/projects/mitch>

For issues with this port, open an issue on <https://github.com/TofuGG/mitch>.
