import { useEffect, useState } from "react";
import type { Session } from "@supabase/supabase-js";
import { store, supabase } from "./lib/store";
import type { Pet } from "./lib/types";
import { PetDetail, PetForm, speciesEmoji } from "./components/Pets";
import { ageYears } from "./lib/health";
import { PlansScreen } from "./components/Plans";

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [ready, setReady] = useState(!supabase);

  useEffect(() => {
    if (!supabase) return;
    supabase.auth.getSession().then(({ data }) => { setSession(data.session); setReady(true); });
    const { data } = supabase.auth.onAuthStateChange((_e, s) => setSession(s));
    return () => data.subscription.unsubscribe();
  }, []);

  if (!ready) return null;
  if (supabase && !session) return <SignIn />;
  return <Home />;
}

function SignIn() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const send = async () => {
    const { error } = await supabase!.auth.signInWithOtp({ email, options: { emailRedirectTo: window.location.origin } });
    if (error) setError(error.message); else setSent(true);
  };
  return (
    <main>
      <header className="top"><h1>🐾 CuteFamilyPet</h1></header>
      <div className="card">
        <h3>Sign in</h3>
        {sent ? <p>Check your email for a sign-in link.</p> : (
          <>
            <p className="muted">Use the same account as the Android app to see your pets everywhere.</p>
            <label htmlFor="email">Email</label>
            <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            <div className="row" style={{ marginTop: 12 }}>
              <button className="primary" disabled={!email.includes("@")} onClick={send}>Email me a link</button>
            </div>
            {error && <p style={{ color: "var(--emergency)" }}>{error}</p>}
          </>
        )}
      </div>
    </main>
  );
}

function Home() {
  const [pets, setPets] = useState<Pet[] | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [editing, setEditing] = useState<Pet | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [plans, setPlans] = useState(window.location.hash === "#plans");
  useEffect(() => {
    const onHash = () => setPlans(window.location.hash === "#plans");
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const showPlans = (on: boolean) => { window.location.hash = on ? "plans" : ""; setPlans(on); };

  const reload = () => store.listPets().then(setPets).catch((e) => setError(String(e.message ?? e)));
  useEffect(() => { reload(); }, []);

  const open = pets?.find((p) => p.id === openId);

  if (plans) return <main><PlansScreen onBack={() => showPlans(false)} /></main>;

  if (editing) {
    return (
      <main>
        <PetForm
          initial={editing === "new" ? undefined : editing}
          onCancel={() => setEditing(null)}
          onSaved={(p) => { setEditing(null); setOpenId(p.id); reload(); }}
          onDeleted={() => { setEditing(null); setOpenId(null); reload(); }}
        />
      </main>
    );
  }

  if (open) return <main><PetDetail pet={open} onBack={() => setOpenId(null)} onEdit={() => setEditing(open)} onPlans={() => showPlans(true)} /></main>;

  return (
    <main>
      <header className="top">
        <h1><a href="../" className="home-link" title="CuteFamilyPet website">🐾</a> My pets</h1>
        {supabase && <button className="link" onClick={() => supabase!.auth.signOut()}>Sign out</button>}
        <button onClick={() => showPlans(true)}>⭐ Plans</button>
        <button className="primary" onClick={() => setEditing("new")}>Add pet</button>
      </header>
      {store.mode === "demo" && (
        <div className="banner">Demo mode: data is saved in this browser only. Set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY to sync with the Android app.</div>
      )}
      {error && <div className="card" style={{ color: "var(--emergency)" }}>{error}</div>}
      {pets?.length === 0 && <div className="card muted">Add your first pet to start tracking symptoms, weight, vaccines and medications.</div>}
      {pets?.map((p) => {
        const age = ageYears(p.birth_date);
        return (
          <div key={p.id} className="card pet" onClick={() => setOpenId(p.id)}>
            <span className="emoji">{speciesEmoji(p.species)}</span>
            <div>
              <strong>{p.name}</strong>
              <div className="muted">{[p.breed || p.species.toLowerCase(), age != null ? `${age.toFixed(1)} yrs` : null].filter(Boolean).join(" · ")}</div>
            </div>
          </div>
        );
      })}
    </main>
  );
}
