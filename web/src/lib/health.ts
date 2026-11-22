// Ports of the small calculations in core/.../HealthCalculations.kt.
import type { Vaccination, WeightEntry } from "./types";

const DAY = 86_400_000;
const parse = (d: string) => new Date(d + "T00:00:00");
const iso = (d: Date) => d.toISOString().slice(0, 10);

export function addMonths(date: string, months: number): string {
  const d = parse(date);
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + months);
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  d.setDate(Math.min(day, last));
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export type VaccineStatus = "CURRENT" | "DUE_SOON" | "OVERDUE" | "NO_BOOSTER";

export const dueOn = (v: Vaccination) => (v.valid_for_months == null ? null : addMonths(v.administered_on, v.valid_for_months));

export function vaccineStatus(v: Vaccination, today: string): VaccineStatus {
  const due = dueOn(v);
  if (!due) return "NO_BOOSTER";
  if (due < today) return "OVERDUE";
  if (due <= iso(new Date(parse(today).getTime() + 30 * DAY))) return "DUE_SOON";
  return "CURRENT";
}

export function latestByName(all: Vaccination[]): Vaccination[] {
  const m = new Map<string, Vaccination>();
  for (const v of all) {
    const k = v.name.trim().toLowerCase();
    const cur = m.get(k);
    if (!cur || v.administered_on > cur.administered_on) m.set(k, v);
  }
  return [...m.values()].sort((a, b) => (dueOn(a) ?? "9999").localeCompare(dueOn(b) ?? "9999"));
}

export function weightTrend(entries: WeightEntry[], windowDays = 30) {
  if (entries.length < 2) return null;
  const sorted = [...entries].sort((a, b) => a.date.localeCompare(b.date));
  const latest = sorted[sorted.length - 1];
  const cutoff = iso(new Date(parse(latest.date).getTime() - windowDays * DAY));
  const baseline = [...sorted].reverse().find((e) => e.date <= cutoff) ?? sorted[0];
  if (baseline === latest || baseline.kilograms <= 0) return null;
  const change = latest.kilograms - baseline.kilograms;
  return {
    latestKg: latest.kilograms,
    changeKg: change,
    changePercent: (change / baseline.kilograms) * 100,
    overDays: Math.round((parse(latest.date).getTime() - parse(baseline.date).getTime()) / DAY),
  };
}

export function ageYears(birth: string | null | undefined, now = new Date()): number | null {
  return birth ? (now.getTime() - parse(birth).getTime()) / (365.25 * DAY) : null;
}

export const todayIso = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};
