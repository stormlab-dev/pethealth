// Receives RevenueCat webhooks and records subscriptions and one-time purchases.
// RevenueCat sits in front of Google Play Billing and Apple StoreKit, so one webhook covers both.
// The apps call Purchases.logIn(<supabase user id>), so app_user_id is the Supabase user id.
//
// Deploy:  supabase functions deploy billing-webhook --no-verify-jwt
// Secrets: supabase secrets set REVENUECAT_WEBHOOK_AUTH=<long random string>
// RevenueCat → Project settings → Integrations → Webhooks:
//   URL https://<project>.supabase.co/functions/v1/billing-webhook
//   Authorization header: Bearer <the same random string>
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import { planFor, type RcEvent } from "./plans.ts";

const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

Deno.serve(async (req) => {
  if (req.headers.get("Authorization") !== `Bearer ${Deno.env.get("REVENUECAT_WEBHOOK_AUTH")}`) {
    return new Response("unauthorized", { status: 401 });
  }
  const { event } = (await req.json()) as { event: RcEvent };
  const userId = event.app_user_id;
  if (!/^[0-9a-f-]{36}$/i.test(userId ?? "")) return new Response("ignored: anonymous user", { status: 200 });

  const sub = planFor(event.product_id);
  if (sub) {
    const active = event.type !== "EXPIRATION" && !(event.type === "CANCELLATION" && event.cancel_reason === "CUSTOMER_SUPPORT");
    const { error } = await admin.from("entitlements").upsert({
      user_id: userId,
      plan: active ? sub.plan : "FREE",
      period: sub.period,
      // Cancelled subscriptions stay active until they expire.
      expires_at: event.expiration_at_ms ? new Date(event.expiration_at_ms).toISOString() : null,
      in_trial: event.period_type === "TRIAL",
      store: event.store === "PLAY_STORE" || event.store === "APP_STORE" || event.store === "STRIPE" ? event.store : "PROMO",
      updated_at: new Date().toISOString(),
    });
    if (error) return new Response(error.message, { status: 500 });
  } else if (event.type === "NON_RENEWING_PURCHASE" && event.transaction_id) {
    const { error } = await admin.from("purchases").upsert({
      user_id: userId, product_id: event.product_id, store: event.store, transaction_id: event.transaction_id,
    }, { onConflict: "transaction_id" });
    if (error) return new Response(error.message, { status: 500 });
    if (event.product_id === "cfp_contest_boost") {
      // Boost the buyer's newest entry in this week's Cutest Pet contest.
      const { data: entry } = await admin.from("contest_entries").select("id").eq("user_id", userId)
        .order("created_at", { ascending: false }).limit(1).maybeSingle();
      if (entry) await admin.from("contest_entries").update({ boosted: true }).eq("id", entry.id);
    }
  } else if (event.type === "CANCELLATION" && event.transaction_id) {
    await admin.from("purchases").update({ status: "REFUNDED" }).eq("transaction_id", event.transaction_id);
  }
  return new Response("ok");
});
