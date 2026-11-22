import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// VITE_BASE lets the same build run at a sub-path, e.g. GitHub Pages serves it at /pethealth/.
export default defineConfig({
  base: process.env.VITE_BASE ?? "/",
  plugins: [react()],
});
