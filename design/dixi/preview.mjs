import { chromium } from 'playwright/playwright/index.mjs';
import { writeFileSync } from 'node:fs';
import { A, B, Cc, svg } from './icons.mjs';
const root = process.argv[2], out = process.argv[3];
// adaptive icon at 108 dp scale: disc 62 dp on #050507, shown through masks
const ad = (r) => `<svg viewBox="0 0 108 108" width="132" height="132" style="border-radius:${r}"><rect width="108" height="108" fill="#050507"/><g transform="translate(4.34 4.34) scale(.1938)"><circle cx="256" cy="256" r="160" fill="#FF7A1A"/><path d="M216 192H252A64 64 0 0 1 252 320H216Z" fill="none" stroke="#0A0A0C" stroke-width="38" stroke-linejoin="round"/><circle cx="344" cy="168" r="17" fill="#2BE4FF"/></g></svg>`;
const mono = `<svg viewBox="0 0 108 108" width="132" height="132" style="border-radius:50%;background:#cfd8ff"><g transform="translate(4.34 4.34) scale(.1938)" fill="none" stroke="#1b1f3a"><circle cx="256" cy="256" r="147" stroke-width="26"/><path d="M216 192H252A64 64 0 0 1 252 320H216Z" stroke-width="38" stroke-linejoin="round"/><circle cx="344" cy="168" r="17" fill="#1b1f3a" stroke="none"/></g></svg>`;
const html = `<style>body{margin:0;background:#15151c;color:#F5F5F7;font:16px sans-serif;padding:28px;width:1384px}h2{margin:0 0 14px;font-size:20px}.r{display:flex;gap:28px;align-items:center;margin-bottom:34px}figure{margin:0;text-align:center}figcaption{margin-top:8px;color:#A9A9B8;font-size:14px}</style>
<h2>Pistes d'icône</h2><div class=r>${[['A · Lettre D + point cyan (retenue)', A], ['B · Deux « i » dans la voix', B], ['C · Bulle de dialogue', Cc]].map(([n, f]) => `<figure><div style="border-radius:22%;overflow:hidden;width:300px">${svg(f(), 300)}</div><figcaption>${n}</figcaption></figure>`).join('')}</div>
<h2>Icône adaptive (piste A) : rond, carré arrondi, goutte, thème monochrome — et en 48 px</h2><div class=r>${ad('50%')}${ad('28%')}${ad('50% 50% 12% 50%')}${mono}<div style="display:flex;gap:16px;align-items:end">${svg(A(false), 96).replace('<svg ', '<svg style="border-radius:22%" ')}${svg(A(false), 48).replace('<svg ', '<svg style="border-radius:22%" ')}${svg(A(false), 32).replace('<svg ', '<svg style="border-radius:22%" ')}</div></div>
<h2>Logo</h2><div class=r style="background:#050507;padding:20px"><img src="file://${root}/design/dixi/logo/dixi-lockup.png" height=140><img src="file://${root}/design/dixi/logo/dixi-wordmark.png" height=140></div>`;
writeFileSync('/tmp/prev.html', html);
const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1440, height: 900 } });
await p.goto('file:///tmp/prev.html', { waitUntil: 'load' }); await p.screenshot({ path: out, fullPage: true }); await b.close();
