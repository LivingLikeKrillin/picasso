// 칸과 간선을 빌드에서 읽는다. **간선은 전부 그린다** — 바닥의 계약 어휘 둘로 가는 것만 수로 적는다.
// 문법은 engineering-diagram-svg(기준본 hero.svg)를 따른다: 토큰 8색, 직각 경로, 반전 칸은 핵심만.
// ★간선마다 경로를 아래 ROUTES 에 손으로 정해 둔다. 빌드에 경로 없는 간선이 생기면 **멈춘다** —
//   자동 배치로 넘기면 그림이 조용히 엉키고, 멈추면 사람이 자리를 정한다.
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const W = 840;
const INK = '#262626', GREY = '#6f6f6f', HAIR = '#bdbdbd', GOV = '#2b3f6b', TINT = '#e8ebf3', TEXT2 = '#cfd6e6';
const SANS = "'Helvetica Neue', Helvetica, Arial, 'Apple SD Gothic Neo', 'Malgun Gothic', 'Noto Sans KR', 'Noto Sans CJK KR', sans-serif";
const MONO = "Menlo, Consolas, 'DejaVu Sans Mono', monospace";

// ── 빌드에서 읽는다
const ROOT = process.argv[4] || '.';
const SHIP = new Set(['api', 'implementation', 'compileOnly', 'runtimeOnly']);
const mods = [...new Set([...readFileSync(join(ROOT, 'settings.gradle.kts'), 'utf8')
  .split('rootProject.name')[1].matchAll(/"([a-z0-9-]+)"/g)].map(m => m[1]))].sort();
const graph = {};
for (const m of mods) {
  const f = join(ROOT, m, 'build.gradle.kts');
  if (!existsSync(f)) continue;
  graph[m] = [...new Set([...readFileSync(f, 'utf8')
    .matchAll(/^\s*(\w+)\s*\(\s*(?:testFixtures\s*\(\s*)?project\("[:]([a-z0-9-]+)"\)/gm)]
    .filter(x => SHIP.has(x[1])).map(x => x[2]))].sort();
}
const ROOTS = ['contracts', 'profile-model'];
const usersOf = n => Object.entries(graph).filter(([, d]) => d.includes(n)).map(([m]) => m);
const rootEdges = Object.values(graph).flat().filter(d => ROOTS.includes(d)).length;

// ── 문구. Gemini 초안을 사실과 대조해 넣었다(2026-10-05)
const T = {
  sub: n => `모듈 ${n} 개의 출하 의존과 표준 계약 어휘 구조를 나타낸다.`,
  note1: '간선은 빌드 파일에서 뽑는다',
  note2: '기종 코드는 어댑터 모듈에만 있다',
  zoneVendor: '기종 어댑터 모듈',
  users: n => `${n}개 모듈이 사용`,
  foot: n => `바닥의 두 계약 어휘로 향하는 화살표 ${n} 개는 안 그렸다.`,
  keyArrow: '출하 의존 (A가 B를 사용)',
  keyProject: '이 저장소',
  keyCore: '핵심 모듈',
};

// ── 칸. 열은 x 32 부터 160 간격, 폭 136. 행 A 112, B 216, C 352, 바닥 456
const col = i => 32 + 160 * i;
const BOX = {
  harness: [col(1), 112, 'harness', '통합 시험 러너'],
  orbit: [col(3), 112, 'orbit', '플릿 어댑터와 런처'],
  picasso: [col(0), 216, 'picasso', '미들웨어 코어'],
  mimic: [col(1), 216, 'mimic', '프로파일 에뮬레이터'],
  'adapter-host': [col(2), 216, 'adapter-host', '기종 비종속 런타임'],
  vendor3: [col(3), 216, 'g1, spot, digit', '이기종 기체 어댑터'],
  registry: [col(4), 216, 'registry', '기체 수명주기 운영'],
  client: [col(0), 352, 'client', '참조 클라이언트'],
  capability: [col(1), 352, 'capability', '능력 투영과 협상'],
  uplink: [col(2), 352, 'uplink', '원격측정과 상태보고'],
  'adapter-core': [col(3), 352, 'adapter-core', '추상 어댑터 계약'],
  gate: [col(4), 352, 'gate', '정합성 규칙 검증'],
};
// 표시 칸 하나가 모듈 여럿을 뜻할 수 있다. 주석은 실제 모듈 이름으로 낸다.
const CELL = {
  'adapter-unitree-g1': 'vendor3', 'adapter-boston-dynamics-spot': 'vendor3', 'adapter-agility-digit': 'vendor3',
  'adapter-boston-dynamics-orbit': 'orbit',
};
const cell = m => CELL[m] || m;

// ── 경로. 표시 칸 쌍마다 하나
const ROUTES = {
  'picasso>client': 'M100,268 V350',
  'picasso>capability': 'M144,268 V292 H228 V350',
  'mimic>capability': 'M260,268 V350',
  'mimic>uplink': 'M328,252 H336 V400 H350',
  'adapter-host>capability': 'M368,268 V316 H300 V350',
  'adapter-host>uplink': 'M420,268 V350',
  'adapter-host>adapter-core': 'M468,268 V328 H548 V350',
  'vendor3>adapter-core': 'M580,268 V350',
  'registry>gate': 'M740,268 V350',
  'harness>mimic': 'M260,164 V214',
  'harness>client': 'M192,128 H16 V378 H30',
  'harness>uplink': 'M328,148 H344 V384 H350',
  'orbit>adapter-host': 'M512,132 H440 V214',
  'orbit>uplink': 'M512,156 H496 V392 H490',
  'orbit>adapter-core': 'M648,140 H664 V378 H650',
};

const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const text = (x, y, s, { size = 11, fill = null, weight = null, mono = false, anchor = null } = {}) =>
  `<text x="${x}" y="${y}" font-size="${size}"${weight ? ` font-weight="${weight}"` : ''}` +
  `${fill ? ` fill="${fill}"` : ''}${anchor ? ` text-anchor="${anchor}"` : ''}` +
  `${mono ? ` font-family="${MONO}"` : ''}>${esc(s)}</text>`;

const o = [];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="588" viewBox="0 0 ${W} 588">`);
o.push('  <defs>');
o.push(`    <marker id="data" markerUnits="userSpaceOnUse" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto">`);
o.push(`      <path d="M1,1 L8,5 L1,9" fill="none" stroke="${INK}" stroke-width="1.2"/>`);
o.push('    </marker>');
o.push('  </defs>');
// 시험이 읽는다. 바닥 둘로 가는 간선은 수로 적으므로 여기 안 싣는다.
const drawn = new Set();
for (const [m, deps] of Object.entries(graph)) {
  for (const d of deps) {
    if (ROOTS.includes(d)) continue;
    const key = `${cell(m)}>${cell(d)}`;
    if (!ROUTES[key]) throw new Error(`경로가 없는 간선: ${m} -> ${d} (${key}). ROUTES 에 자리를 정하라`);
    drawn.add(key);
    o.push(`  <!-- edge: ${m} -> ${d} -->`);
  }
}
for (const k of Object.keys(ROUTES)) if (!drawn.has(k)) throw new Error(`빌드에 없는 경로: ${k}`);

o.push(`  <g font-family="${SANS}" fill="${INK}">`);
o.push('    ' + text(20, 36, 'picasso 모듈 구성', { size: 17, weight: 700 }));
o.push('    ' + text(20, 56, T.sub(Object.keys(graph).length), { size: 12, fill: GREY }));
o.push('    ' + text(W - 20, 36, T.note1, { fill: GREY, anchor: 'end' }));
o.push('    ' + text(W - 20, 52, T.note2, { fill: GREY, anchor: 'end' }));

o.push('\n    <!-- zone: model-specific adapters -->');
o.push(`    <rect x="504" y="84" width="152" height="196" fill="none" stroke="${HAIR}"/>`);
o.push('    ' + text(516, 100, T.zoneVendor, { fill: GREY }));

o.push('\n    <!-- modules -->');
for (const [x, y, name, role] of Object.values(BOX)) {
  o.push(`    <rect x="${x}" y="${y}" width="136" height="52" fill="${TINT}" stroke="${GOV}" stroke-width="1.5"/>`);
  o.push('    ' + text(x + 12, y + 23, name, { size: 12.5, weight: 700, mono: true }));
  o.push('    ' + text(x + 12, y + 39, role, { fill: GREY }));
}

o.push('\n    <!-- foundation: contract vocabularies -->');
[[32, 'contracts', 'proto 계약. 스킬, 원격측정, 결함'], [432, 'profile-model', '기체 프로파일 모델. 데이터 직렬화']]
  .forEach(([x, name, role]) => {
    o.push(`    <rect x="${x}" y="456" width="376" height="52" fill="${GOV}" stroke="${GOV}" stroke-width="1.5"/>`);
    o.push(`    <rect x="${x + 4}" y="460" width="368" height="44" fill="none" stroke="#fff" stroke-width="0.75" stroke-opacity="0.6"/>`);
    o.push('    ' + text(x + 16, 479, name, { size: 12.5, weight: 700, fill: '#fff', mono: true }));
    o.push('    ' + text(x + 16, 495, role, { fill: TEXT2 }));
    o.push('    ' + text(x + 360, 479, T.users(usersOf(name).length), { fill: TEXT2, anchor: 'end' }));
  });
o.push('    ' + text(20, 536, T.foot(rootEdges), { fill: GREY }));

o.push('\n    <!-- edges: shipping dependencies -->');
o.push(`    <g fill="none" stroke="${INK}" stroke-width="1.2">`);
for (const d of Object.values(ROUTES)) o.push(`      <path d="${d}" marker-end="url(#data)"/>`);
o.push('    </g>');

o.push('\n    <!-- key -->');
o.push(`    <g font-size="10.5" fill="${GREY}">`);
o.push(`      <path d="M20,564 H44" fill="none" stroke="${INK}" stroke-width="1.2"/>`);
o.push('      ' + text(50, 568, T.keyArrow));
o.push(`      <rect x="216" y="558" width="14" height="10" fill="${TINT}" stroke="${GOV}" stroke-width="1.5"/>`);
o.push('      ' + text(236, 568, T.keyProject));
o.push(`      <rect x="312" y="558" width="14" height="10" fill="${GOV}" stroke="${GOV}"/>`);
o.push('      ' + text(332, 568, T.keyCore));
o.push('    </g>');
o.push('  </g>');
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x588 · 간선 ${drawn.size} 경로 · contracts ${usersOf('contracts').length} · profile-model ${usersOf('profile-model').length} · 수로 적은 간선 ${rootEdges}`);
