# AI symptom checker with photo analysis

The symptom checker runs in two layers:

1. **Safety rules** (`core/.../RuleBasedTriageEngine.kt`, mirrored in `web/src/lib/triage.ts`)
   run on the device, instantly and offline. They catch emergencies like breathing trouble,
   bloat or a blocked male cat.
2. **AI review** (Premium and Family) sends the symptoms, pet details, notes, an optional photo
   and the rules' result to the `ai-triage` server function, which asks Claude (model
   `claude-opus-5-5`) for an urgency level, what it sees in the photo, reasons and warning signs.

The final urgency is the **more urgent** of the two. The AI can raise it (for example a photo
of a deep wound), never lower it. The server and each app both apply this rule. The result
always carries the "not a diagnosis" disclaimer.

## Where things live

- `backend/supabase/functions/ai-triage/index.ts` calls Claude, checks the user's plan and
  limits, and logs completed checks to `ai_checks`.
- `backend/supabase/functions/ai-triage/logic.ts` holds the prompt, the output schema and the
  merge rule (tests: `web/src/lib/ai-triage.test.ts`).
- The apps shrink photos to at most 1568 px JPEG before upload.

The Anthropic API key is stored only as a Supabase function secret and never ships in the apps.

## Setup

```bash
supabase functions deploy ai-triage
supabase secrets set ANTHROPIC_API_KEY=sk-ant-...           # console.anthropic.com → API keys
# optional
supabase secrets set PAID_AI_CHECKS_PER_DAY=30 FREE_AI_CHECKS_PER_MONTH=0
```

Also enable **Anonymous sign-ins** (Supabase → Authentication → Sign In / Providers). The phone
apps use an anonymous account per device, which needs no email. Then build the apps with
`CFP_SUPABASE_URL` and `CFP_SUPABASE_ANON_KEY` (see docs/MONETIZATION.md).

**Fallbacks are turned on.** If Claude declines a request, Anthropic re-runs it on its
recommended fallback model (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`). If
that also declines, the app shows the rule-based result.

## Cost

At $4 per million input tokens and $20 per million output tokens, one check with a photo uses
about 3,000 to 4,000 input tokens and 1,000 to 2,000 output tokens (including the model's
reasoning). That comes to roughly **3 to 6 cents per check**. A Premium subscriber running 10
checks a month costs about $0.50, against $3.99 revenue before store fees. The daily cap
(default 30) limits abuse.
