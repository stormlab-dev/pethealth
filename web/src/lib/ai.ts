// Calls the ai-triage server function. The Anthropic key never reaches the browser.
import { supabase } from "./store";
import type { TriageResult } from "./triage";
import type { Pet, Severity, Urgency } from "./types";

export interface AiTriageResult {
  urgency: Urgency; ruleUrgency: Urgency; aiUrgency: Urgency | null; raisedByAi: boolean;
  reasons: string[]; photoFindings: string[]; photoQuality: string | null; watchFor: string[]; note: string | null;
}
export class AiError extends Error { constructor(message: string, readonly code?: string) { super(message); } }

export const aiAvailable = !!supabase;

/** Shrinks a photo to at most 1568 px on the long side (the size the model reads best) as JPEG. */
export async function photoForAi(file: File): Promise<{ mediaType: "image/jpeg"; data: string }> {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, 1568 / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bmp.width * scale);
  canvas.height = Math.round(bmp.height * scale);
  canvas.getContext("2d")!.drawImage(bmp, 0, 0, canvas.width, canvas.height);
  const url = canvas.toDataURL("image/jpeg", 0.85);
  return { mediaType: "image/jpeg", data: url.slice(url.indexOf(",") + 1) };
}

export async function aiTriage(args: {
  pet: Pet; ageYears: number | null; weightKg: number | null; symptomLabels: string[]; severity: Severity;
  hoursSinceOnset: number; notes: string; rule: TriageResult; photo: File | null;
}): Promise<AiTriageResult> {
  if (!supabase) throw new AiError("Sign in to use the AI symptom checker. It runs on our secure server.", "NO_CLOUD");
  const body = {
    petId: args.pet.id,
    pet: { species: args.pet.species, sex: args.pet.sex, breed: args.pet.breed, ageYears: args.ageYears, weightKg: args.weightKg },
    symptoms: args.symptomLabels, severity: args.severity, hoursSinceOnset: args.hoursSinceOnset, notes: args.notes,
    ruleResult: args.rule,
    photo: args.photo ? await photoForAi(args.photo) : undefined,
  };
  const { data, error } = await supabase.functions.invoke("ai-triage", { body });
  if (error) {
    let msg = "The AI check is unavailable right now. The rule-based result still applies.";
    let code: string | undefined;
    try {
      const j = await (error as { context?: Response }).context?.json();
      if (j?.error) { msg = j.error; code = j.code; }
    } catch { /* keep default */ }
    throw new AiError(msg, code);
  }
  return data as AiTriageResult;
}
