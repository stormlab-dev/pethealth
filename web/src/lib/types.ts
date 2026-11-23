// Mirrors backend/supabase/migrations/0001_init.sql and the Kotlin models in :core.

export type Species = "DOG" | "CAT" | "RABBIT" | "BIRD" | "REPTILE" | "OTHER";
export type Sex = "MALE" | "FEMALE" | "UNKNOWN";
export type Severity = "MILD" | "MODERATE" | "SEVERE";
export type Urgency = "EMERGENCY" | "SEE_VET_24H" | "BOOK_VISIT" | "MONITOR";

export interface Pet {
  id: string;
  name: string;
  species: Species;
  breed?: string | null;
  sex: Sex;
  neutered: boolean;
  birth_date?: string | null; // YYYY-MM-DD
  microchip_id?: string | null;
  notes?: string | null;
}

export interface SymptomEntry {
  id: string;
  pet_id: string;
  symptoms: string[];
  severity: Severity;
  started_at: string; // ISO
  logged_at: string;
  notes?: string | null;
  urgency?: Urgency | null;
}

export interface WeightEntry { id: string; pet_id: string; kilograms: number; date: string }

export interface Vaccination {
  id: string;
  pet_id: string;
  name: string;
  administered_on: string;
  valid_for_months: number | null;
  clinic_name?: string | null;
}

export interface Medication {
  id: string;
  pet_id: string;
  name: string;
  dosage: string;
  schedule_type: "DAILY" | "EVERY_N_DAYS" | "AS_NEEDED";
  times: string[]; // "08:00"
  interval_days: number;
  start_date: string;
  end_date?: string | null;
  units_remaining?: number | null;
  units_per_dose: number;
  reminders_enabled: boolean;
}

export const SPECIES: Species[] = ["DOG", "CAT", "RABBIT", "BIRD", "REPTILE", "OTHER"];

export const SYMPTOMS: { key: string; label: string; redFlag?: boolean }[] = [
  { key: "vomiting", label: "Vomiting" },
  { key: "diarrhea", label: "Diarrhea" },
  { key: "not_eating", label: "Not eating" },
  { key: "lethargy", label: "Lethargy / low energy" },
  { key: "coughing", label: "Coughing" },
  { key: "sneezing", label: "Sneezing" },
  { key: "limping", label: "Limping" },
  { key: "itching", label: "Itching / scratching" },
  { key: "drinking_more", label: "Drinking more than usual" },
  { key: "urinating_more", label: "Urinating more than usual" },
  { key: "eye", label: "Eye redness, squinting or discharge" },
  { key: "ear", label: "Ear scratching, odor or head shaking" },
  { key: "lump", label: "New lump or bump" },
  { key: "other", label: "Other" },
  { key: "breathing", label: "Difficulty breathing", redFlag: true },
  { key: "collapse", label: "Collapse / can't stand", redFlag: true },
  { key: "seizure", label: "Seizure", redFlag: true },
  { key: "pale_gums", label: "Pale, blue or grey gums", redFlag: true },
  { key: "toxin", label: "Ate something toxic", redFlag: true },
  { key: "trauma", label: "Hit by car / fall / major injury", redFlag: true },
  { key: "retching", label: "Retching without bringing anything up", redFlag: true },
  { key: "swollen_abdomen", label: "Swollen or hard belly", redFlag: true },
  { key: "straining_urinate", label: "Straining to urinate", redFlag: true },
  { key: "blood_stool", label: "Blood in stool or vomit", redFlag: true },
];

export const symptomLabel = (key: string) => SYMPTOMS.find((s) => s.key === key)?.label ?? key;
