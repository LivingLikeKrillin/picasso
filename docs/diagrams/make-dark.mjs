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

const src = process.argv[2];
if (!src) { console.error('usage: node make-dark.mjs <light.svg>'); process.exit(2); }

let svg = readFileSync(src, 'utf8');
const seen = new Set();
svg = svg.replace(/#[0-9a-fA-F]{6}/g, (hex) => {
  const k = hex.toLowerCase();
  if (!(k in MAP)) { seen.add(k); return hex; }
  return MAP[k];
});

if (seen.size) {
  console.error(`unmapped colours (add them to MAP): ${[...seen].join(', ')}`);
  process.exit(1);
}

const out = src.replace(/\.svg$/, '.dark.svg');
writeFileSync(out, svg);
console.log(`${out}  (${svg.length} bytes)`);
