import { useEffect, useRef, useState } from "react";
import { aiAvailable, aiTriage, AiError, type AiTriageResult } from "../lib/ai";
import { getCustomer, LIMITS, type Plan } from "../lib/plans";
import { MediaTab } from "./Media";
import { store, type ChildTable, type Tables } from "../lib/store";
import { DISCLAIMER, HEADLINES, triage, type TriageResult } from "../lib/triage";
import { ageYears, dueOn, latestByName, todayIso, vaccineStatus, weightTrend, type VaccineStatus } from "../lib/health";
import { SPECIES, SYMPTOMS, symptomLabel, type Medication, type Pet, type Severity, type Sex, type Species, type Urgency } from "../lib/types";

export const speciesEmoji = (s: Species) => ({ DOG: "🐕", CAT: "🐈", RABBIT: "🐇", BIRD: "🦜", REPTILE: "🦎", OTHER: "🐾" })[s];

const URGENCY_COLOR: Record<Urgency, string> = { EMERGENCY: "var(--emergency)", SEE_VET_24H: "var(--today)", BOOK_VISIT: "var(--week)", MONITOR: "var(--ok)" };
const VACCINE_COLOR: Record<VaccineStatus, string> = { OVERDUE: "var(--today)", DUE_SOON: "var(--week)", CURRENT: "var(--ok)", NO_BOOSTER: "var(--muted)" };

function Pill({ text, color }: { text: string; color: string }) {
  return <span className="pill" style={{ color, background: `color-mix(in srgb, ${color} 15%, transparent)` }}>{text}</span>;
}

/** Loads one child table for a pet and exposes add/remove. */
function useRows<K extends ChildTable>(table: K, petId: string) {
  const [rows, setRows] = useState<Tables[K][]>([]);
  const reload = () => store.list(table, petId).then(setRows);
  useEffect(() => { reload(); }, [table, petId]);
  return {
    rows,
    add: async (row: Omit<Tables[K], "id">) => { await store.insert(table, row); await reload(); },
    remove: async (id: string) => { await store.remove(table, id); await reload(); },
  };
}

// ---------------------------------------------------------------- Pet form

export function PetForm({ initial, onSaved, onCancel, onDeleted }: { initial?: Pet; onSaved: (p: Pet) => void; onCancel: () => void; onDeleted: () => void }) {
  const [p, setP] = useState<Omit<Pet, "id"> & { id?: string }>(initial ?? { name: "", species: "DOG", sex: "UNKNOWN", neutered: false });
  const set = (patch: Partial<Pet>) => setP({ ...p, ...patch });
  return (
    <div className="card">
      <h3>{initial ? `Edit ${initial.name}` : "Add pet"}</h3>
      <label>Name</label>
      <input value={p.name} onChange={(e) => set({ name: e.target.value })} />
      <div className="grid2">
        <div>
          <label>Species</label>
          <select value={p.species} onChange={(e) => set({ species: e.target.value as Species })}>
            {SPECIES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </select>
        </div>
        <div>
          <label>Sex</label>
          <select value={p.sex} onChange={(e) => set({ sex: e.target.value as Sex })}>
            {["MALE", "FEMALE", "UNKNOWN"].map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </select>
        </div>
        <div><label>Breed</label><input value={p.breed ?? ""} onChange={(e) => set({ breed: e.target.value })} /></div>
        <div><label>Birthday</label><input type="date" value={p.birth_date ?? ""} max={todayIso()} onChange={(e) => set({ birth_date: e.target.value || null })} /></div>
      </div>
      <label><input type="checkbox" style={{ width: "auto" }} checked={p.neutered} onChange={(e) => set({ neutered: e.target.checked })} /> Spayed / neutered</label>
      <label>Notes: allergies, conditions, diet</label>
      <textarea rows={3} value={p.notes ?? ""} onChange={(e) => set({ notes: e.target.value })} />
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!p.name.trim()} onClick={async () => onSaved(await store.savePet(p))}>Save</button>
        <button onClick={onCancel}>Cancel</button>
        <span className="grow" />
        {initial && <button className="link" style={{ color: "var(--emergency)" }} onClick={async () => {
          if (confirm(`Delete ${initial.name} and all their logs?`)) { await store.deletePet(initial.id); onDeleted(); }
        }}>Delete</button>}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Pet detail

const TABS = ["Overview", "Symptoms", "Photos", "Weight", "Vaccines", "Meds"] as const;

export function PetDetail({ pet, onBack, onEdit, onPlans }: { pet: Pet; onBack: () => void; onEdit: () => void; onPlans: () => void }) {
  const [tab, setTab] = useState<(typeof TABS)[number]>("Overview");
  const symptoms = useRows("symptom_entries", pet.id);
  const weights = useRows("weights", pet.id);
  const vaccines = useRows("vaccinations", pet.id);
  const meds = useRows("medications", pet.id);
  const today = todayIso();
  const trend = weightTrend(weights.rows);

  return (
    <>
      <header className="top">
        <button className="link" onClick={onBack}>← Pets</button>
        <h1>{speciesEmoji(pet.species)} {pet.name}</h1>
        <button onClick={onEdit}>Edit</button>
      </header>
      <nav className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? "on" : ""} onClick={() => setTab(t)}>{t}</button>)}</nav>

      {tab === "Overview" && (
        <>
          <div className="card">
            <h3>Vaccines due</h3>
            {latestByName(vaccines.rows).filter((v) => ["OVERDUE", "DUE_SOON"].includes(vaccineStatus(v, today))).map((v) => (
              <div key={v.id} className="list-item"><span className="grow">{v.name} · due {dueOn(v)}</span><Pill text={vaccineStatus(v, today).replace("_", " ").toLowerCase()} color={VACCINE_COLOR[vaccineStatus(v, today)]} /></div>
            ))}
            {vaccines.rows.length === 0 ? <p className="muted">No vaccines logged yet.</p> : null}
          </div>
          <div className="card">
            <h3>Weight</h3>
            <p className="muted">{trend ? `${trend.latestKg} kg (${trend.changeKg >= 0 ? "+" : ""}${trend.changeKg.toFixed(1)} kg, ${trend.changePercent.toFixed(0)}% over ${trend.overDays} days)` : "Log weight regularly to spot changes early."}</p>
          </div>
          <div className="card">
            <h3>Medications</h3>
            {meds.rows.map((m) => <div key={m.id} className="list-item"><span className="grow">{m.name}, {m.dosage}</span><span className="muted">{scheduleLabel(m)}</span></div>)}
            {meds.rows.length === 0 && <p className="muted">None.</p>}
          </div>
          <SymptomChecker pet={pet} recent={symptoms.rows.map((s) => s.symptoms)} weightChange={trend?.changePercent ?? null} weightKg={trend?.latestKg ?? weights.rows.at(-1)?.kilograms ?? null} onSave={symptoms.add} onPlans={onPlans} />
        </>
      )}

      {tab === "Symptoms" && (
        <>
          <SymptomChecker pet={pet} recent={symptoms.rows.map((s) => s.symptoms)} weightChange={trend?.changePercent ?? null} weightKg={trend?.latestKg ?? weights.rows.at(-1)?.kilograms ?? null} onSave={symptoms.add} onPlans={onPlans} />
          <div className="card">
            <h3>History</h3>
            {symptoms.rows.map((s) => (
              <div key={s.id} className="list-item">
                <div className="grow">
                  <div>{s.symptoms.map(symptomLabel).join(", ")}</div>
                  <div className="muted">{new Date(s.logged_at).toLocaleString()} · {s.severity.toLowerCase()}{s.notes ? ` · ${s.notes}` : ""}</div>
                </div>
                {s.urgency && <Pill text={HEADLINES[s.urgency]} color={URGENCY_COLOR[s.urgency]} />}
                <button className="link" onClick={() => symptoms.remove(s.id)}>Delete</button>
              </div>
            ))}
            {symptoms.rows.length === 0 && <p className="muted">Nothing logged.</p>}
          </div>
        </>
      )}

      {tab === "Photos" && <MediaTab pet={pet} onUpgrade={onPlans} />}
      {tab === "Weight" && <WeightTab petId={pet.id} rows={weights.rows} add={weights.add} remove={weights.remove} />}
      {tab === "Vaccines" && <VaccinesTab petId={pet.id} rows={vaccines.rows} add={vaccines.add} remove={vaccines.remove} />}
      {tab === "Meds" && <MedsTab petId={pet.id} rows={meds.rows} add={meds.add} remove={meds.remove} />}
    </>
  );
}

function scheduleLabel(m: Medication) {
  if (m.schedule_type === "AS_NEEDED") return "as needed";
  if (m.schedule_type === "EVERY_N_DAYS") return m.interval_days === 30 ? `monthly at ${m.times[0]}` : `every ${m.interval_days} days at ${m.times[0]}`;
  return m.times.join(", ");
}

// ---------------------------------------------------------------- Symptom checker

const ONSETS = [["Just now", 0], ["A few hours ago", 4], ["About a day", 26], ["2–3 days", 48], ["A week or more", 168]] as const;

function SymptomChecker({ pet, recent, weightChange, weightKg, onSave, onPlans }: {
  pet: Pet; recent: string[][]; weightChange: number | null; weightKg: number | null;
  onSave: (row: Omit<Tables["symptom_entries"], "id">) => Promise<void>; onPlans: () => void;
}) {
  const [photo, setPhoto] = useState<File | null>(null);
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const [plan, setPlan] = useState<Plan>("FREE");
  const [ai, setAi] = useState<AiTriageResult | null>(null);
  const [aiBusy, setAiBusy] = useState(false);
  const [aiMsg, setAiMsg] = useState<{ text: string; upgrade?: boolean } | null>(null);
  const photoInput = useRef<HTMLInputElement>(null);
  useEffect(() => { getCustomer().then((c) => setPlan(c.plan)); }, []);
  useEffect(() => { if (!photo) { setPhotoUrl(null); return; } const u = URL.createObjectURL(photo); setPhotoUrl(u); return () => URL.revokeObjectURL(u); }, [photo]);
  const [sel, setSel] = useState<string[]>([]);
  const [severity, setSeverity] = useState<Severity>("MILD");
  const [onset, setOnset] = useState(0);
  const [notes, setNotes] = useState("");
  const [result, setResult] = useState<TriageResult | null>(null);
  const toggle = (k: string) => { setSel(sel.includes(k) ? sel.filter((x) => x !== k) : [...sel, k]); setResult(null); setAi(null); };
  const hours = ONSETS[onset][1];
  const assess = () => triage({ pet, symptoms: sel, severity, hoursSinceOnset: hours, ageYears: ageYears(pet.birth_date), recent: recent.slice(0, 20), recentWeightChangePercent: weightChange });
  const save = async () => {
    const r = ai ?? result ?? assess();
    const now = Date.now();
    await onSave({ pet_id: pet.id, symptoms: sel, severity, started_at: new Date(now - hours * 3_600_000).toISOString(), logged_at: new Date(now).toISOString(), notes: notes || null, urgency: r.urgency });
    setSel([]); setNotes(""); setResult(null); setAi(null); setPhoto(null); setAiMsg(null);
  };
  const runAi = async () => {
    const rule = assess();
    setResult(rule); setAiMsg(null);
    if (!LIMITS[plan].aiPhotoCheck) { setAiMsg({ text: "AI photo checks are part of Premium. Your rule-based result is above.", upgrade: true }); return; }
    if (!aiAvailable) { setAiMsg({ text: "Sign in to use the AI check. It runs on our secure server, so this demo shows the rule-based result only." }); return; }
    setAiBusy(true);
    try {
      setAi(await aiTriage({ pet, ageYears: ageYears(pet.birth_date), weightKg, symptomLabels: sel.map(symptomLabel), severity, hoursSinceOnset: hours, notes, rule, photo }));
    } catch (e) {
      setAiMsg({ text: (e as Error).message, upgrade: e instanceof AiError && e.code === "UPGRADE_REQUIRED" });
    } finally { setAiBusy(false); }
  };
  const shown = ai ?? result;
  const chips = (red: boolean) => SYMPTOMS.filter((s) => !!s.redFlag === red).map((s) => (
    <button key={s.key} className={"chip" + (sel.includes(s.key) ? " on" : "")} onClick={() => toggle(s.key)}>{s.label}</button>
  ));

  return (
    <div className="card">
      <h3>Something wrong with {pet.name}?</h3>
      <div className="row">{chips(false)}</div>
      <p className="muted" style={{ color: "var(--emergency)", marginBottom: 4 }}>Serious signs</p>
      <div className="row">{chips(true)}</div>
      <div className="grid2">
        <div><label>How bad?</label><select value={severity} onChange={(e) => setSeverity(e.target.value as Severity)}>{["MILD", "MODERATE", "SEVERE"].map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}</select></div>
        <div><label>Started</label><select value={onset} onChange={(e) => setOnset(Number(e.target.value))}>{ONSETS.map(([l], i) => <option key={l} value={i}>{l}</option>)}</select></div>
      </div>
      <label>Notes</label>
      <input value={notes} onChange={(e) => { setNotes(e.target.value); setAi(null); }} />
      <div className="photo-attach">
        {photoUrl && <img src={photoUrl} alt="Attached photo" />}
        <button onClick={() => photoInput.current?.click()}>{photo ? "Change photo" : "📸 Add a photo"}</button>
        {photo && <button className="link" onClick={() => { setPhoto(null); setAi(null); }}>Remove</button>}
        <input ref={photoInput} type="file" accept="image/*" capture="environment" hidden onChange={(e) => { setPhoto(e.target.files?.[0] ?? null); setAi(null); e.target.value = ""; }} />
      </div>
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!sel.length} onClick={() => { setAi(null); setResult(assess()); }}>How urgent is this?</button>
        <button disabled={(!sel.length && !photo) || aiBusy} onClick={runAi}>{aiBusy ? "Checking…" : "✨ Check with AI"}{plan === "FREE" ? " (Premium)" : ""}</button>
        <button disabled={!sel.length} onClick={save}>Save to log</button>
      </div>
      {aiMsg && <div className="banner" style={{ marginTop: 12 }}>{aiMsg.text} {aiMsg.upgrade && <button className="link" onClick={onPlans}>See plans</button>}</div>}
      {shown && (
        <div className={ai ? "ai-box" : ""} style={{ marginTop: 12 }}>
          {ai && <p className="muted">✨ Reviewed by AI{ai.raisedByAi ? ", which raised the urgency" : ""}</p>}
          <h3 style={{ color: URGENCY_COLOR[shown.urgency] }}>{HEADLINES[shown.urgency]}</h3>
          <ul>{shown.reasons.map((r) => <li key={r}>{r}</li>)}</ul>
          {ai && ai.photoFindings.length > 0 && <><strong>From the photo</strong><ul>{ai.photoFindings.map((r) => <li key={r}>{r}</li>)}</ul></>}
          {ai?.photoQuality === "UNCLEAR" && <p className="muted">The photo was hard to read. A closer, well-lit photo may help.</p>}
          {shown.watchFor.length > 0 && <><strong>Watch for</strong><ul>{shown.watchFor.map((r) => <li key={r}>{r}</li>)}</ul></>}
          {ai?.note && <p className="muted">{ai.note}</p>}
          <p className="muted">{DISCLAIMER}</p>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- Weight / vaccines / meds

type TabProps<K extends ChildTable> = { petId: string; rows: Tables[K][]; add: (r: Omit<Tables[K], "id">) => Promise<void>; remove: (id: string) => Promise<void> };

function WeightTab({ petId, rows, add, remove }: TabProps<"weights">) {
  const [kg, setKg] = useState("");
  const [date, setDate] = useState(todayIso());
  const value = parseFloat(kg.replace(",", "."));
  return (
    <div className="card">
      <h3>Weight</h3>
      <WeightChart rows={rows} />
      <div className="row">
        <input style={{ width: 120 }} placeholder="kg" inputMode="decimal" value={kg} onChange={(e) => setKg(e.target.value)} />
        <input style={{ width: 170 }} type="date" value={date} max={todayIso()} onChange={(e) => setDate(e.target.value)} />
        <button className="primary" disabled={!(value > 0)} onClick={async () => { await add({ pet_id: petId, kilograms: value, date }); setKg(""); }}>Add</button>
      </div>
      {[...rows].reverse().map((w) => <div key={w.id} className="list-item"><span className="grow">{w.date}</span><span>{w.kilograms} kg</span><button className="link" onClick={() => remove(w.id)}>Delete</button></div>)}
    </div>
  );
}

function WeightChart({ rows }: { rows: Tables["weights"][] }) {
  if (rows.length < 2) return null;
  const W = 600, H = 140, pad = 10;
  const t = rows.map((r) => new Date(r.date).getTime());
  const kg = rows.map((r) => r.kilograms);
  const [t0, t1] = [Math.min(...t), Math.max(...t)];
  const [k0, k1] = [Math.min(...kg), Math.max(...kg)];
  const x = (v: number) => pad + ((v - t0) / Math.max(1, t1 - t0)) * (W - 2 * pad);
  const y = (v: number) => H - pad - ((v - k0) / Math.max(0.5, k1 - k0)) * (H - 2 * pad);
  const pts = rows.map((r, i) => `${x(t[i])},${y(r.kilograms)}`).join(" ");
  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: "100%", height: "auto", marginBottom: 8 }} role="img" aria-label="Weight over time">
      <polyline points={pts} fill="none" stroke="var(--primary)" strokeWidth={3} strokeLinejoin="round" />
      {rows.map((r, i) => <circle key={r.id} cx={x(t[i])} cy={y(r.kilograms)} r={4} fill="var(--primary)"><title>{`${r.date}: ${r.kilograms} kg`}</title></circle>)}
    </svg>
  );
}

const COMMON_VACCINES: [string, number][] = [["Rabies", 36], ["DHPP", 12], ["Bordetella", 12], ["Leptospirosis", 12], ["FVRCP", 12], ["FeLV", 12]];

function VaccinesTab({ petId, rows, add, remove }: TabProps<"vaccinations">) {
  const [name, setName] = useState("");
  const [date, setDate] = useState(todayIso());
  const [months, setMonths] = useState("12");
  const today = todayIso();
  return (
    <div className="card">
      <h3>Vaccines</h3>
      {latestByName(rows).map((v) => {
        const st = vaccineStatus(v, today);
        return <div key={v.id} className="list-item"><span className="grow">{v.name}<div className="muted">given {v.administered_on}{dueOn(v) ? ` · due ${dueOn(v)}` : ""}</div></span><Pill text={st.replace("_", " ").toLowerCase()} color={VACCINE_COLOR[st]} /><button className="link" onClick={() => remove(v.id)}>Delete</button></div>;
      })}
      <div className="row" style={{ marginTop: 12 }}>{COMMON_VACCINES.map(([n, m]) => <button key={n} className="chip" onClick={() => { setName(n); setMonths(String(m)); }}>{n}</button>)}</div>
      <div className="grid2">
        <div><label>Vaccine</label><input value={name} onChange={(e) => setName(e.target.value)} /></div>
        <div><label>Date given</label><input type="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} /></div>
        <div><label>Booster every (months, blank = none)</label><input inputMode="numeric" value={months} onChange={(e) => setMonths(e.target.value.replace(/\D/g, ""))} /></div>
      </div>
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!name.trim()} onClick={async () => { await add({ pet_id: petId, name: name.trim(), administered_on: date, valid_for_months: months ? Number(months) : null }); setName(""); }}>Add vaccine</button>
      </div>
    </div>
  );
}

function MedsTab({ petId, rows, add, remove }: TabProps<"medications">) {
  const [name, setName] = useState("");
  const [dosage, setDosage] = useState("");
  const [type, setType] = useState<Medication["schedule_type"]>("DAILY");
  const [times, setTimes] = useState("08:00");
  const [interval, setInterval] = useState("30");
  return (
    <div className="card">
      <h3>Medications</h3>
      <p className="muted">Dose reminders run on the Android app. Medications added here sync to it.</p>
      {rows.map((m) => <div key={m.id} className="list-item"><span className="grow">{m.name}, {m.dosage}<div className="muted">{scheduleLabel(m)}</div></span><button className="link" onClick={() => remove(m.id)}>Delete</button></div>)}
      <div className="grid2">
        <div><label>Medication</label><input value={name} onChange={(e) => setName(e.target.value)} /></div>
        <div><label>Dose</label><input value={dosage} onChange={(e) => setDosage(e.target.value)} /></div>
        <div><label>Schedule</label><select value={type} onChange={(e) => setType(e.target.value as Medication["schedule_type"])}><option value="DAILY">daily</option><option value="EVERY_N_DAYS">every N days</option><option value="AS_NEEDED">as needed</option></select></div>
        {type !== "AS_NEEDED" && <div><label>{type === "DAILY" ? "Times (comma separated)" : "Time"}</label><input value={times} onChange={(e) => setTimes(e.target.value)} /></div>}
        {type === "EVERY_N_DAYS" && <div><label>Every how many days?</label><input inputMode="numeric" value={interval} onChange={(e) => setInterval(e.target.value.replace(/\D/g, ""))} /></div>}
      </div>
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!name.trim()} onClick={async () => {
          const parsed = times.split(",").map((s) => s.trim()).filter((s) => /^\d{1,2}:\d{2}$/.test(s)).map((s) => s.padStart(5, "0"));
          await add({
            pet_id: petId, name: name.trim(), dosage, schedule_type: type,
            times: type === "AS_NEEDED" ? [] : type === "DAILY" ? parsed : parsed.slice(0, 1),
            interval_days: type === "EVERY_N_DAYS" ? Number(interval) || 30 : 1,
            start_date: todayIso(), end_date: null, units_remaining: null, units_per_dose: 1, reminders_enabled: true,
          });
          setName(""); setDosage("");
        }}>Add medication</button>
      </div>
    </div>
  );
}
