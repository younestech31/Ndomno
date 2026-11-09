# Ndomno — Domino Score

Native Android scorekeeper for your regular **2-vs-2 domino games**.

## App rules and product decisions

- Team matches only: exactly four players, two players on each team.
- Players come from one shared pool; teammates can change for every match.
- Players can join as many matches as they like.
- Each match stores its own two pairings, rounds and final score.
- Individual and partnership rankings are calculated from saved match history.
- Local-first: no account, server, subscription or internet connection is required while playing.

## Android source

The initial Android Studio project is stored in `source-parts/` as a compressed source archive to keep this first source drop compact.

Extract it from a terminal after cloning:

```bash
cat source-parts/*.b64 | base64 --decode > DominoScore-Android-Source.tar.xz
tar -xJf DominoScore-Android-Source.tar.xz
cd domino-score-android
```

Then open the `domino-score-android` folder in Android Studio. The project uses Kotlin, Jetpack Compose, Room and Gradle.

## Build and tests

On a machine with JDK 17 and Android SDK Platform 37 installed:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The GitHub Actions workflow performs the same extraction, unit tests and debug APK build. A successful run publishes the APK as a downloadable workflow artifact.

## Scoring behavior in this first iteration

For a normal round, choose the winning team and enter the remaining pips for all four players. The winning team receives the combined remaining pips held by the opposing team.

For a blocked round, the initial implementation treats the team with fewer remaining pips as the winner and awards the difference. A tie awards zero points. This blocked-round rule is provisional and should be confirmed against your usual table rule before relying on it for rankings.

## Current scope and known limitation

This first version stores all players and match results locally on one phone. It does **not** yet synchronize the game history between your phone and your friend's phone. Phone-to-phone sync, export/backup and advanced statistics can be added after the scorekeeping flow has been tested in real games.

No ads, analytics or tracking are included.
