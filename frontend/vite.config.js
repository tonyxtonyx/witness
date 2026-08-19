/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins: [react()], server: { proxy: { '/api': { target: 'http://backend:8080', headers: { 'X-API-Key': process.env.REST_API_KEY || 'dev-secret' } } } }, test: { environment: 'jsdom', setupFiles: ['./src/test-setup.ts'] } });
