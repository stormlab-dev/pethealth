# Plans, in-app purchases and extras

| Plan    | Price                              | Unlocks |
|---------|------------------------------------|---------|
| Free    | $0                                 | Unlimited pets and logs, reminders, rule-based symptom checker, 200 photos/videos, 15 s clips, share with 1 person |
| Premium | $3.99/month or $29.99/year (7-day trial on yearly) | Unlimited media, 3-minute clips, AI photo symptom checks, vet-ready reports, no ads |
| Family  | $5.99/month or $44.99/year (7-day trial on yearly) | Premium for up to 6 people, unlimited pets |

One-time extras: sticker/frame packs ($0.99, $1.99), AI highlight reels ($2.99 monthly, $4.99
yearly), AI portrait ($2.99), memorial album ($4.99), contest boost ($0.99). Prints (photo book
$29.99, calendar $19.99, mug $14.99, ID tag $9.99) are physical goods, which the app stores do
not allow to be sold with in-app billing, so they go through a web checkout.

The single source of truth for product ids and limits is `core/.../billing/Plans.kt`; the web
(`web/src/lib/plans.ts`), the webhook (`backend/.../billing-webhook/plans.ts`) and the database
quota triggers (`0002_media_plans_ai.sql`) mirror it.

## How it works

- **Apps** sell through Google Play Billing (via RevenueCat on Android) and StoreKit 2 on
  iPhone. With no store keys built in, they use a **demo store** that charges nothing, so every
  feature can be tried today.
- **Server**: RevenueCat calls `billing-webhook`, which writes the `entitlements` and
  `purchases` tables. The database enforces the free-plan limits (photo count, clip length,
  sharing seats) and `ai-triage` checks the plan, so a modified app can't unlock Premium.
- **Web app**: shows plans and lets people try them in demo mode; real purchases happen in the
  phone apps and unlock the web automatically through the same account.

## Accounts Jeffrey needs

1. **Google Play Console** ($25 once): create the app `com.cutefamilypet.app`, a payments
   profile, and the products below.
2. **Apple Developer Program** ($99/year) and **App Store Connect**: Paid Apps agreement,
   banking and tax, then the same products (subscriptions in one group "CuteFamilyPet").
3. **RevenueCat** (free up to $2.5k monthly revenue): connect both stores, create entitlements
   `premium` and `family`, attach the products, and add the webhook from
   `billing-webhook/index.ts`. Copy the Android public key into `CFP_REVENUECAT_ANDROID_KEY`.
4. **Supabase** project (free tier is fine to start) and an **Anthropic API** key for AI checks.
5. Later: a **print partner** (Prodigi, Gelato or Printful) plus **Stripe** for print checkout,
   and a video/image AI service for highlight reels and portraits. Until then those items are
   listed as "coming soon" or fulfilled in demo mode only.

## Product ids (create these exactly)

| Id | Type | Price |
|----|------|-------|
| cfp_premium_monthly | auto-renewing subscription | $3.99 / month |
| cfp_premium_yearly | auto-renewing subscription, 7-day free trial | $29.99 / year |
| cfp_family_monthly | auto-renewing subscription | $5.99 / month |
| cfp_family_yearly | auto-renewing subscription, 7-day free trial | $44.99 / year |
| cfp_stickers_seasonal | non-consumable | $0.99 |
| cfp_stickers_birthday | non-consumable | $1.99 |
| cfp_portrait | consumable | $2.99 |
| cfp_reel_monthly | consumable | $2.99 |
| cfp_reel_yearly | consumable | $4.99 |
| cfp_memorial_album | non-consumable | $4.99 |
| cfp_contest_boost | consumable | $0.99 |

## Build keys

Android reads these from `~/.gradle/gradle.properties` or environment variables at build time:
`CFP_SUPABASE_URL`, `CFP_SUPABASE_ANON_KEY`, `CFP_REVENUECAT_ANDROID_KEY`. iPhone reads
`CFP_SUPABASE_URL` and `CFP_SUPABASE_ANON_KEY` from build settings (in an `.xcconfig`, write the
URL as `https:/$()/xyz.supabase.co` because `//` starts a comment there). StoreKit needs no key.

To get iPhone purchases onto the server as well, either add the RevenueCat iOS SDK (swap the
StoreKit calls in `ios/PetPulse/Services/PlanStore.swift`) or point App Store Server
Notifications at RevenueCat. Purchases already carry the user's cloud id as `appAccountToken`.

## Ads on the free plan

Free users see one small banner ad. Premium and Family remove ads entirely.

| Where | What | Where it shows |
|---|---|---|
| Android | Google AdMob adaptive banner | Bottom of the pet list and the Photos and videos tab |
| iPhone | Google AdMob adaptive banner | Bottom of the pet list |
| Web app | Google AdSense (off until configured) | Bottom of the pet list |

Ads never appear on the symptom checker, AI results, medications, vaccines, vet reports, the
Plans screen or the marketing website. The banner disappears the moment a purchase upgrades the
plan. Consent is collected with Google's User Messaging Platform before any ad loads, and the
Plans screen has an "Ad privacy choices" button where the law requires one.

**Out of the box the apps use Google's test ad IDs**, so you can build and try them safely.
Test ads earn nothing. Never tap your own live ads; AdMob bans accounts for it.

### Accounts and steps

1. **AdMob** (admob.google.com): create the account, add two apps (Android
   `com.petpulse.app`, iOS bundle id), and create one *Banner* ad unit in each. Add payment
   and tax info.
2. Put the real IDs in the builds:
   - Android: `CFP_ADMOB_APP_ID` and `CFP_ADMOB_BANNER_ID` in `~/.gradle/gradle.properties` or as
     environment variables (CI secrets).
   - iPhone: `CFP_ADMOB_APP_ID` and `CFP_ADMOB_BANNER_ID` build settings in `ios/project.yml` or
     an `.xcconfig`.
3. **AdMob → Privacy & messaging**: create a GDPR consent message and a US state regulations
   message, and publish them. The apps show them automatically.
4. **app-ads.txt**: AdMob gives you a line like
   `google.com, pub-XXXXXXXXXXXXXXXX, DIRECT, f08c47fec0942fa0`. Put it in
   `web/public/app-ads.txt` (served at `www.cutefamilypet.com/app-ads.txt`) and list the website
   as the developer site in Play Console and App Store Connect.
5. **Google Play Console**: set "Contains ads" to Yes, and in Data safety declare device or
   other IDs, approximate location and app interactions collected for advertising, shared with
   Google.
6. **App Store Connect**: in App Privacy declare Device ID, Coarse Location and Product
   Interaction used for third-party advertising. Add an App Tracking Transparency prompt only if
   you later want personalized ads on iOS; without it iOS ads are non-personalized and earn less.
7. **AdSense** (optional, web app): apply with www.cutefamilypet.com, then set
   `VITE_ADSENSE_CLIENT` (`ca-pub-...`) and `VITE_ADSENSE_SLOT` for the web build, and add the
   AdSense line to `web/public/ads.txt`. Approval can take a few weeks.

### What to expect

Banner ads in the US typically pay roughly $0.50 to $2 per 1,000 views, less elsewhere. As a
rough guide, 1,000 daily free users opening the app twice a day is about $30 to $120 a month.
Ads mainly work as a nudge toward Premium; rewarded ads (for example, watch a video to unlock one
AI check) can be added later.

## Not built yet

- Rewarded ads (for example, one free AI check per video watched).
- Contest voting screens in the apps (database tables and policies are in place).
- AI highlight reels, portraits, memorial albums and prints are sold but not yet produced; they
  need the partner services above.
