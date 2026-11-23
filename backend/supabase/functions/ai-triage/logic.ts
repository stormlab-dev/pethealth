// Pure logic for the ai-triage function, kept free of Deno and network code so it can be unit
// tested (see web/src/lib/ai-triage.test.ts).

export type Urgency = "EMERGENCY" | "SEE_VET_24H" | "BOOK_VISIT" | "MONITOR";
export const URGENCIES: Urgency[] = ["EMERGENCY", "SEE_VET_24H", "BOOK_VISIT", "MONITOR"];
const MEDIA_TYPES = ["image/jpeg", "image/png", "image/webp", "image/gif"] as const;
export type ImageMediaType = (typeof MEDIA_TYPES)[number];

export interface RuleResult { urgency: Urgency; reasons: string[]; watchFor: string[] }

export interface TriageRequest {
  petId?: string;
  pet: { species: string; sex?: string; ageYears?: number | null; weightKg?: number | null; breed?: string | null };
  symptoms: string[];          // labels, e.g. "Vomiting"
  severity: "MILD" | "MODERATE" | "SEVERE";
  hoursSinceOnset: number;
  notes?: string;
  ruleResult: RuleResult;
  photo?: { mediaType: ImageMediaType; data: string };  // base64, no data: prefix
}

export interface AiOutput {
  urgency: Urgency;
  photo_quality: "CLEAR" | "UNCLEAR" | "NOT_RELEVANT" | "NO_PHOTO";
  photo_findings: string[];
  reasons: string[];
  watch_for: string[];
}

export interface MergedResult {
  urgency: Urgency;
  ruleUrgency: Urgency;
  aiUrgency: Urgency | null;
  raisedByAi: boolean;
  reasons: string[];
  photoFindings: string[];
  photoQuality: AiOutput["photo_quality"] | null;
  watchFor: string[];
  note: string | null;
}

/** Structured-output schema Claude must follow. */
export const OUTPUT_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["urgency", "photo_quality", "photo_findings", "reasons", "watch_for"],
  properties: {
    urgency: { type: "string", enum: URGENCIES },
    photo_quality: { type: "string", enum: ["CLEAR", "UNCLEAR", "NOT_RELEVANT", "NO_PHOTO"] },
    photo_findings: { type: "array", items: { type: "string" } },
    reasons: { type: "array", items: { type: "string" } },
    watch_for: { type: "array", items: { type: "string" } },
  },
} as const;

const MAX_PHOTO_BASE64 = 7_000_000; // about 5 MB of image; apps downscale to 1568 px first

export function validateRequest(body: unknown): TriageRequest {
  const b = body as Partial<TriageRequest> | null;
  if (!b || typeof b !== "object") throw new Error("Expected a JSON body.");
  if (!b.pet || typeof b.pet.species !== "string") throw new Error("pet.species is required.");
  if (!Array.isArray(b.symptoms) || b.symptoms.some((s) => typeof s !== "string")) throw new Error("symptoms must be a list of strings.");
  if (!b.severity || !["MILD", "MODERATE", "SEVERE"].includes(b.severity)) throw new Error("severity must be MILD, MODERATE or SEVERE.");
  if (!b.ruleResult || !URGENCIES.includes(b.ruleResult.urgency)) throw new Error("ruleResult.urgency is required.");
  if (!b.symptoms.length && !b.photo && !b.notes) throw new Error("Add a symptom, a note or a photo.");
  if (b.photo) {
    if (!MEDIA_TYPES.includes(b.photo.mediaType)) throw new Error("Photo must be JPEG, PNG, WebP or GIF.");
    if (typeof b.photo.data !== "string" || b.photo.data.length > MAX_PHOTO_BASE64) throw new Error("Photo is too large.");
  }
  return {
    ...b,
    symptoms: b.symptoms.slice(0, 40).map((s) => s.slice(0, 80)),
    notes: b.notes?.slice(0, 2000),
    hoursSinceOnset: Math.max(0, Number(b.hoursSinceOnset) || 0),
    ruleResult: { urgency: b.ruleResult.urgency, reasons: b.ruleResult.reasons ?? [], watchFor: b.ruleResult.watchFor ?? [] },
  } as TriageRequest;
}

export function buildPrompt(r: TriageRequest): string {
  const p = r.pet;
  const facts = [
    `Species: ${p.species}${p.breed ? ` (${p.breed})` : ""}`,
    p.sex ? `Sex: ${p.sex}` : null,
    p.ageYears != null ? `Age: ${p.ageYears.toFixed(1)} years` : null,
    p.weightKg != null ? `Weight: ${p.weightKg} kg` : null,
  ].filter(Boolean).join("\n");
  return `You are the triage reviewer inside CuteFamilyPet, a pet health app for owners (not vets).
Decide how urgently this pet should see a veterinarian.

Pet
${facts}

What the owner reports
Symptoms: ${r.symptoms.length ? r.symptoms.join(", ") : "none selected"}
Owner's severity rating: ${r.severity}
Started about ${Math.round(r.hoursSinceOnset)} hours ago
Notes: ${r.notes?.trim() || "none"}
Photo attached: ${r.photo ? "yes (above)" : "no"}

The app's safety rules already rated this ${r.ruleResult.urgency} because:
${r.ruleResult.reasons.map((x) => `- ${x}`).join("\n") || "- no specific rule matched"}

Urgency levels, most to least urgent:
EMERGENCY = go to an emergency vet now; SEE_VET_24H = call the vet today;
BOOK_VISIT = book a visit within the week; MONITOR = watch at home.

Instructions
- If there is a photo, describe only what is actually visible and relevant (for example redness, swelling, discharge, wound size, gum colour, stool appearance). Say plainly when the photo is blurry, too dark, or does not show the problem, and set photo_quality accordingly. Use NO_PHOTO when none was attached.
- Choose the urgency the whole picture deserves. You may choose a MORE urgent level than the safety rules if the photo or notes justify it. Never treat the situation as less urgent than the rules because of a photo; a photo cannot rule out internal problems.
- Do not diagnose. Name possibilities only as "can be a sign of" and keep each point to one or two short sentences in plain language an anxious owner can follow.
- reasons: 1 to 4 points explaining your urgency choice. watch_for: 1 to 4 concrete signs that should make the owner act sooner.
- If anything suggests poisoning, breathing trouble, collapse, heavy bleeding, bloat or a blocked male cat, the urgency is EMERGENCY.`;
}

const rank = (u: Urgency) => URGENCIES.indexOf(u); // lower = more urgent

/** The final answer never rates lower than the safety rules. */
export function mergeResults(rule: RuleResult, ai: AiOutput | null, note: string | null = null): MergedResult {
  if (!ai || !URGENCIES.includes(ai.urgency)) {
    return { urgency: rule.urgency, ruleUrgency: rule.urgency, aiUrgency: null, raisedByAi: false, reasons: rule.reasons,
      photoFindings: [], photoQuality: null, watchFor: rule.watchFor, note };
  }
  const urgency = rank(ai.urgency) < rank(rule.urgency) ? ai.urgency : rule.urgency;
  const raisedByAi = urgency !== rule.urgency;
  const dedupe = (xs: string[]) => [...new Set(xs.map((x) => x.trim()).filter(Boolean))];
  return {
    urgency,
    ruleUrgency: rule.urgency,
    aiUrgency: ai.urgency,
    raisedByAi,
    // Rule reasons first: they are the safety floor.
    reasons: dedupe([...rule.reasons, ...ai.reasons]).slice(0, 8),
    photoFindings: dedupe(ai.photo_findings).slice(0, 6),
    photoQuality: ai.photo_quality,
    watchFor: dedupe([...ai.watch_for, ...rule.watchFor]).slice(0, 6),
    note: note ?? (rank(ai.urgency) > rank(rule.urgency)
      ? "The AI thought this looked less urgent, but we keep the safety rules' advice to be careful." : null),
  };
}
