# Ndomno — Domino Score

Native Android scorekeeper for regular **2-vs-2 domino games**.

## Features
- Shared player pool; choose any four players for each round.
- Partnerships can change every game.
- Standard round scoring: the winning team receives the combined remaining pips of the opposing team.
- Individual and partnership rankings, plus match history.
- Local persistence between app launches.
- Stable player IDs keep past results linked even after player names change.
- Edit or delete players; previous round history and rankings are preserved when a player is removed.
- Correct or delete saved rounds; individual and partnership rankings recalculate from the saved records.
- Optional shared groups: create a private 10-character invite code and join from another phone.
- Live player and game-history updates through Cloud Firestore.
- Player renames/deletions and round corrections/deletions are synchronized across group members.
- Firestore's Android offline cache queues supported writes while disconnected and syncs when connectivity returns.

## Firebase setup (free Spark plan)

The Android source includes Firebase Authentication (anonymous sign-in), Cloud Firestore, and security rules. No Analytics or paid backend is required.

1. Open the [Firebase Console](https://console.firebase.google.com/) and create a Firebase project.
2. Add an Android app with package name **com.ndomno.app**.
3. Download `google-services.json`. For a local Android Studio build, place it at **app/google-services.json**.
4. For GitHub Actions APK builds, add the entire JSON file as a repository Actions secret:
   - Open GitHub repository **Settings → Secrets and variables → Actions**.
   - Choose **New repository secret**.
   - Set the name to `GOOGLE_SERVICES_JSON`.
   - Paste the complete contents of `google-services.json` into the secret value and save.
   - The workflow writes this secret to `app/google-services.json` only during the build. The JSON file itself is not committed to Git.
5. In Firebase Console → Authentication → Sign-in method (or Sign-in providers), enable **Anonymous**.
6. Create a **Cloud Firestore** database in production mode.
7. Deploy this repository's security rules with Firebase CLI: `firebase deploy --only firestore:rules`. Alternatively, paste `firestore.rules` into the Firestore Rules editor and publish.
8. Build and install the app on both phones. The phone with the existing player list/history should open **Sync → Create group** first. Share the generated code with the other phone; it chooses **Sync → Join** and enters the code.

Both phones must use builds connected to the same Firebase project. Group documents are protected by membership rules; invite codes are the only way to request access. Anonymous device identities are persisted by Firebase on each installation.

### Important notes
- The current app uses local SharedPreferences for its offline local copy and Firestore listeners for shared updates. When a group is joined, the shared group becomes the source of truth for the displayed player list and match history on that phone.
- Create the group from the phone that contains the data you want to keep. Joining another group replaces that phone's displayed local list/history with the group's shared data.
- Anonymous accounts are device-specific. Reinstalling the app can create a new identity; keep the group invite code and do not clear app data.
- Firestore Spark quotas are suitable for a small personal scorekeeping group, but Firebase quotas and terms can change. Monitor usage in the Firebase Console.
- Blocked-round scoring and match-finish rules are not implemented yet; we will match your table rules before adding them.

## Android project and build

Open the repository root in Android Studio. Use JDK 17 and Android SDK Platform 35. GitHub Actions runs unit tests and assembles a debug APK. Without the Firebase Actions secret or local `app/google-services.json`, the app still builds in local-only mode and displays Firebase setup instructions in the Sync dialog.
