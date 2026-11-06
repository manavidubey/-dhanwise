import { createServer } from "vite";
import react from "@vitejs/plugin-react";

const server = await createServer({
  configFile: false,
  root: process.cwd(),
  plugins: [react()],
  server: {
    port: 5173,
    // The API lives in the single Spring Boot process on 8080.
    proxy: {
      "/api": "http://127.0.0.1:8080",
    },
  },
});

await server.listen();
server.printUrls();
