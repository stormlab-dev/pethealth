# iPhone testing with TestFlight

TestFlight is Apple's way to put a pre-release app on testers' iPhones. Testers install the
free TestFlight app and tap an invite link. Builds expire after 90 days.

## What you need

| Item | Cost | Notes |
|---|---|---|
| Apple Developer Program membership | $99/year | Enroll as an individual (about a day) or as a company (needs a D-U-N-S number, 1–2 weeks). Enrolling as the company puts its name on the App Store listing. |
| A Mac with Xcode 15 or newer | | Only for the first setup; later uploads can run from GitHub Actions on Apple's macOS runners. |
| An app icon (1024×1024 PNG) | | Required before upload. |

## Steps

1. **Enroll** at developer.apple.com/programs.
2. **Create the app record** in App Store Connect → Apps → "+" → New App. Bundle ID
   `com.cutefamilypet.app` (or your own, then update `ios/project.yml`). Pick the final name early;
   names are unique across the App Store.
3. **Build and upload from the Mac**:
   ```
   brew install xcodegen
   cd ios && xcodegen && open PetPulse.xcodeproj
   ```
   In Xcode: select the PetPulse target → Signing & Capabilities → choose your Team (automatic
   signing). Then Product → Archive → Distribute App → App Store Connect → Upload.
4. **Internal testing** (no review): App Store Connect → TestFlight → add up to 100 people from
   your App Store Connect team. They get the build within minutes of processing.
5. **External testing** (up to 10,000 testers, public link): create a group, add the build,
   fill in "What to Test" and a contact email. The first build goes through a short beta review
   (usually under a day). Then share the public link.

## Before inviting outside testers

- **Privacy:** App Store Connect asks what data you collect. Today everything stays on the
  phone; once cloud sync is on, declare health and contact data. You need a privacy policy URL.
- **Medical wording:** Apple reviews health apps carefully. Keep the "general guidance, not a
  diagnosis" disclaimer visible in the symptom checker and avoid "diagnose" in the description.
- **Demo partners:** the pharmacy and clinic flows are labeled "Demo". Keep the labels, or hide
  those screens for outside testers so nobody thinks a refill was really ordered.
- **Notification permission:** the reminder prompt appears on first launch. That's fine for
  testing; for launch, ask when someone adds their first medication.

## Automating uploads later (optional)

Once the app exists in App Store Connect, the macOS job in `.github/workflows/build.yml` can
also archive and upload on every push to `main`. It needs three repository secrets: an App Store
Connect API key (key id, issuer id, .p8 contents). Then `xcodebuild -allowProvisioningUpdates`
with that key handles signing. Ask and I'll add this step.
