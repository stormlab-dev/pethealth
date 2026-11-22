# Databases, cloud sync and the web app

## Where data lives today

| Layer | Tech | Status |
|---|---|---|
| Android on-device | Room (SQLite), `app/.../data` | Built. The app works fully offline. |
| iPhone on-device | SwiftData, `ios/PetPulse/Data` | Built. Same table layout as Room. |
| Cloud | Supabase (Postgres + Auth + Row Level Security) | Schema ready: `backend/supabase/migrations/0001_init.sql` |
| Web | React + Vite + supabase-js, `web/` | Built. Runs in demo mode (browser storage) until Supabase keys are set. |

```
 Android app ── Room (offline) ──sync──┐
                                       ├──▶ Supabase Postgres  ◀── web app (direct, via RLS)
 iPhone app ─── local DB (offline) ─sync┘        │
                                         Edge Functions: partner APIs, AI triage, push
```

## Why Supabase

- Postgres with row-level security, so phones and the browser can talk to it directly and
  each user only ever sees their own pets (or pets shared with them).
- Auth (email magic link, Google, Apple) built in, which you need for the "same account on
  phone and web" experience.
- Edge Functions for the parts that must stay server-side: partner pharmacy/clinic API keys,
  calls to an LLM for AI triage, and push notifications.
- Cost fits a $2–5/month app: free tier for development, then about $25/month for the Pro
  plan until you have real scale.

Firebase (Firestore) works too and has stronger offline sync out of the box, but the data here
is relational (pets → meds → dose logs, sharing between family members), which suits Postgres.
A custom REST API (e.g. Ktor or Node + Postgres on Render/Fly) is the most flexible option and
the most work; only worth it once you outgrow Supabase.

## Connect the database

1. Create a project at supabase.com. Copy the Project URL and the anon public key
   (Project Settings → API).
2. Apply the schema: `supabase db push` with the Supabase CLI from `backend/`, or paste
   `0001_init.sql` into the SQL editor.
3. Turn on the sign-in methods you want (Authentication → Providers). Add your web URL and
   `petpulse://auth` (Android deep link) to the redirect allow-list.
4. Web: copy `web/.env.example` to `web/.env` and fill in the two values. `npm run dev`.
5. Android: add the same two values to `local.properties`, expose them through `BuildConfig`,
   and add the sync described below.

The anon key is safe to ship in apps because row-level security enforces access. Never ship
the service-role key; it belongs only in Edge Functions.

The schema was checked on Postgres 16 with a stand-in for Supabase's auth schema: an owner sees
their pet's data, another user sees nothing and cannot write, and a user added as a "viewer"
can read but not edit.

## Android sync (next step)

Room stays the source of truth on the phone, so the app keeps working offline. Sync runs in a
WorkManager job (on app start, after edits, and periodically):

1. Add `remoteId` (UUID), `updatedAt` and `deletedAt` columns to the Room entities (Room
   migration v1 → v2). Deletes become soft deletes.
2. **Push**: send rows where `updatedAt > lastPushedAt` with an upsert keyed on `remoteId`.
3. **Pull**: fetch rows where `updated_at > lastPulledAt` for each table; upsert locally.
4. Conflicts: last write wins on `updated_at`. That is fine for a single owner; for shared
   pets the dose log is append-only, which avoids most real conflicts.
5. Client library: `io.github.jan-tennert.supabase` (supabase-kt) for auth and Postgrest.

## The web app

`web/` is a companion for the things people prefer to do on a big screen: reviewing history,
entering vaccine records, sharing with a vet or a pet sitter. It includes pets, the symptom
checker (same rules as Android, ported to TypeScript and tested against the same cases),
weight chart, vaccines and medications. Medication reminders stay on the phone.

```
cd web
npm install
npm run dev     # http://localhost:5173, demo mode
npm test        # triage + health rule tests
npm run build   # static site in dist/, deploy to Vercel, Netlify or Cloudflare Pages
```

Keeping triage rules in sync: today they exist in Kotlin (`core`) and TypeScript (`web`), each
with the same test cases. Once there is a backend, move triage into one Supabase Edge Function
that every app calls, with the on-device rules kept only as an offline fallback.
