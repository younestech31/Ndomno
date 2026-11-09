# Ndomno — Domino Score

Native Android scorekeeper for your regular **2-vs-2 domino games**.

## Features in this first build
- Add a shared pool of players.
- Select any four players for each match; partners can change every game.
- Enter remaining pips for all four players and select the winning team.
- Standard round scoring: the winning team receives the combined remaining pips of the opposing team.
- View individual points ranking, partnership ranking and match history.
- Keep player and round data saved locally on the device between app launches.

## Android project
This repository contains the Android Studio project as ordinary source files. There is no Base64 or compressed source archive to reconstruct.

Open the repository root in Android Studio. Use JDK 17 and Android SDK Platform 35.

## Build
The GitHub Actions workflow installs Gradle and Android SDK, runs unit tests and assembles a debug APK.

## Important first-version limitations
- Data is stored locally using Android SharedPreferences. It stays on this device and is not synchronized to another phone.
- This is a single-device prototype. Phone-to-phone synchronization is not included yet.
- Blocked-round scoring and match-finish rules are not implemented yet; we will match your local table rules before adding them.
- Blocked-round scoring and match-finish rules are not implemented yet; we will match your local table rules before adding them.

No ads, analytics or tracking are included.
