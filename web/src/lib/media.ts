// Pet photos and videos. Cloud mode: Supabase Storage (private bucket "pet-media") plus the
// pet_media table. Demo mode: this browser's IndexedDB.
import { supabase } from "./store";

export interface MediaItem {
  id: string; pet_id: string; kind: "PHOTO" | "VIDEO"; mime_type: string; caption: string | null;
  taken_at: string; duration_sec: number | null; storage_path: string;
}

export interface MediaStore {
  list(petId: string): Promise<MediaItem[]>;
  count(): Promise<number>;
  add(petId: string, file: File, caption: string | null, durationSec: number | null): Promise<MediaItem>;
  url(item: MediaItem): Promise<string>;
  blob(item: MediaItem): Promise<Blob>;
  remove(item: MediaItem): Promise<void>;
}

const kindOf = (f: File) => (f.type.startsWith("video/") ? "VIDEO" : "PHOTO") as MediaItem["kind"];
const ext = (f: File) => (f.name.split(".").pop() || f.type.split("/")[1] || "bin").toLowerCase().replace(/[^a-z0-9]/g, "");

class CloudMedia implements MediaStore {
  async list(petId: string) {
    const { data, error } = await supabase!.from("pet_media").select("*").eq("pet_id", petId).is("deleted_at", null).order("taken_at", { ascending: false });
    if (error) throw new Error(error.message);
    return data as MediaItem[];
  }
  async count() {
    const { count } = await supabase!.from("pet_media").select("id", { count: "exact", head: true }).is("deleted_at", null);
    return count ?? 0;
  }
  async add(petId: string, file: File, caption: string | null, durationSec: number | null) {
    const path = `${petId}/${crypto.randomUUID()}.${ext(file)}`;
    const up = await supabase!.storage.from("pet-media").upload(path, file, { contentType: file.type });
    if (up.error) throw new Error(up.error.message);
    const { data, error } = await supabase!.from("pet_media").insert({
      pet_id: petId, kind: kindOf(file), storage_path: path, mime_type: file.type, caption, duration_sec: durationSec,
      taken_at: new Date(file.lastModified || Date.now()).toISOString(),
    }).select().single();
    if (error) {
      await supabase!.storage.from("pet-media").remove([path]);
      throw new Error(error.message.replace(/^(MEDIA_LIMIT|CLIP_LIMIT|MEMBER_LIMIT): /, ""));
    }
    return data as MediaItem;
  }
  private cache = new Map<string, string>();
  async url(item: MediaItem) {
    const hit = this.cache.get(item.id);
    if (hit) return hit;
    const { data, error } = await supabase!.storage.from("pet-media").createSignedUrl(item.storage_path, 3600);
    if (error) throw new Error(error.message);
    this.cache.set(item.id, data.signedUrl);
    return data.signedUrl;
  }
  async blob(item: MediaItem) {
    const { data, error } = await supabase!.storage.from("pet-media").download(item.storage_path);
    if (error) throw new Error(error.message);
    return data;
  }
  async remove(item: MediaItem) {
    await supabase!.from("pet_media").update({ deleted_at: new Date().toISOString() }).eq("id", item.id);
    await supabase!.storage.from("pet-media").remove([item.storage_path]);
  }
}

class LocalMedia implements MediaStore {
  private db: Promise<IDBDatabase> | null = null;
  private open() {
    this.db ??= new Promise((resolve, reject) => {
      const r = indexedDB.open("cutefamilypet-media", 1);
      r.onupgradeneeded = () => {
        const s = r.result.createObjectStore("media", { keyPath: "id" });
        s.createIndex("pet_id", "pet_id");
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
    return this.db;
  }
  private async tx<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const req = fn(db.transaction("media", mode).objectStore("media"));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  async list(petId: string) {
    const rows = await this.tx<(MediaItem & { blob: Blob })[]>("readonly", (s) => s.index("pet_id").getAll(petId));
    return rows.map(({ blob: _b, ...m }) => m).sort((a, b) => b.taken_at.localeCompare(a.taken_at));
  }
  count() { return this.tx<number>("readonly", (s) => s.count()); }
  async add(petId: string, file: File, caption: string | null, durationSec: number | null) {
    const item: MediaItem = { id: crypto.randomUUID(), pet_id: petId, kind: kindOf(file), mime_type: file.type, caption, duration_sec: durationSec,
      taken_at: new Date(file.lastModified || Date.now()).toISOString(), storage_path: file.name };
    await this.tx("readwrite", (s) => s.put({ ...item, blob: file }));
    return item;
  }
  private urls = new Map<string, string>();
  async url(item: MediaItem) {
    const hit = this.urls.get(item.id);
    if (hit) return hit;
    const u = URL.createObjectURL(await this.blob(item));
    this.urls.set(item.id, u);
    return u;
  }
  async blob(item: MediaItem) {
    const row = await this.tx<{ blob: Blob }>("readonly", (s) => s.get(item.id));
    return row.blob;
  }
  async remove(item: MediaItem) { await this.tx("readwrite", (s) => s.delete(item.id)); }
}

export const media: MediaStore = supabase ? new CloudMedia() : new LocalMedia();

/** Reads a video's length in seconds without uploading it. */
export function videoDuration(file: File): Promise<number> {
  return new Promise((resolve) => {
    const v = document.createElement("video");
    v.preload = "metadata";
    v.onloadedmetadata = () => { URL.revokeObjectURL(v.src); resolve(Number.isFinite(v.duration) ? v.duration : 0); };
    v.onerror = () => resolve(0);
    v.src = URL.createObjectURL(file);
  });
}

/** Share with the system share sheet where supported (phones, Safari, Edge), else download. */
export async function shareMedia(item: MediaItem, blob: Blob, petName: string): Promise<"shared" | "downloaded"> {
  const name = `${petName}-${item.taken_at.slice(0, 10)}.${item.mime_type.split("/")[1] ?? "bin"}`;
  const file = new File([blob], name, { type: item.mime_type });
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: petName, text: item.caption ?? `${petName} on CuteFamilyPet` });
      return "shared";
    } catch (e) {
      if ((e as DOMException).name === "AbortError") return "shared";
    }
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(file);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
  return "downloaded";
}
