// Data access for the web app. Uses Supabase when configured, otherwise this browser's storage
// (demo mode), behind one interface so the UI doesn't care which.
import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import type { Medication, Pet, SymptomEntry, Vaccination, WeightEntry } from "./types";

export interface Tables {
  symptom_entries: SymptomEntry;
  weights: WeightEntry;
  vaccinations: Vaccination;
  medications: Medication;
}
export type ChildTable = keyof Tables;
export type NewRow<T> = Omit<T, "id">;

export interface Store {
  readonly mode: "cloud" | "demo";
  listPets(): Promise<Pet[]>;
  savePet(pet: NewRow<Pet> & { id?: string }): Promise<Pet>;
  deletePet(id: string): Promise<void>;
  list<K extends ChildTable>(table: K, petId: string): Promise<Tables[K][]>;
  insert<K extends ChildTable>(table: K, row: NewRow<Tables[K]>): Promise<Tables[K]>;
  remove(table: ChildTable, id: string): Promise<void>;
}

const ORDER: Record<ChildTable, [string, boolean]> = {
  symptom_entries: ["logged_at", false],
  weights: ["date", true],
  vaccinations: ["administered_on", false],
  medications: ["name", true],
};

// ---------------------------------------------------------------- Supabase

class SupabaseStore implements Store {
  readonly mode = "cloud" as const;
  constructor(readonly client: SupabaseClient) {}

  private check<T>(r: { data: T | null; error: { message: string } | null }): T {
    if (r.error) throw new Error(r.error.message);
    return r.data as T;
  }

  async listPets() {
    return this.check(await this.client.from("pets").select("*").is("deleted_at", null).order("name"));
  }
  async savePet(pet: NewRow<Pet> & { id?: string }) {
    const q = pet.id ? this.client.from("pets").update(pet).eq("id", pet.id) : this.client.from("pets").insert(pet);
    return this.check(await q.select().single()) as Pet;
  }
  async deletePet(id: string) {
    // Soft delete so other devices learn about it on their next sync.
    this.check(await this.client.from("pets").update({ deleted_at: new Date().toISOString() }).eq("id", id));
  }
  async list<K extends ChildTable>(table: K, petId: string) {
    const [col, asc] = ORDER[table];
    return this.check(
      await this.client.from(table).select("*").eq("pet_id", petId).is("deleted_at", null).order(col, { ascending: asc }),
    ) as Tables[K][];
  }
  async insert<K extends ChildTable>(table: K, row: NewRow<Tables[K]>) {
    return this.check(await this.client.from(table).insert(row).select().single()) as Tables[K];
  }
  async remove(table: ChildTable, id: string) {
    this.check(await this.client.from(table).update({ deleted_at: new Date().toISOString() }).eq("id", id));
  }
}

// ---------------------------------------------------------------- Local (demo)

export class LocalStore implements Store {
  readonly mode = "demo" as const;
  constructor(private storage: Pick<Storage, "getItem" | "setItem"> = window.localStorage) {}

  private read<T>(key: string): T[] {
    try {
      return JSON.parse(this.storage.getItem("petpulse." + key) ?? "[]");
    } catch {
      return [];
    }
  }
  private write(key: string, rows: unknown[]) {
    try {
      this.storage.setItem("petpulse." + key, JSON.stringify(rows));
    } catch {
      /* storage full or blocked: keep going in memory */
    }
  }

  async listPets() {
    return this.read<Pet>("pets").sort((a, b) => a.name.localeCompare(b.name));
  }
  async savePet(pet: NewRow<Pet> & { id?: string }) {
    const rows = this.read<Pet>("pets");
    const saved = { ...pet, id: pet.id ?? crypto.randomUUID() } as Pet;
    this.write("pets", [...rows.filter((p) => p.id !== saved.id), saved]);
    return saved;
  }
  async deletePet(id: string) {
    this.write("pets", this.read<Pet>("pets").filter((p) => p.id !== id));
    for (const t of Object.keys(ORDER)) this.write(t, this.read<{ pet_id: string }>(t).filter((r) => r.pet_id !== id));
  }
  async list<K extends ChildTable>(table: K, petId: string) {
    const [col, asc] = ORDER[table];
    const rows = this.read<Tables[K]>(table).filter((r) => r.pet_id === petId);
    return rows.sort((a, b) => {
      const c = String((a as unknown as Record<string, unknown>)[col]).localeCompare(String((b as unknown as Record<string, unknown>)[col]));
      return asc ? c : -c;
    });
  }
  async insert<K extends ChildTable>(table: K, row: NewRow<Tables[K]>) {
    const saved = { ...row, id: crypto.randomUUID() } as Tables[K];
    this.write(table, [...this.read<Tables[K]>(table), saved]);
    return saved;
  }
  async remove(table: ChildTable, id: string) {
    this.write(table, this.read<{ id: string }>(table).filter((r) => r.id !== id));
  }
}

const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const key = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;

export const supabase: SupabaseClient | null = url && key ? createClient(url, key) : null;
export const store: Store = supabase ? new SupabaseStore(supabase) : new LocalStore();
