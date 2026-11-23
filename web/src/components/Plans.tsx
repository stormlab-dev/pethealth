import { useEffect, useState } from "react";
import { demoPurchase, demoReset, EXTRAS, getCustomer, PRINTS, SUBSCRIPTIONS, type Customer, type Product } from "../lib/plans";

const ANDROID_APK = "https://github.com/stormlab-dev/pethealth/releases/latest/download/cutefamilypet-debug.apk";

export function PlansScreen({ onBack }: { onBack: () => void }) {
  const [c, setC] = useState<Customer | null>(null);
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => { getCustomer().then(setC); }, []);

  const buy = (p: Product) => {
    if (!c) return;
    if (p.kind === "PRINT") { setNote("Print orders open soon. We'll email you when photo books and calendars are ready to order."); return; }
    if (!c.demo) { setNote("Subscriptions and extras are bought in the Android or iPhone app, through Google Play or the App Store. They unlock here automatically."); return; }
    setC({ ...demoPurchase(p) });
    setNote(p.kind === "SUB" ? `Demo: you're now on ${p.plan === "FAMILY" ? "Family" : "Premium"}. Nothing was charged.` : `Demo: ${p.title.replace(/^\S+ /, "")} added. Nothing was charged.`);
  };

  const planName = c ? { FREE: "Free", PREMIUM: "Premium", FAMILY: "Family" }[c.plan] : "…";
  return (
    <>
      <header className="top">
        <button className="link" onClick={onBack}>← Pets</button>
        <h1>Plans and extras</h1>
      </header>
      <div className="card">
        <h3>Your plan: {planName}{c?.inTrial ? " (free trial)" : ""}</h3>
        {c?.expiresAt && <p className="muted">{c.inTrial ? "Trial ends" : "Renews or ends"} {new Date(c.expiresAt).toLocaleDateString()}.</p>}
        {c?.demo && <p className="muted">Demo mode: buttons below simulate purchases so you can try every feature. <button className="link" onClick={() => { demoReset(); getCustomer().then(setC); setNote(null); }}>Reset to Free</button></p>}
        {note && <div className="banner">{note}</div>}
      </div>
      <div className="plan-grid">
        {[["PREMIUM", "Premium", ["Unlimited photos and videos, clips up to 3 minutes", "AI symptom checker with photo analysis", "Vet-ready health reports", "No ads"]],
          ["FAMILY", "Family", ["Everything in Premium", "Share with up to 6 people", "Unlimited pets"]]].map(([plan, name, perks]) => (
          <div key={plan as string} className={"card" + (c?.plan === plan ? " current" : "")}>
            <h3>{name as string}</h3>
            <ul>{(perks as string[]).map((x) => <li key={x}>{x}</li>)}</ul>
            {SUBSCRIPTIONS.filter((s) => s.plan === plan).map((s) => (
              <div key={s.id} className="list-item">
                <span className="grow">{s.description}</span>
                <button className={s.trialDays ? "primary" : ""} disabled={c?.plan === plan} onClick={() => buy(s)}>{s.trialDays ? "Start free trial" : "Choose"}</button>
              </div>
            ))}
          </div>
        ))}
      </div>
      <div className="card">
        <h3>Extras, bought once</h3>
        {EXTRAS.map((p) => (
          <div key={p.id} className="list-item">
            <div className="grow"><div>{p.title}</div><div className="muted">{p.description}</div></div>
            {c?.owned.includes(p.id) && !p.id.startsWith("cfp_reel") && p.id !== "cfp_contest_boost" ? <span className="muted">Owned</span> : <button onClick={() => buy(p)}>{p.price}</button>}
          </div>
        ))}
      </div>
      <div className="card">
        <h3>Prints, shipped to your door</h3>
        {PRINTS.map((p) => (
          <div key={p.id} className="list-item">
            <div className="grow"><div>{p.title}</div><div className="muted">{p.description}</div></div>
            <button onClick={() => buy(p)}>{p.price}</button>
          </div>
        ))}
      </div>
      <p className="muted">Subscriptions renew automatically through Google Play or the App Store until cancelled. Get the app: <a href={ANDROID_APK}>Android</a> · iPhone coming soon.</p>
    </>
  );
}
