// Renders Dixi store graphics + logo from icons.mjs. Usage: node build.mjs <repo-root>
import { chromium } from 'playwright/playwright/index.mjs';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { A, C, svg } from './icons.mjs';
const root = process.argv[2];
const fontB64 = (f) => readFileSync(`${root}/app/src/main/res/font/${f}`).toString('base64');
const css = `@font-face{font-family:SG;font-weight:700;src:url(data:font/ttf;base64,${fontB64('space_grotesk_bold.ttf')})}
@font-face{font-family:IN;font-weight:600;src:url(data:font/ttf;base64,${fontB64('inter_semibold.ttf')})}
*{margin:0;box-sizing:border-box}body{background:${C.bg}}`;
const b = await chromium.launch();
async function shot(html, w, h, out, transparent = false) {
  const p = await b.newPage({ viewport: { width: w, height: h } });
  await p.setContent(`<style>${css}${transparent ? 'body{background:transparent}' : ''}</style>${html}`);
  await p.evaluate(() => Promise.all([document.fonts.load('700 100px SG'), document.fonts.load('600 40px IN')]));
  mkdirSync(out.replace(/\/[^/]+$/, ''), { recursive: true });
  await p.screenshot({ path: out, omitBackground: transparent }); await p.close();
}
const tagline = { 'en-US': 'Dictate in any app.', 'fr-FR': 'Dictez dans toutes vos apps.' };
for (const loc of ['en-US', 'fr-FR']) {
  const g = `${root}/app/src/main/play/listings/${loc}/graphics`;
  await shot(svg(A(), 512), 512, 512, `${g}/icon/icon.png`);
  await shot(`<div style="width:1024px;height:500px;position:relative;background:radial-gradient(circle at 20% 50%,#1a0d03 0,${C.bg} 60%)">
<div style="position:absolute;left:-10px;top:-6px">${svg(A().replace(/<circle cx="344"[^>]*>/, '<circle cx="344" cy="168" r="17" fill="#2BE4FF"/>'), 512, 'transparent')}</div>
<div style="position:absolute;left:470px;top:150px;font:600 124px/1 IN;color:${C.text};letter-spacing:-2px">Dixi</div>
<div style="position:absolute;left:474px;top:284px;width:120px;height:5px;border-radius:3px;background:${C.cyan};box-shadow:0 0 12px ${C.cyan}"></div>
<div style="position:absolute;left:472px;top:318px;font:600 32px/1 IN;color:#FF8F3D">${tagline[loc]}</div></div>`, 1024, 500, `${g}/feature-graphic/feature-graphic.png`);
}
// logo: wordmark and lockup, transparent PNG + dark preview
await shot(`<div style="display:inline-flex;align-items:center;gap:28px;padding:24px 32px"><div style="width:120px;height:120px">${svg(A(false), 120, 'transparent')}</div><span style="font:600 112px/1 IN;color:${C.text};letter-spacing:-2px">Dixi</span></div>`, 560, 170, `${root}/design/dixi/logo/dixi-lockup.png`, true);
await shot(`<div style="padding:24px 32px;font:600 112px/1 IN;color:${C.text};letter-spacing:-2px;display:inline-block">Dixi<div style="margin-top:10px;width:90px;height:5px;border-radius:3px;background:${C.cyan}"></div></div>`, 340, 180, `${root}/design/dixi/logo/dixi-wordmark.png`, true);
await b.close();
