import { describe, expect, it } from "vitest";
import { triage, type TriageInput } from "./triage";
import { addMonths, latestByName, vaccineStatus, weightTrend } from "./health";

const dog = { species: "DOG", sex: "MALE" } as const;
const maleCat = { species: "CAT", sex: "MALE" } as const;
const femaleCat = { species: "CAT", sex: "FEMALE" } as const;
const t = (pet: TriageInput["pet"], symptoms: string[], o: Partial<TriageInput> = {}) =>
  triage({ pet, symptoms, severity: "MILD", hoursSinceOnset: 2, ageYears: 4, ...o }).urgency;

// Mirrors core/src/test/.../TriageTest.kt
describe("triage", () => {
  it("breathing is an emergency", () => expect(t(dog, ["breathing"])).toBe("EMERGENCY"));
  it("dog retching is bloat", () => expect(t(dog, ["retching"])).toBe("EMERGENCY"));
  it("male cat straining is emergency, female same day", () => {
    expect(t(maleCat, ["straining_urinate"])).toBe("EMERGENCY");
    expect(t(femaleCat, ["straining_urinate"])).toBe("SEE_VET_24H");
  });
  it("brief mild vomiting in adult is monitor", () => expect(t(dog, ["vomiting"])).toBe("MONITOR"));
  it("vomiting in puppy is same day", () => expect(t(dog, ["vomiting"], { ageYears: 0.3 })).toBe("SEE_VET_24H"));
  it("vomiting over a day is same day", () => expect(t(dog, ["vomiting"], { hoursSinceOnset: 30 })).toBe("SEE_VET_24H"));
  it("cat not eating a day", () => expect(t(femaleCat, ["not_eating"], { hoursSinceOnset: 26 })).toBe("SEE_VET_24H"));
  it("rabbit not eating is emergency", () => expect(t({ species: "RABBIT", sex: "UNKNOWN" }, ["not_eating"])).toBe("EMERGENCY"));
  it("thirst is book visit", () => expect(t(dog, ["drinking_more"])).toBe("BOOK_VISIT"));
  it("recurring is book visit", () => expect(t(dog, ["sneezing"], { recent: [["sneezing"], ["sneezing"]] })).toBe("BOOK_VISIT"));
  it("weight change is book visit", () => expect(t(dog, ["sneezing"], { recentWeightChangePercent: -12 })).toBe("BOOK_VISIT"));
  it("highest wins, emergency has no watch list", () => {
    const r = triage({ pet: dog, symptoms: ["itching", "seizure"], severity: "MILD", hoursSinceOnset: 1 });
    expect(r.urgency).toBe("EMERGENCY");
    expect(r.reasons[0]).toContain("Seizure");
    expect(r.watchFor).toHaveLength(0);
  });
});

describe("health", () => {
  it("adds months clamping month end", () => expect(addMonths("2026-01-31", 1)).toBe("2026-02-28"));
  it("vaccine status", () => {
    const v = (id: string, name: string, on: string, m: number | null) => ({ id, pet_id: "p", name, administered_on: on, valid_for_months: m });
    expect(vaccineStatus(v("1", "Rabies", "2023-01-01", 12), "2026-11-22")).toBe("OVERDUE");
    expect(vaccineStatus(v("3", "DHPP", "2025-12-10", 12), "2026-11-22")).toBe("DUE_SOON");
    expect(vaccineStatus(v("2", "Rabies", "2026-01-01", 12), "2026-11-22")).toBe("CURRENT");
    expect(latestByName([v("1", "Rabies", "2023-01-01", 12), v("2", "rabies ", "2026-01-01", 12), v("3", "DHPP", "2025-12-10", 12)]).map((x) => x.id)).toEqual(["3", "2"]);
  });
  it("weight trend", () => {
    const w = (id: string, kg: number, date: string) => ({ id, pet_id: "p", kilograms: kg, date });
    const r = weightTrend([w("1", 10, "2026-09-01"), w("2", 10, "2026-10-20"), w("3", 8.8, "2026-11-22")])!;
    expect(r.changePercent).toBeCloseTo(-12);
    expect(r.overDays).toBe(33);
  });
});
