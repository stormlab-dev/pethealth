# PetPulse

Pet health tracker: pet profiles, symptom log with triage ("does this need a vet?"), weight,
vaccines, medication reminders and refill alerts, plus pharmacy and vet clinic integrations.

| Folder | What |
|---|---|
| `core/` | Pure Kotlin: models, triage engine, dose/refill/vaccine/weight logic, partner interfaces and mocks. 24 unit tests. |
| `app/` | Android app, Kotlin + Jetpack Compose, Room, WorkManager notifications. |
| `web/` | Web companion, React + TypeScript, Supabase or demo mode. |
| `backend/` | Supabase Postgres schema with row-level security. |
| `docs/` | `INTEGRATIONS.md` (partners), `CLOUD_AND_WEB.md` (databases, sync, web). |

## Android

Open the folder in Android Studio (Ladybug or newer), let Gradle sync, run the `app`
configuration on an emulator or phone (Android 8.0+). Or from a terminal with the Android SDK:

```
./gradlew :core:test :app:assembleDebug
```

`scripts/test-core.sh` runs the core tests on machines without the Android SDK.

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
