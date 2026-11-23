# Website and www.cutefamilypet.com

`web/` builds one site: the marketing pages (`index`, `pricing`, `about`, `contact`, `terms`,
`privacy`) and the web app at `/app/`. GitHub Actions publishes it to GitHub Pages on every push
to `main` (`.github/workflows/web-pages.yml`).

## Connect the domain (one time)

1. **DNS** at the registrar where cutefamilypet.com is registered:
   | Type  | Name | Value |
   |-------|------|-------|
   | CNAME | www  | `stormlab-dev.github.io` |
   | A     | @    | `185.199.108.153` |
   | A     | @    | `185.199.109.153` |
   | A     | @    | `185.199.110.153` |
   | A     | @    | `185.199.111.153` |
   The apex records make `cutefamilypet.com` redirect to `www`.
2. **GitHub** → repo Settings → Pages → Custom domain: `www.cutefamilypet.com` → Save. When the
   DNS check passes, tick **Enforce HTTPS** (the certificate can take up to an hour).
3. **GitHub** → Actions → "Publish web app" → Run workflow. The build reads the custom domain
   from Pages and builds the site for the domain root instead of `/pethealth/`.
4. Optional but recommended: Settings → Pages → verify the domain for your account/org, which
   stops anyone else claiming it on GitHub Pages.

## Contact form

With `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` set as repository variables, messages
are saved to the `contact_messages` table (read them in Supabase → Table editor). Without them
the form opens the visitor's email app addressed to connect@stormlabmarketing.com, so set up that
mailbox (or change `CONTACT_EMAIL` in `web/src/site/site.ts`).

## Before public launch

- Have a lawyer review `terms.html` and `privacy.html` (both are marked as drafts).
- Replace the example result and phone mockup with real screenshots when you have them.
- Point the iPhone button at the TestFlight public link once it exists.

## Hosting on AWS Amplify instead

`amplify.yml` at the repo root holds the build settings, so Amplify detects them.

1. AWS console → **Amplify** → Create new app → **GitHub** → authorize, then pick
   `stormlab-dev/pethealth` and branch `main`.
2. Tick **My app is a monorepo** and enter `web` as the root directory. Amplify then shows the
   build settings from `amplify.yml` (build `npm run build`, output `dist`). Keep them as they are.
3. Optional: under Advanced settings → Environment variables, add `VITE_SUPABASE_URL` and
   `VITE_SUPABASE_ANON_KEY` once Supabase exists.
4. **Save and deploy.** Open the `https://main.<id>.amplifyapp.com` link it gives you and check
   the site and `/app/`.
5. **Custom domain:** Amplify → Hosting → Custom domains → Add domain → `cutefamilypet.com`.
   Pick "Manual configuration" since the domain is at GoDaddy. Amplify lists DNS records to add at
   GoDaddy:
   - a CNAME used to verify the SSL certificate (name like `_abc123.cutefamilypet.com`)
   - CNAME `www` → the `xxxx.cloudfront.net` value Amplify shows. This **replaces** the
     `www → stormlab-dev.github.io` record.
   - For the bare domain, GoDaddy can't point `@` at CloudFront, so in GoDaddy use **Forwarding** →
     forward `cutefamilypet.com` to `https://www.cutefamilypet.com` (permanent, 301), and remove
     the four `185.199.x.153` A records. Or move the domain's DNS to Route 53, which supports the
     bare domain directly.
6. When `https://www.cutefamilypet.com` loads from Amplify, remove the custom domain in GitHub →
   Settings → Pages. The GitHub Pages copy keeps building until then and is harmless afterwards.

Cost: Amplify charges about $0.01 per build minute and $0.15 per GB served, with a free tier for
the first 12 months. A small site like this typically costs well under $5 a month.
