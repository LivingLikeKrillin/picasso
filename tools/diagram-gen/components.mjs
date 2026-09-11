// 칸과 간선을 빌드에서 읽는다. **배치는 역할이 정한다** — 같은 크기의 칸을 줄 세우면
// 모두가 평등해 보이고, 그러면 위계도 포함 관계도 사라진다.
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const W = Number(process.argv[3] || 1000), M = 24, IW = W - 2 * M;
const ink = '#1f2328', muted = '#57606a', faint = '#8b95a1', border = '#d0d7de',
      laneFill = '#fbfcfd', laneStroke = '#e6e9ee',
      acc = '#0969da', accFill = '#ddf4ff', accEdge = '#cfe0f5',
      ok = '#1a7f37', okFill = '#eaf3ea';

const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const t = (x, y, s, o = {}) => {
  const { size = 11, fill = muted, weight = 400, anchor = 'start', ls = null } = o;
  return `  <text x="${x}" y="${y}" font-size="${size}"` +
    `${weight !== 400 ? ` font-weight="${weight}"` : ''}` +
    `${anchor !== 'start' ? ` text-anchor="${anchor}"` : ''}` +
    `${ls ? ` letter-spacing="${ls}"` : ''} fill="${fill}">${esc(s)}</text>`;
};
const r = (x, y, w, h, fill, stroke, rx = 6) =>
  `  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"` +
  `${stroke ? ` stroke="${stroke}"` : ''}/>`;
const arr = (pts, stroke) =>
  `  <polyline points="${pts.map(p => p.join(',')).join(' ')}" fill="none" stroke="${stroke}" stroke-width="1.5" marker-end="url(#am)"/>`;
const line = (pts, stroke) =>
  `  <polyline points="${pts.map(p => p.join(',')).join(' ')}" fill="none" stroke="${stroke}" stroke-width="1.5"/>`;

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
const VENDOR = ['adapter-unitree-g1', 'adapter-boston-dynamics-spot',
                'adapter-agility-digit', 'adapter-boston-dynamics-orbit'];
const usersOf = n => Object.entries(graph).filter(([, d]) => d.includes(n)).map(([m]) => m);
const rootEdges = Object.values(graph).flat().filter(d => ROOTS.includes(d)).length;

// ── 자리
const CONS_Y = 130, CONS_H = 84;
const VOC_Y = CONS_Y + CONS_H + 40, VOC_H = 104;
const SND_Y = VOC_Y + VOC_H + 40, SND_H = 272;
const OPS_Y = SND_Y + SND_H + 20, OPS_H = 88;
const FOOT_Y = OPS_Y + OPS_H + 18, FOOT_H = 58;
const H = FOOT_Y + FOOT_H + 22;

const o = [];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push('  <defs>');
o.push(`    <marker id="am" markerWidth="8" markerHeight="8" refX="6.5" refY="2.75" orient="auto"><path d="M0,0 L6.5,2.75 L0,5.5 Z" fill="${muted}"/></marker>`);
o.push('  </defs>');
// 시험이 읽는다. 바닥 둘로 가는 간선은 받침으로 그리므로 여기 안 싣는다.
for (const [m, deps] of Object.entries(graph)) {
  for (const d of deps) if (!ROOTS.includes(d)) o.push(`  <!-- edge: ${m} -> ${d} -->`);
}
o.push(r(6, 6, W - 12, H - 12, '#ffffff', border, 14));
o.push(t(32, 46, '가운데는 어휘이고, 위아래는 갈아 끼워진다', { size: 18, weight: 600, fill: ink }));
o.push(t(32, 72, `모듈 ${Object.keys(graph).length} 개. 계약 어휘 둘은 프로젝트 안의 무엇도 안 쓰고, 나머지 열넷이 그 위에 선다.`, { size: 12.5 }));
o.push(t(32, 93, '발신자 둘이 같은 자리에 서고, 기종을 아는 넷은 그중 하나 안에만 있다.', { size: 12.5 }));

// ── 소비자
o.push(r(M, CONS_Y, IW, CONS_H, laneFill, laneStroke, 10));
o.push(t(M + 16, CONS_Y + 22, '소비자 — 계약을 두드린다', { size: 10.5, weight: 700, fill: faint, ls: 1 }));
const cw = (IW - 48) / 2;
const consumers = [
  ['picasso', '미들웨어의 가운데. 접수 → 조합 → 실행 → 근거 결합. 기종을 모른다'],
  ['client', '계약을 두드려 완료 기준을 증명하는 얇은 소비자'],
];
consumers.forEach(([n, d], i) => {
  const x = M + 16 + i * (cw + 16);
  o.push(r(x, CONS_Y + 32, cw, 38, '#ffffff', border, 4));
  o.push(t(x + 14, CONS_Y + 50, n, { size: 12.5, weight: 600, fill: ink }));
  o.push(t(x + 14, CONS_Y + 64, d, { size: 9.5, fill: muted }));
});
// picasso -> client
o.push(arr([[M + 16 + cw, CONS_Y + 51], [M + 16 + cw + 14, CONS_Y + 51]], muted));

// ── 어휘 기둥. 가장 크고, 위아래가 여기 얹힌다
o.push(r(M, VOC_Y, IW, VOC_H, accFill, acc, 10));
o.push(t(M + 18, VOC_Y + 25, '계약 어휘 — 프로젝트 내 의존 0', { size: 11, weight: 700, fill: acc, ls: 1 }));
const vw = (IW - 52) / 2;
[['contracts', 'proto. 스킬 · 태스크 · 이벤트 · 결함'],
 ['profile-model', '프로파일 문서의 읽기 전용 모델']].forEach(([n, d], i) => {
  const x = M + 18 + i * (vw + 16);
  o.push(r(x, VOC_Y + 36, vw, 52, '#ffffff', acc, 4));
  o.push(t(x + 16, VOC_Y + 59, n, { size: 14.5, weight: 600, fill: ink }));
  o.push(t(x + 16, VOC_Y + 77, d, { size: 10, fill: muted }));
  o.push(t(x + vw - 16, VOC_Y + 59, `${usersOf(n).length} 이 쓴다`, { size: 12, weight: 600, fill: acc, anchor: 'end' }));
});
// 받침 둘 — 화살표 20 개 대신
// ★받침 선을 그렸다가 지웠다 — 빈 상자로 읽혔다. 캡션 둘이 같은 말을 더 잘한다.
o.push(t(W / 2, VOC_Y - 14, '↑ 소비자 둘이 여기 기댄다', { size: 10.5, fill: acc, anchor: 'middle' }));
o.push(t(W / 2, VOC_Y + VOC_H + 22, `↓ 아래 열둘도 전부 여기 기댄다 — 그 화살표 ${rootEdges} 개는 안 그렸다`, { size: 10.5, fill: acc, anchor: 'middle' }));

// ── 발신자
o.push(r(M, SND_Y, IW, SND_H, laneFill, laneStroke, 10));
o.push(t(M + 16, SND_Y + 22, '발신자 — 계약을 구현해 선다', { size: 10.5, weight: 700, fill: faint, ls: 1 }));
const sx = M + 16, sw = IW - 32;
o.push(r(sx, SND_Y + 32, sw, 44, '#ffffff', border, 4));
o.push(t(sx + 14, SND_Y + 50, '공유 부품', { size: 10, weight: 700, fill: faint, ls: 0.5 }));
o.push(t(sx + 14, SND_Y + 66, '아래 둘이 함께 쓴다', { size: 9.5, fill: faint }));
[['capability', '프로파일 → Capability 투영과 협상. 전송을 모른다'],
 ['uplink', '브로커 발행과 레지스트리 적재']].forEach(([n, d], i) => {
  const x = sx + 110 + i * ((sw - 124) / 2);
  o.push(t(x, SND_Y + 52, n, { size: 12, weight: 600, fill: ink }));
  o.push(t(x, SND_Y + 67, d, { size: 9.5, fill: muted }));
});

const tw = (sw - 60) / 2, ty = SND_Y + 92, th = 162;
// 왼쪽 — mimic
o.push(r(sx, ty, tw, th, laneFill, laneStroke, 10));  // 담는 칸 = 지대
o.push(t(sx + tw / 2, ty + 27, 'mimic', { size: 14, weight: 600, fill: ink, anchor: 'middle' }));
o.push(t(sx + tw / 2, ty + 45, '프로파일 주도 에뮬레이터', { size: 10.5, fill: muted, anchor: 'middle' }));
o.push(t(sx + tw / 2, ty + 61, '「어댑터 + 로봇」 한 쌍을 대신한다', { size: 10, fill: muted, anchor: 'middle' }));
o.push(r(sx + 24, ty + 76, tw - 48, 66, '#ffffff', border, 5));
o.push(t(sx + tw / 2, ty + 100, '프로파일 한 장', { size: 12, weight: 600, fill: faint, anchor: 'middle' }));
o.push(t(sx + tw / 2, ty + 118, '기종이 곧 이 문서다', { size: 9.5, fill: faint, anchor: 'middle' }));
o.push(t(sx + tw / 2, ty + 133, '실물이 아니어도 된다', { size: 9.5, fill: faint, anchor: 'middle' }));

// 오른쪽 — adapter-host 가 담는다
const hx = sx + tw + 60;
o.push(r(hx, ty, tw, th, laneFill, laneStroke, 10));  // 담는 칸 = 지대
o.push(t(hx + tw / 2, ty + 27, 'adapter-host', { size: 14, weight: 600, fill: ink, anchor: 'middle' }));
o.push(t(hx + tw / 2, ty + 45, '어댑터 하나를 계약의 gRPC 서비스 뒤에 세운다 · 기종을 모른다', { size: 10, fill: muted, anchor: 'middle' }));
o.push(r(hx + 16, ty + 56, tw - 32, 58, okFill, ok, 5));
o.push(t(hx + tw / 2, ty + 73, '기종 어댑터 넷 — 기종을 아는 유일한 자리', { size: 10.5, weight: 600, fill: ok, anchor: 'middle' }));
const aw = (tw - 44) / 4;
VENDOR.forEach((v, i) => {
  const x = hx + 22 + i * (aw + 4);
  o.push(r(x, ty + 82, aw, 22, '#ffffff', ok, 3));
  o.push(t(x + aw / 2, ty + 97, v.replace('adapter-', '').replace('boston-dynamics-', ''),
    { size: 9.5, fill: ok, anchor: 'middle' }));
});
o.push(r(hx + 16, ty + 120, tw - 32, 28, '#ffffff', border, 5));
o.push(t(hx + tw / 2, ty + 138, 'adapter-core — 넷이 공유하는 RobotAdapter. 기종을 모른다', { size: 9.5, fill: muted, anchor: 'middle' }));

// 같은 자리
const gx = sx + tw + 30;
o.push(line([[sx + tw + 4, ty + 10], [gx + 26, ty + 10]], accEdge));
o.push(line([[sx + tw + 4, ty + th - 10], [gx + 26, ty + th - 10]], accEdge));
o.push(t(gx, ty + th / 2 - 5, '같은', { size: 10.5, weight: 700, fill: acc, anchor: 'middle' }));
o.push(t(gx, ty + th / 2 + 10, '자리', { size: 10.5, weight: 700, fill: acc, anchor: 'middle' }));

// ── 운영 · 시험
const ow = (IW - 16) / 2;
o.push(r(M, OPS_Y, ow, OPS_H, laneFill, laneStroke, 10));
o.push(t(M + 16, OPS_Y + 22, '시험', { size: 10.5, weight: 700, fill: faint, ls: 1 }));
o.push(r(M + 16, OPS_Y + 32, ow - 32, 42, '#ffffff', border, 4));
o.push(t(M + 16 + (ow - 32) / 2, OPS_Y + 51, 'harness', { size: 12, weight: 600, fill: ink, anchor: 'middle' }));
o.push(t(M + 16 + (ow - 32) / 2, OPS_Y + 65, '계약 스위트의 주인 — mimic 을 띄우고 client 로 돈다', { size: 9.5, fill: muted, anchor: 'middle' }));
// ★harness 를 mimic 바로 아래로 옮겨 「띄운다」를 화살표 하나로 보인다.
o.push(arr([[sx + tw / 2, OPS_Y + 32], [sx + tw / 2, ty + th + 4]], muted));

const pw = (ow - 48) / 2;
o.push(r(M + ow + 16, OPS_Y, ow, OPS_H, laneFill, laneStroke, 10));
o.push(t(M + ow + 32, OPS_Y + 22, '운영', { size: 10.5, weight: 700, fill: faint, ls: 1 }));
o.push(r(M + ow + 32, OPS_Y + 32, pw, 42, '#ffffff', border, 4));
o.push(t(M + ow + 32 + pw / 2, OPS_Y + 51, 'registry', { size: 12, weight: 600, fill: ink, anchor: 'middle' }));
o.push(t(M + ow + 32 + pw / 2, OPS_Y + 65, '수명주기 · 바인딩 · 원장 · 변경 계획', { size: 9.5, fill: muted, anchor: 'middle' }));
o.push(r(M + ow + 48 + pw, OPS_Y + 32, pw, 42, '#ffffff', border, 4));
o.push(t(M + ow + 48 + pw + pw / 2, OPS_Y + 51, 'gate', { size: 12, weight: 600, fill: ink, anchor: 'middle' }));
o.push(t(M + ow + 48 + pw + pw / 2, OPS_Y + 65, '검증 규칙의 단일 구현', { size: 9.5, fill: muted, anchor: 'middle' }));
o.push(arr([[M + ow + 48 + pw, OPS_Y + 53], [M + ow + 34 + pw, OPS_Y + 53]], muted));

o.push(r(M, FOOT_Y, IW, FOOT_H, laneFill, laneStroke));
o.push(t(M + 20, FOOT_Y + 23, '초록 넷만 기종 이름을 안다. 나머지에 벤더·모델 문자열이 들어오면 게이트 검사 7번이 CI 를 실패시킨다 — 주석이라도 걸린다.', { size: 11 }));
o.push(t(M + 20, FOOT_Y + 42, '칸과 간선은 각 build.gradle.kts 에서 읽는다. 주석이 어휘 둘로 가지 않는 출하 간선 전부를 싣고, 그림은 그중 일부를 화살표로 일부를 담기로 보인다.', { size: 10, fill: faint }));
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H} · contracts ${usersOf('contracts').length} · profile-model ${usersOf('profile-model').length} · 받침으로 접은 간선 ${rootEdges}`);
