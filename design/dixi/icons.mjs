// Dixi icon tracks (512 viewBox). Charter colours from DMC-213.
export const C = { bg: '#050507', orange: '#FF7A1A', dark: '#0A0A0C', cyan: '#2BE4FF', text: '#F5F5F7' };
const halo = (id = 'g') => `<defs><radialGradient id="${id}" cx="50%" cy="50%" r="50%"><stop offset="55%" stop-color="${C.orange}" stop-opacity=".35"/><stop offset="100%" stop-color="${C.orange}" stop-opacity="0"/></radialGradient></defs>
<circle cx="256" cy="256" r="250" fill="url(#${id})"/>
<circle cx="256" cy="256" r="226" fill="none" stroke="${C.orange}" stroke-opacity=".28" stroke-width="3"/>
<circle cx="256" cy="256" r="194" fill="none" stroke="${C.orange}" stroke-opacity=".55" stroke-width="3"/>`;
const bar = (x, h, fill = C.dark, w = 34) => `<rect x="${x - w / 2}" y="${256 - h / 2}" width="${w}" height="${h}" rx="${w / 2}" fill="${fill}"/>`;
// A: letter D in the bubble
export const A = (halos = true) => `${halos ? halo('ga') : ''}<circle cx="256" cy="256" r="160" fill="${C.orange}"/>
<path d="M216 192H252A64 64 0 0 1 252 320H216Z" fill="none" stroke="${C.dark}" stroke-width="38" stroke-linejoin="round"/>
<circle cx="344" cy="168" r="17" fill="${C.cyan}"/>`;
// B: waveform whose two inner stems are the two i of Dixi (cyan dots)
const stem = (x) => `<rect x="${x - 15}" y="244" width="30" height="86" rx="15" fill="${C.dark}"/><circle cx="${x}" cy="206" r="17" fill="${C.cyan}"/>`;
export const B = (halos = true) => `${halos ? halo('gb') : ''}<circle cx="256" cy="256" r="160" fill="${C.orange}"/>
${bar(152, 60, C.dark, 30)}${stem(204)}${bar(256, 190, C.dark, 30)}${stem(308)}${bar(360, 60, C.dark, 30)}`;
// C: speech bubble with tail, waveform inside
export const Cc = (halos = true) => `${halos ? halo('gc') : ''}<path d="M256 96a160 160 0 1 1-82 297l-52 26 22-58A160 160 0 0 1 256 96z" fill="${C.orange}"/>
${bar(184, 64)}${bar(220, 112)}${bar(256, 168)}${bar(292, 112)}${bar(328, 64)}`;
export const svg = (inner, size = 512, bg = C.bg) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="${size}" height="${size}"><rect width="512" height="512" fill="${bg}"/>${inner}</svg>`;
