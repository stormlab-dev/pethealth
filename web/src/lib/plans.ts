// Plans, products and limits. Keep in step with core/.../billing/Plans.kt.
import { supabase } from "./store";

export type Plan = "FREE" | "PREMIUM" | "FAMILY";
export interface Limits { maxMedia: number | null; maxClipSeconds: number; maxSharedPeople: number; aiPhotoCheck: boolean; vetReports: boolean }
export const LIMITS: Record<Plan, Limits> = {
  FREE: { maxMedia: 200, maxClipSeconds: 15, maxSharedPeople: 1, aiPhotoCheck: false, vetReports: false },
  PREMIUM: { maxMedia: null, maxClipSeconds: 180, maxSharedPeople: 1, aiPhotoCheck: true, vetReports: true },
  FAMILY: { maxMedia: null, maxClipSeconds: 180, maxSharedPeople: 5, aiPhotoCheck: true, vetReports: true },
};

export interface Product { id: string; title: string; description: string; price: string; plan?: Plan; trialDays?: number; kind: "SUB" | "ONE_TIME" | "PRINT" }
export const SUBSCRIPTIONS: Product[] = [
  { id: "cfp_premium_monthly", title: "Premium monthly", description: "$3.99 a month", price: "$3.99", plan: "PREMIUM", kind: "SUB" },
  { id: "cfp_premium_yearly", title: "Premium yearly", description: "$29.99 a year, save 37%. 7-day free trial", price: "$29.99", plan: "PREMIUM", trialDays: 7, kind: "SUB" },
  { id: "cfp_family_monthly", title: "Family monthly", description: "$5.99 a month for up to 6 people", price: "$5.99", plan: "FAMILY", kind: "SUB" },
  { id: "cfp_family_yearly", title: "Family yearly", description: "$44.99 a year, save 37%. 7-day free trial", price: "$44.99", plan: "FAMILY", trialDays: 7, kind: "SUB" },
];
export const EXTRAS: Product[] = [
  { id: "cfp_stickers_seasonal", title: "🎨 Seasonal sticker pack", description: "Frames, stickers and filters for every season", price: "$0.99", kind: "ONE_TIME" },
  { id: "cfp_stickers_birthday", title: "🎂 Birthday and gotcha-day pack", description: "Party hats, cakes and confetti", price: "$1.99", kind: "ONE_TIME" },
  { id: "cfp_reel_monthly", title: "🎬 Monthly highlight reel", description: "An AI-made video of this month's best moments", price: "$2.99", kind: "ONE_TIME" },
  { id: "cfp_reel_yearly", title: "🎞️ Year in review reel", description: "Your pet's year, set to music", price: "$4.99", kind: "ONE_TIME" },
  { id: "cfp_portrait", title: "🖼️ AI pet portrait", description: "Cartoon, oil painting or superhero styles", price: "$2.99", kind: "ONE_TIME" },
  { id: "cfp_memorial_album", title: "🕯️ Memorial album", description: "A lasting tribute for a pet who has passed", price: "$4.99", kind: "ONE_TIME" },
  { id: "cfp_contest_boost", title: "🏆 Contest boost", description: "Feature your Cutest Pet of the Week entry", price: "$0.99", kind: "ONE_TIME" },
];
export const PRINTS: Product[] = [
  { id: "print_photo_book", title: "📚 Photo book", description: "20 pages, hardcover, 8×8 in", price: "$29.99", kind: "PRINT" },
  { id: "print_calendar", title: "📅 Wall calendar", description: "12 months of your pet", price: "$19.99", kind: "PRINT" },
  { id: "print_mug", title: "☕ Photo mug", description: "11 oz ceramic", price: "$14.99", kind: "PRINT" },
  { id: "print_id_tag", title: "🏷️ Engraved ID tag", description: "Stainless steel with photo back", price: "$9.99", kind: "PRINT" },
];

export interface Customer { plan: Plan; inTrial: boolean; expiresAt: string | null; owned: string[]; demo: boolean }

const KEY = "cutefamilypet.demoCustomer";
function readDemo(): Customer {
  try {
    const c = JSON.parse(localStorage.getItem(KEY) ?? "null") as Customer | null;
    if (c && (!c.expiresAt || new Date(c.expiresAt) > new Date())) return { ...c, demo: true };
  } catch { /* storage blocked */ }
  return { plan: "FREE", inTrial: false, expiresAt: null, owned: [], demo: true };
}

/** In cloud mode the server's entitlements row is the truth (written by the billing webhook). */
export async function getCustomer(): Promise<Customer> {
  if (!supabase) return readDemo();
  const { data: plan } = await supabase.rpc("current_plan");
  const { data: ent } = await supabase.from("entitlements").select("in_trial, expires_at").maybeSingle();
  const { data: buys } = await supabase.from("purchases").select("product_id").eq("status", "PAID");
  return { plan: (plan as Plan) ?? "FREE", inTrial: !!ent?.in_trial, expiresAt: ent?.expires_at ?? null, owned: (buys ?? []).map((b) => b.product_id), demo: false };
}

/** Demo-mode purchase: nothing is charged. Real purchases happen in the Android and iPhone apps. */
export function demoPurchase(p: Product): Customer {
  const c = readDemo();
  if (p.kind === "SUB" && p.plan) {
    const trial = !!p.trialDays && c.plan === "FREE";
    const days = trial ? p.trialDays! : p.id.endsWith("yearly") ? 365 : 30;
    Object.assign(c, { plan: p.plan, inTrial: trial, expiresAt: new Date(Date.now() + days * 86_400_000).toISOString() });
  } else if (!c.owned.includes(p.id)) c.owned.push(p.id);
  try { localStorage.setItem(KEY, JSON.stringify(c)); } catch { /* ignore */ }
  return c;
}
export function demoReset() { try { localStorage.removeItem(KEY); } catch { /* ignore */ } }

export type Gate = { ok: true } | { ok: false; reason: string; suggested: Plan };
export function canAddMedia(plan: Plan, count: number, isVideo: boolean, seconds = 0): Gate {
  const l = LIMITS[plan];
  if (l.maxMedia != null && count >= l.maxMedia) return { ok: false, reason: `The free plan holds ${l.maxMedia} photos and videos. Premium is unlimited.`, suggested: "PREMIUM" };
  if (isVideo && seconds > l.maxClipSeconds) {
    return plan === "FREE"
      ? { ok: false, reason: `Free clips can be up to ${l.maxClipSeconds} seconds. Premium allows 3 minutes.`, suggested: "PREMIUM" }
      : { ok: false, reason: "Clips can be up to 3 minutes. Trim it and try again.", suggested: plan };
  }
  return { ok: true };
}
