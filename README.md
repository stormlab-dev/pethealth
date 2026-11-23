# CuteFamilyPet

Pet health tracker: pet profiles, symptom log with triage ("does this need a vet?"), weight,
vaccines, medication reminders and refill alerts, plus pharmacy and vet clinic integrations.

| Folder | What |
|---|---|
| `core/` | Shared Kotlin Multiplatform code (Android + iPhone): models, triage engine, dose/refill/vaccine/weight logic, partner interfaces and mocks. 25 unit tests. |
| `app/` | Android app, Kotlin + Jetpack Compose, Room, WorkManager notifications. |
| `ios/` | iPhone app, SwiftUI + SwiftData, uses `core` as a framework. See `ios/README.md`. |
| `web/` | Web companion, React + TypeScript, Supabase or demo mode. |
| `backend/` | Supabase Postgres schema with row-level security. |
| `docs/` | `INTEGRATIONS.md` (partners), `CLOUD_AND_WEB.md` (databases, sync, web). |

## Android

Open the folder in Android Studio (Ladybug or newer), let Gradle sync, run the `app`
configuration on an emulator or phone (Android 8.0+). Or from a terminal with the Android SDK:

```
./gradlew :core:jvmTest :app:assembleDebug
```

`scripts/test-core.sh` runs the core tests on machines without the Android SDK.

## iPhone

On a Mac: `brew install xcodegen && cd ios && xcodegen && open PetPulse.xcodeproj`, then Run.
Details in `ios/README.md`.

## Try it on your phone or in a browser

- **Android:** every build of `main` publishes a test APK under Releases:
  `https://github.com/stormlab-dev/pethealth/releases/latest/download/cutefamilypet-debug.apk`
- **Web:** published to `https://stormlab-dev.github.io/pethealth/` on every push to `main`
  (one-time: Settings → Pages → Source: GitHub Actions).
- **iPhone:** see `docs/TESTFLIGHT.md`.

## CI

`.github/workflows/build.yml` runs the core tests, builds the Android APK, builds the iPhone
app for the simulator on macOS, and tests and builds the web app.

### Try it

1. Add a pet, then tap **Something's wrong** and pick symptoms. You get an urgency level
   (emergency / today / this week / monitor) with reasons.
2. Add a medication with a time a couple of minutes ahead to see the reminder; tap **Given**
   on the notification.
3. **Meds → Link pharmacy → Connect account → Request refill**. Refresh later to watch the
   demo order move through approval and shipping.
4. **Vet tab → Find and link a clinic → This is my vet** (code `123456`) → **Import records**,
   then **Book online**.

All partners are demo implementations. See `docs/INTEGRATIONS.md` for which real ones could plug in.

## Medical disclaimer

Triage is rule-based general guidance from widely published veterinary red flags, not a
diagnosis. Have a veterinarian review the rules before launch.
