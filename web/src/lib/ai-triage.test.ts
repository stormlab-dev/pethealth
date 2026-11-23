import { describe, expect, it } from "vitest";
import { mergeResults, validateRequest, buildPrompt, type AiOutput } from "../../../backend/supabase/functions/ai-triage/logic";

const rule = { urgency: "BOOK_VISIT" as const, reasons: ["Skin problems rarely clear up alone."], watchFor: ["Spreading redness"] };
const ai = (urgency: AiOutput["urgency"]): AiOutput => ({
  urgency, photo_quality: "CLEAR", photo_findings: ["Red, moist patch about 3 cm wide"], reasons: ["Looks like a hot spot."], watch_for: ["Pus or a bad smell"],
});

describe("mergeResults", () => {
  it("lets the AI raise urgency", () => {
    const r = mergeResults(rule, ai("SEE_VET_24H"));
    expect(r.urgency).toBe("SEE_VET_24H");
    expect(r.raisedByAi).toBe(true);
    expect(r.reasons[0]).toBe(rule.reasons[0]);
    expect(r.photoFindings).toHaveLength(1);
  });
  it("never lets the AI lower urgency", () => {
    const r = mergeResults({ ...rule, urgency: "EMERGENCY" }, ai("MONITOR"));
    expect(r.urgency).toBe("EMERGENCY");
    expect(r.raisedByAi).toBe(false);
    expect(r.note).toMatch(/safety rules/);
  });
  it("falls back to the rules when the AI gave nothing", () => {
    const r = mergeResults(rule, null, "unavailable");
    expect(r).toMatchObject({ urgency: "BOOK_VISIT", aiUrgency: null, note: "unavailable" });
  });
});

describe("validateRequest", () => {
  const base = { pet: { species: "DOG" }, symptoms: ["Itching"], severity: "MILD", hoursSinceOnset: 5, ruleResult: rule };
  it("accepts a normal request", () => expect(validateRequest(base).symptoms).toEqual(["Itching"]));
  it("rejects bad photos", () => {
    expect(() => validateRequest({ ...base, photo: { mediaType: "image/bmp", data: "x" } })).toThrow(/JPEG/);
    expect(() => validateRequest({ ...base, photo: { mediaType: "image/jpeg", data: "x".repeat(8_000_000) } })).toThrow(/large/);
  });
  it("needs something to look at", () => expect(() => validateRequest({ ...base, symptoms: [] })).toThrow());
  it("puts the rule floor in the prompt", () => expect(buildPrompt(validateRequest(base))).toContain("rated this BOOK_VISIT"));
});
