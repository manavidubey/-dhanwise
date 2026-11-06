import { build } from "vite";
import react from "@vitejs/plugin-react";

// Inline config on purpose: Vite's config-file loader stalls on some machines, and this
// project's config is three settings. Keeping it here also makes the build reproducible
// without a second config dialect.
await build({
  configFile: false,
  root: process.cwd(),
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  logLevel: "info",
});
