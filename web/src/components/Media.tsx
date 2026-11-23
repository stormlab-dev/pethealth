import { useEffect, useRef, useState } from "react";
import { media, shareMedia, videoDuration, type MediaItem } from "../lib/media";
import { canAddMedia, getCustomer, LIMITS, type Customer } from "../lib/plans";
import type { Pet } from "../lib/types";

function Thumb({ item, onOpen }: { item: MediaItem; onOpen: () => void }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => { media.url(item).then(setUrl).catch(() => setUrl(null)); }, [item.id]);
  return (
    <button className="thumb" onClick={onOpen} aria-label={item.caption ?? (item.kind === "VIDEO" ? "Video" : "Photo")}>
      {url && (item.kind === "VIDEO" ? <video src={url} muted preload="metadata" /> : <img src={url} alt="" loading="lazy" />)}
      {item.kind === "VIDEO" && <span className="badge">▶ {item.duration_sec ? `${Math.round(item.duration_sec)}s` : ""}</span>}
    </button>
  );
}

export function MediaTab({ pet, onUpgrade }: { pet: Pet; onUpgrade: () => void }) {
  const [items, setItems] = useState<MediaItem[]>([]);
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [total, setTotal] = useState(0);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ text: string; upgrade?: boolean } | null>(null);
  const [open, setOpen] = useState<MediaItem | null>(null);
  const [openUrl, setOpenUrl] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const reload = async () => {
    setItems(await media.list(pet.id));
    setTotal(await media.count());
  };
  useEffect(() => { reload().catch((e) => setMsg({ text: String(e.message ?? e) })); getCustomer().then(setCustomer); }, [pet.id]);
  useEffect(() => { if (open) media.url(open).then(setOpenUrl); else setOpenUrl(null); }, [open]);

  const upload = async (files: FileList | null) => {
    if (!files?.length || !customer) return;
    setBusy(true); setMsg(null);
    let count = total;
    try {
      for (const f of Array.from(files)) {
        if (!f.type.startsWith("image/") && !f.type.startsWith("video/")) continue;
        const isVideo = f.type.startsWith("video/");
        const secs = isVideo ? await videoDuration(f) : 0;
        const gate = canAddMedia(customer.plan, count, isVideo, secs);
        if (!gate.ok) { setMsg({ text: gate.reason, upgrade: gate.suggested !== customer.plan }); break; }
        await media.add(pet.id, f, null, isVideo ? Math.round(secs) : null);
        count++;
      }
    } catch (e) {
      setMsg({ text: String((e as Error).message ?? e) });
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
      await reload();
    }
  };

  const share = async (item: MediaItem) => {
    const how = await shareMedia(item, await media.blob(item), pet.name);
    if (how === "downloaded") setMsg({ text: "Saved to your downloads. Your browser doesn't support direct sharing." });
  };
  const remove = async (item: MediaItem) => { await media.remove(item); setOpen(null); await reload(); };

  const limit = customer ? LIMITS[customer.plan].maxMedia : null;
  return (
    <>
      <div className="card">
        <div className="row">
          <h3 className="grow">Photos and videos</h3>
          <button className="primary" disabled={busy || !customer} onClick={() => input.current?.click()}>{busy ? "Uploading…" : "Add photos or videos"}</button>
          <input ref={input} type="file" accept="image/*,video/*" multiple hidden onChange={(e) => upload(e.target.files)} />
        </div>
        <p className="muted">
          {limit != null ? `${total} of ${limit} used on the free plan · clips up to ${LIMITS.FREE.maxClipSeconds}s. ` : "Unlimited on your plan · clips up to 3 minutes. "}
          Share any photo with family or your vet.
        </p>
        {msg && <div className="banner">{msg.text} {msg.upgrade && <button className="link" onClick={onUpgrade}>See plans</button>}</div>}
        {items.length === 0 ? <p className="muted">No photos yet. Add a few of {pet.name}, or snap a health photo to show your vet.</p> : (
          <div className="gallery">{items.map((m) => <Thumb key={m.id} item={m} onOpen={() => setOpen(m)} />)}</div>
        )}
      </div>
      {open && (
        <div className="lightbox" role="dialog" aria-modal="true" onClick={() => setOpen(null)}>
          <div className="lightbox-inner" onClick={(e) => e.stopPropagation()}>
            {openUrl && (open.kind === "VIDEO" ? <video src={openUrl} controls autoPlay playsInline /> : <img src={openUrl} alt={open.caption ?? ""} />)}
            <div className="row" style={{ marginTop: 8 }}>
              <span className="muted grow">{new Date(open.taken_at).toLocaleDateString()}</span>
              <button className="primary" onClick={() => share(open)}>Share</button>
              <button onClick={() => remove(open)}>Delete</button>
              <button onClick={() => setOpen(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
