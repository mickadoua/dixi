import { writeFileSync } from 'node:fs';
import { chromium } from 'playwright/playwright/index.mjs';
import { A, B, Cc, svg } from './icons.mjs';
const font = new URL('../../app/src/main/res/font/space_grotesk_bold.ttf', import.meta.url).href;
const tr = [['A · Lettre D dans la bulle', A], ['B · Deux « i » dans la voix', B], ['C · Bulle de dialogue', Cc]];
const html = `<style>@font-face{font-family:SpaceGrotesk;src:url(${font});font-weight:700}body{margin:0;background:#101015;color:#F5F5F7;font:16px Inter,sans-serif;display:flex;gap:32px;padding:32px}figure{margin:0;text-align:center}figure div svg{border-radius:22%}.s{display:flex;gap:12px;align-items:end;justify-content:center;margin-top:12px}</style>
${tr.map(([n, f]) => `<figure><div>${svg(f(), 320)}</div><div class=s>${svg(f(false), 96)}${svg(f(false), 48)}</div><p>${n}</p></figure>`).join('')}`;
const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1200, height: 520 } });
writeFileSync('/tmp/sheet.html', html); await p.goto('file:///tmp/sheet.html', { waitUntil: 'networkidle' }); await p.evaluate(() => document.fonts.load('700 100px SpaceGrotesk')); await p.waitForTimeout(500);
await p.screenshot({ path: process.argv[2] }); await b.close();
