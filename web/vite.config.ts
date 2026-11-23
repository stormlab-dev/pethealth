import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

// The site is several static pages plus the React app at /app/.
// VITE_BASE lets the same build run at a sub-path, e.g. GitHub Pages serves it at /pethealth/
// until the custom domain is connected.
export default defineConfig({
  base: process.env.VITE_BASE ?? "/",
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        home: resolve(__dirname, "index.html"),
        about: resolve(__dirname, "about.html"),
        contact: resolve(__dirname, "contact.html"),
        pricing: resolve(__dirname, "pricing.html"),
        privacy: resolve(__dirname, "privacy.html"),
        terms: resolve(__dirname, "terms.html"),
        app: resolve(__dirname, "app/index.html"),
      },
    },
  },
});
