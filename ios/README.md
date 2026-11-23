# CuteFamilyPet for iPhone

SwiftUI app (iOS 17+) using SwiftData for on-device storage and the shared Kotlin core
(`../core`, Kotlin Multiplatform) for triage, dose schedules, refill forecasts, vaccine status,
record import and the pharmacy/clinic partner interfaces. Same logic as Android, written once.

## Run it (needs a Mac with Xcode 15+ and JDK 17)

```
brew install xcodegen
cd ios
xcodegen               # creates PetPulse.xcodeproj from project.yml
open PetPulse.xcodeproj
```

Pick an iPhone simulator and press Run. The first build takes a few minutes because Xcode runs
Gradle to compile the Kotlin core into `PetPulseCore.framework`. To run on your own phone, set
your team under Signing & Capabilities.

## Layout

| File | What |
|---|---|
| `App/PetPulseApp.swift` | Entry point, notification permission and Given/Skip actions |
| `Data/Records.swift` | SwiftData models (mirror the Android Room tables and Supabase schema) |
| `Data/Bridge.swift` | Converts records to the shared Kotlin models; date helpers |
| `Data/Reminders.swift` | Dose reminders (next 6 doses per medication), daily vaccine/refill check |
| `Views/` | Pets, pet detail tabs, symptom checker, medications, pharmacy, vet |

## Status

Written without access to a Mac, so it has not been compiled yet. The GitHub Actions workflow
builds it on a macOS runner; expect a round of small fixes on the first run.
