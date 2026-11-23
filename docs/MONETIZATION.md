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

## Not built yet

- Ads for free users (the plan flag `ads` exists; no ad SDK is added).
- Contest voting screens in the apps (database tables and policies are in place).
- AI highlight reels, portraits, memorial albums and prints are sold but not yet produced; they
  need the partner services above.
