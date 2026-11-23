// AI symptom checker with photo analysis (Supabase Edge Function, Deno).
//
// The apps run the rule-based checker first and send its result here together with the symptoms
// and an optional photo. Claude reviews everything and may RAISE the urgency, never lower it, so
// the safety rules always win. The Anthropic key lives only in this function's secrets.
//
// Deploy:  supabase functions deploy ai-triage
// Secrets: supabase secrets set ANTHROPIC_API_KEY=sk-ant-...
// Optional: FREE_AI_CHECKS_PER_MONTH (default 0), PAID_AI_CHECKS_PER_DAY (default 30)
import Anthropic from "npm:@anthropic-ai/sdk@1.6.0";
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import { buildPrompt, mergeResults, OUTPUT_SCHEMA, type AiOutput, type TriageRequest, validateRequest } from "./logic.ts";

const MODEL = "claude-opus-5-5";
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

const anthropic = new Anthropic({ apiKey: Deno.env.get("ANTHROPIC_API_KEY") });
const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  // Who is asking, and what plan are they on?
  const token = req.headers.get("Authorization")?.replace(/^Bearer\s+/i, "");
  const { data: auth } = token ? await admin.auth.getUser(token) : { data: { user: null } };
  const user = auth?.user;
  if (!user) return json({ error: "Sign in to use the AI symptom checker." }, 401);

  const { data: plan } = await admin.rpc("current_plan", { u: user.id });
  const paid = plan === "PREMIUM" || plan === "FAMILY";
  const since = new Date(Date.now() - (paid ? 1 : 30) * 86_400_000).toISOString();
  const { count } = await admin.from("ai_checks").select("id", { count: "exact", head: true })
    .eq("user_id", user.id).gte("created_at", since);
  const limit = paid ? Number(Deno.env.get("PAID_AI_CHECKS_PER_DAY") ?? 30) : Number(Deno.env.get("FREE_AI_CHECKS_PER_MONTH") ?? 0);
  if ((count ?? 0) >= limit) {
    return json(paid
      ? { error: "You've reached today's AI check limit. The rule-based result above still applies.", code: "RATE_LIMIT" }
      : { error: "AI photo checks are part of Premium.", code: "UPGRADE_REQUIRED" }, 402);
  }

  let input: TriageRequest;
  try {
    input = validateRequest(await req.json());
  } catch (e) {
    return json({ error: String((e as Error).message) }, 400);
  }

  const content: Anthropic.Beta.BetaContentBlockParam[] = [];
  if (input.photo) {
    content.push({ type: "image", source: { type: "base64", media_type: input.photo.mediaType, data: input.photo.data } });
  }
  content.push({ type: "text", text: buildPrompt(input) });

  let ai: AiOutput | null = null;
  let note: string | null = null;
  try {
    const msg = await anthropic.beta.messages.create({
      model: MODEL,
      max_tokens: 4000,
      thinking: { type: "adaptive" },
      // If the primary model declines, Anthropic re-runs the request on its recommended fallback.
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      output_config: { format: { type: "json_schema", schema: OUTPUT_SCHEMA } },
      messages: [{ role: "user", content }],
    });

    if (msg.stop_reason === "refusal") {
      note = "The AI couldn't review this one, so you're seeing the rule-based result.";
    } else {
      const text = msg.content.find((b) => b.type === "text");
      ai = text && text.type === "text" ? (JSON.parse(text.text) as AiOutput) : null;
      if (!ai) note = "The AI review was incomplete, so you're seeing the rule-based result.";
    }
  } catch (e) {
    console.error("anthropic error", e);
    note = "The AI review is unavailable right now, so you're seeing the rule-based result.";
  }

  const result = mergeResults(input.ruleResult, ai, note);
  // Only completed AI reviews count toward the limits.
  if (ai) await admin.from("ai_checks").insert({
    user_id: user.id, pet_id: input.petId ?? null, urgency: result.urgency,
    rule_urgency: input.ruleResult.urgency, had_photo: !!input.photo,
  });
  return json(result);
});
