// TypeScript port of core/.../RuleBasedTriageEngine.kt. Keep the two in step; triage.test.ts
// mirrors TriageTest.kt. Long term, move triage to one backend function both apps call.
import type { Pet, Severity, Urgency } from "./types";

export const HEADLINES: Record<Urgency, string> = {
  EMERGENCY: "Go to an emergency vet now",
  SEE_VET_24H: "Call your vet today",
  BOOK_VISIT: "Book a vet visit this week",
  MONITOR: "Monitor at home",
};

export const DISCLAIMER =
  "PetPulse gives general guidance, not a diagnosis. If you are worried, call your vet. " +
  "In an emergency, go to the nearest emergency clinic.";

const ORDER: Urgency[] = ["EMERGENCY", "SEE_VET_24H", "BOOK_VISIT", "MONITOR"];

export interface TriageInput {
  pet: Pick<Pet, "species" | "sex">;
  symptoms: string[];
  severity: Severity;
  hoursSinceOnset: number;
  ageYears?: number | null;
  /** Symptom lists from the last 14 days, excluding this entry. */
  recent?: string[][];
  recentWeightChangePercent?: number | null;
}

export interface TriageResult { urgency: Urgency; reasons: string[]; watchFor: string[] }

export function triage(i: TriageInput): TriageResult {
  const s = new Set(i.symptoms);
  const has = (k: string) => s.has(k);
  const f: [Urgency, string][] = [];
  const add = (u: Urgency, why: string) => f.push([u, why]);
  const h = i.hoursSinceOnset;
  const { species, sex } = i.pet;

  if (has("breathing")) add("EMERGENCY", "Difficulty breathing is always an emergency.");
  if (has("pale_gums")) add("EMERGENCY", "Pale, blue or grey gums can mean shock or poor oxygen.");
  if (has("seizure")) add("EMERGENCY", "Seizures need a vet straight away, especially a first seizure.");
  if (has("collapse")) add("EMERGENCY", "Collapse or being unable to stand needs immediate care.");
  if (has("toxin")) add("EMERGENCY", "Possible poisoning: call your vet or a pet poison hotline now, even if your pet seems fine.");
  if (has("trauma")) add("EMERGENCY", "Major injuries can cause internal bleeding that isn't visible.");
  if (has("retching") && species === "DOG") add("EMERGENCY", "Retching without vomiting can be a sign of bloat (GDV), which is life-threatening in dogs.");
  if (has("swollen_abdomen") && (has("retching") || has("lethargy"))) add("EMERGENCY", "A swollen belly with retching or weakness needs emergency assessment.");
  if (has("straining_urinate")) {
    if (species === "CAT" && sex === "MALE") add("EMERGENCY", "Male cats straining to pee may have a urinary blockage, which can be fatal within a day.");
    else add("SEE_VET_24H", "Straining to urinate should be checked by a vet today.");
  }
  if (has("not_eating") && species === "RABBIT") add("EMERGENCY", "Rabbits that stop eating can develop GI stasis quickly.");
  if (has("blood_stool")) {
    if (i.severity === "SEVERE" || has("lethargy")) add("EMERGENCY", "Large amounts of blood, or blood with weakness, needs immediate care.");
    else add("SEE_VET_24H", "Blood in stool or vomit should be seen by a vet today.");
  }

  const young = i.ageYears != null && i.ageYears < 0.5;
  const senior = i.ageYears != null && i.ageYears >= 10;
  const gi = has("vomiting") || has("diarrhea");
  if (has("vomiting") && has("diarrhea")) add("SEE_VET_24H", "Vomiting and diarrhea together can dehydrate pets fast.");
  if (gi && h >= 24) add("SEE_VET_24H", "GI signs lasting more than a day need a vet check.");
  if (gi && (young || senior)) add("SEE_VET_24H", young ? "Puppies and kittens dehydrate quickly." : "Senior pets are less able to cope with GI illness.");
  if (has("not_eating") && species === "CAT" && h >= 24) add("SEE_VET_24H", "Cats that don't eat for a day or more risk liver problems (hepatic lipidosis).");
  if (has("not_eating") && h >= 48) add("SEE_VET_24H", "Not eating for two days or more needs a vet check.");
  if (has("lethargy") && has("not_eating")) add("SEE_VET_24H", "Low energy combined with not eating is a sign of real illness.");
  if (has("eye")) add("SEE_VET_24H", "Eye problems can get worse quickly and threaten sight.");
  if (i.severity === "SEVERE") add("SEE_VET_24H", "You rated this as severe.");

  if (has("drinking_more") || has("urinating_more")) add("BOOK_VISIT", "Drinking or peeing more can point to kidney disease, diabetes or hormone problems; bloodwork helps.");
  if (has("limping")) add(i.severity === "MILD" && h < 48 ? "MONITOR" : "BOOK_VISIT", "Limping that is more than mild, or lasts over two days, should be examined.");
  if (has("coughing") && (h >= 72 || senior)) add("BOOK_VISIT", "A cough lasting several days should be examined.");
  if (has("itching") || has("ear")) add("BOOK_VISIT", "Skin and ear problems rarely clear up without treatment.");
  if (has("lump")) add("BOOK_VISIT", "New lumps should be checked; a quick needle test can tell what they are.");
  const w = i.recentWeightChangePercent;
  if (w != null && Math.abs(w) >= 10) add("BOOK_VISIT", `Weight changed ${Math.abs(w).toFixed(0)}% in the last month, which is worth discussing with your vet.`);
  const repeats = i.symptoms.filter((k) => k !== "other" && (i.recent ?? []).filter((r) => r.includes(k)).length >= 2);
  if (repeats.length) add("BOOK_VISIT", `This keeps coming back (${repeats.join(", ")}), 3+ times in two weeks.`);
  if (i.severity === "MODERATE") add("BOOK_VISIT", "You rated this as moderate.");

  if (!f.length) add("MONITOR", "Nothing here suggests an urgent problem right now.");

  f.sort((a, b) => ORDER.indexOf(a[0]) - ORDER.indexOf(b[0]));
  const urgency = f[0][0];
  const reasons = [...new Set(f.map((x) => x[1]))];
  const watchFor: string[] = [];
  if (urgency !== "EMERGENCY") {
    watchFor.push("Trouble breathing, collapse, or pale gums: go to an emergency vet.");
    if (gi) watchFor.push("Can't keep water down, blood, or getting weaker: call your vet today.");
    if (has("not_eating")) watchFor.push("Still not eating tomorrow: call your vet.");
    if (has("limping")) watchFor.push("Won't put any weight on the leg, or swelling: book a visit.");
  }
  return { urgency, reasons, watchFor };
}
