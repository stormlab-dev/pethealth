// Store product ids. Keep in step with core/.../billing/Plans.kt and web/src/lib/plans.ts.
export interface RcEvent {
  type: string;                 // INITIAL_PURCHASE, RENEWAL, CANCELLATION, EXPIRATION, PRODUCT_CHANGE, NON_RENEWING_PURCHASE, ...
  app_user_id: string;
  product_id: string;
  period_type?: "TRIAL" | "INTRO" | "NORMAL" | "PROMOTIONAL";
  expiration_at_ms?: number | null;
  store: string;
  transaction_id?: string;
  cancel_reason?: string;
}

const SUBSCRIPTIONS: Record<string, { plan: "PREMIUM" | "FAMILY"; period: "MONTHLY" | "YEARLY" }> = {
  cfp_premium_monthly: { plan: "PREMIUM", period: "MONTHLY" },
  cfp_premium_yearly: { plan: "PREMIUM", period: "YEARLY" },
  cfp_family_monthly: { plan: "FAMILY", period: "MONTHLY" },
  cfp_family_yearly: { plan: "FAMILY", period: "YEARLY" },
};

/** Google Play reports subscriptions as "<product>:<base plan>", so match the part before ':'. */
export const planFor = (productId: string) => SUBSCRIPTIONS[productId?.split(":")[0]] ?? null;
