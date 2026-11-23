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
