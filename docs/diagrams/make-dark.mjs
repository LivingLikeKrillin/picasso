#!/usr/bin/env node
// Derive the dark-theme variant of a house-dialect SVG from the light one.
//
// GitHub cannot restyle an inlined SVG from a stylesheet the way a site can, so a README figure
// needs two files behind a <picture>. Two hand-maintained files drift, so only the light SVG is
// authored: this maps its palette onto GitHub's dark tokens and writes <name>.dark.svg.
//
//   node docs/diagrams/make-dark.mjs docs/diagrams/activation-ladder.svg
//
// Re-run it after editing a light SVG, or the dark variant silently goes stale.
import { readFileSync, writeFileSync } from 'node:fs';

// light token -> GitHub dark token. Keys are the arch-diagram design tokens plus the two
// semantic accents this diagram adds (attention amber, danger red).
const MAP = {
  '#ffffff': '#0d1117',   // canvas
  '#fbfcfd': '#161b22',   // lane fill
  '#f6f8fa': '#1c2128',   // node fill
  '#1f2328': '#c9d1d9',   // ink
  '#57606a': '#8b949e',   // muted
  '#8b95a1': '#8b949e',   // faint (zone headers)
  '#d0d7de': '#30363d',   // border
  '#e6e9ee': '#21262d',   // lane stroke
  '#0969da': '#58a6ff',   // accent / control
  '#ddf4ff': '#121d2f',   // accent fill
  '#eef5ff': '#101a28',   // accent fill, faint (zone behind nodes)
  '#cfe0f5': '#22334d',   // accent border, faint
  '#1a7f37': '#3fb950',   // success
  '#eaf3ea': '#0f2f1a',   // success fill
  '#9a6700': '#d29922',   // attention
  '#cf222e': '#f85149',   // danger
  '#ffebe9': '#25171c',   // danger fill
};

// engineering-diagram-svg 토큰(§7 다크 변종). 반전 칸의 governed 와 그 안의 글자는 그대로 둔다.
// 이 토큰으로 그린 그림은 governed(#2b3f6b)를 들고 있으므로 그것으로 가른다.
const ENGINEERING = {
  '#ffffff': '#1a1a1a',   // paper
  '#262626': '#e6e6e6',   // ink
  '#6f6f6f': '#9a9a9a',   // grey
  '#bdbdbd': '#4a4a4a',   // hairline
  '#e8ebf3': '#232a3a',   // governed-tint
  '#2b3f6b': '#2b3f6b',   // governed
  '#cfd6e6': '#cfd6e6',   // governed-text2
  '#b23a1d': '#d4654a',   // command
};

const src = process.argv[2];
if (!src) { console.error('usage: node make-dark.mjs <light.svg>'); process.exit(2); }

let svg = readFileSync(src, 'utf8');
const map = /#2b3f6b/i.test(svg) ? ENGINEERING : MAP;
// ★governed 는 반전 칸의 **채움**으로는 다크에서도 읽히지만, 선과 글자로는 검은 바탕에 묻힌다.
//   그래서 칸 채움(rect 의 fill)만 그대로 두고 선과 글자는 밝은 남색으로 올린다.
if (map === ENGINEERING) {
  svg = svg.replace(/<(\w+)([^>]*)>/g, (tag, name, attrs) =>
    `<${name}${attrs.replace(/stroke="#2b3f6b"/gi, 'stroke="#8fa2cc"')
      .replace(/fill="#2b3f6b"/gi, name === 'rect' ? 'fill="#2b3f6b"' : 'fill="#8fa2cc"')}>`);
  ENGINEERING['#8fa2cc'] = '#8fa2cc';
}
const seen = new Set();
svg = svg.replace(/#[0-9a-fA-F]{6}/g, (hex) => {
  const k = hex.toLowerCase();
  if (!(k in map)) { seen.add(k); return hex; }
  return map[k];
});

if (seen.size) {
  console.error(`unmapped colours (add them to MAP): ${[...seen].join(', ')}`);
  process.exit(1);
}

const out = src.replace(/\.svg$/, '.dark.svg');
writeFileSync(out, svg);
console.log(`${out}  (${svg.length} bytes)`);
