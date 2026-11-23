// AdSense slot for free-plan users of the web app. Off unless VITE_ADSENSE_CLIENT and
// VITE_ADSENSE_SLOT are set at build time. Never shown on symptom or health screens, on the
// Plans screen, or on the marketing site. Paid plans never see it.
import { useEffect, useRef, useState } from "react";
import { getCustomer } from "../lib/plans";

const CLIENT = import.meta.env.VITE_ADSENSE_CLIENT as string | undefined; // ca-pub-XXXXXXXXXXXXXXXX
const SLOT = import.meta.env.VITE_ADSENSE_SLOT as string | undefined;

declare global { interface Window { adsbygoogle?: unknown[] } }

function loadScript() {
  if (document.getElementById("adsbygoogle-js")) return;
  const s = document.createElement("script");
  s.id = "adsbygoogle-js";
  s.async = true;
  s.crossOrigin = "anonymous";
  s.src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${CLIENT}`;
  document.head.appendChild(s);
}

export function AdSlot() {
  const [free, setFree] = useState(false);
  const ref = useRef<HTMLModElement>(null);
  useEffect(() => {
    if (!CLIENT || !SLOT) return;
    getCustomer().then((c) => setFree(c.plan === "FREE")).catch(() => setFree(false));
  }, []);
  useEffect(() => {
    if (!free || !ref.current || ref.current.dataset.adsbygoogleStatus) return;
    loadScript();
    try { (window.adsbygoogle = window.adsbygoogle || []).push({}); } catch { /* blocked by an ad blocker */ }
  }, [free]);
  if (!CLIENT || !SLOT || !free) return null;
  return (
    <div className="ad-slot" aria-label="Advertisement">
      <small className="muted">Ad · <a href="#plans">Remove ads with Premium</a></small>
      <ins ref={ref} className="adsbygoogle" style={{ display: "block" }}
        data-ad-client={CLIENT} data-ad-slot={SLOT} data-ad-format="horizontal" data-full-width-responsive="true" />
    </div>
  );
}
