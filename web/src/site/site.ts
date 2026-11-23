import "./site.css";
import { createClient } from "@supabase/supabase-js";

// Mobile menu
const btn = document.querySelector<HTMLButtonElement>(".menu-btn");
const nav = document.querySelector<HTMLElement>(".nav");
btn?.addEventListener("click", () => {
  const open = nav?.classList.toggle("open");
  btn.setAttribute("aria-expanded", String(!!open));
});

document.querySelectorAll<HTMLElement>("[data-year]").forEach((el) => (el.textContent = String(new Date().getFullYear())));

// Contact form: saves to Supabase when configured, otherwise opens the visitor's email app.
export const CONTACT_EMAIL = "hello@cutefamilypet.com";
const form = document.querySelector<HTMLFormElement>("#contact-form");
if (form) {
  const topic = new URLSearchParams(location.search).get("topic");
  const select = form.querySelector<HTMLSelectElement>("#topic");
  if (topic && select && [...select.options].some((o) => o.value === topic)) select.value = topic;
  const status = form.querySelector<HTMLElement>(".form-status")!;
  const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
  const key = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;
  const supabase = url && key ? createClient(url, key) : null;

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(form)) as Record<string, string>;
    if (data.website) return; // honeypot field: bots fill it, people don't see it
    const submit = form.querySelector<HTMLButtonElement>("button[type=submit]")!;
    submit.disabled = true;
    try {
      if (supabase) {
        const { error } = await supabase.from("contact_messages").insert({
          name: data.name, email: data.email, topic: data.topic, message: data.message,
        });
        if (error) throw error;
        status.textContent = "Thanks! We got your message and will reply within two business days.";
        form.reset();
      } else {
        const body = `${data.message}\n\n— ${data.name} (${data.email})`;
        window.location.href = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(`[${data.topic}] Message from ${data.name}`)}&body=${encodeURIComponent(body)}`;
        status.textContent = `Your email app should open now. If it doesn't, write to ${CONTACT_EMAIL}.`;
      }
    } catch {
      status.textContent = `Sorry, that didn't send. Please email ${CONTACT_EMAIL}.`;
    } finally {
      submit.disabled = false;
    }
  });
}
