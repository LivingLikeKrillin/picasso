import { writeFileSync } from 'node:fs';

const W = Number(process.argv[3] || 1000), M = 24;
const ink='#1f2328', muted='#57606a', faint='#8b95a1', border='#d0d7de',
      nodeFill='#f6f8fa', laneFill='#fbfcfd', laneStroke='#e6e9ee',
      acc='#0969da', accFill='#ddf4ff', ok='#1a7f37', okFill='#eaf3ea';

const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
const t = (x,y,s,o={}) => {
  const {size=11,fill=muted,weight=400,anchor='start',ls=null}=o;
  return `  <text x="${x}" y="${y}" font-size="${size}"${weight!==400?` font-weight="${weight}"`:''}` +
    `${anchor!=='start'?` text-anchor="${anchor}"`:''}${ls?` letter-spacing="${ls}"`:''} fill="${fill}">${esc(s)}</text>`;
};
const r = (x,y,w,h,fill,stroke,rx=6) =>
  `  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"${stroke?` stroke="${stroke}"`:''}/>`;
const poly = (pts, stroke) =>
  `  <polyline points="${pts.map(p=>p.join(',')).join(' ')}" fill="none" stroke="${stroke}" stroke-width="1.6" marker-end="url(#am)"/>`;

const checks = [
  ['① buf lint', '명명 규칙 이탈'],
  ['② buf breaking', '필드 번호 재사용'],
  ['③ 스키마와 구조 규칙', '뒤집힌 범위 · 미등록 error_type'],
  ['④ proto 교차검증', '카탈로그와 프로파일이 갈리는 것 — 양방향으로 본다'],
  ['⑤ contracts 의존 0', '계약이 무언가를 알게 되는 것'],
  ['⑥ 능력 어휘 파괴 검사', '파급을 확인하지 않은 축소'],
  ['⑦ 기종 분기 금지', '코어 소스에 등장하는 기종 문자열 — 주석이라도'],
  ['⑧ 프로파일 전용 변경', '커밋에 소스가 섞였는가'],
];

const CALLER_Y = 118, CALLER_H = 62, CALLER_W = Math.round((W - 2 * M - 40) / 2);
const LIB_Y = CALLER_Y + CALLER_H + 46, LIB_H = 64;
const LBL_Y = LIB_Y + LIB_H + 34, ROWS_Y = LBL_Y + 12;
const ROW_H = 34, GAP = 4;
const ROWS_END = ROWS_Y + checks.length * (ROW_H + GAP) - GAP;
const NEG_Y = ROWS_END + 12, NEG_H = 56;
const H = NEG_Y + NEG_H + 24;

const o = [];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push('  <defs>');
o.push(`    <marker id="am" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="${muted}"/></marker>`);
o.push('  </defs>');
o.push(r(6, 6, W - 12, H - 12, '#ffffff', border, 14));
o.push(t(32, 46, '구현은 하나이고 호출 지점이 둘이다', { size: 18, weight: 600, fill: ink }));
o.push(t(32, 72, 'CI 와 등록 검증이 각자 구현되면, CI 가 통과시킨 프로파일을 레지스트리가 거부하는 날이 온다.', { size: 12.5 }));
o.push(t(32, 93, '그날 누구도 어느 쪽이 옳은지 말할 수 없다.', { size: 12.5 }));

const callers = [
  ['CI', '기준선은 기본 브랜치의 같은 프로파일 파일'],
  ['registry 등록', '기준선은 같은 profile_id 의 직전 ACTIVE 개정판'],
];
callers.forEach(([name, base], i) => {
  const x = M + i * (CALLER_W + 40);
  o.push(r(x, CALLER_Y, CALLER_W, CALLER_H, nodeFill, border));
  o.push(t(x + CALLER_W / 2, CALLER_Y + 26, name, { size: 13, weight: 600, fill: ink, anchor: 'middle' }));
  o.push(t(x + CALLER_W / 2, CALLER_Y + 45, base, { size: 10.5, fill: muted, anchor: 'middle' }));
});

// ★버스 하나로 모은다. 폴리라인 둘을 같은 구간에 겹쳐 그리면 화살촉이 서로를 가린다.
const BUS = LIB_Y - 20;
const l = M + CALLER_W / 2, rr = M + CALLER_W + 40 + CALLER_W / 2;
o.push(`  <polyline points="${l},${CALLER_Y + CALLER_H} ${l},${BUS} ${rr},${BUS} ${rr},${CALLER_Y + CALLER_H}" fill="none" stroke="${muted}" stroke-width="1.6"/>`);
o.push(poly([[W / 2, BUS], [W / 2, LIB_Y]], muted));

o.push(r(M, LIB_Y, W - 2 * M, LIB_H, accFill, acc));
o.push(t(W / 2, LIB_Y + 26, 'gate 라이브러리', { size: 13.5, weight: 600, fill: ink, anchor: 'middle' }));
o.push(t(W / 2, LIB_Y + 46, '기준선을 인자로 받는다. 입력을 프로파일 문서로 통일해야 두 호출이 같은 답을 낸다', { size: 11, fill: acc, anchor: 'middle' }));

o.push(t(32, LBL_Y, '검사 여덟 — 각 검사가 막는 것', { size: 10, weight: 700, fill: faint, ls: 1 }));
checks.forEach(([name, blocks], i) => {
  const y = ROWS_Y + i * (ROW_H + GAP);
  o.push(r(M, y, W - 2 * M, ROW_H, i % 2 ? '#ffffff' : laneFill, laneStroke));
  o.push(t(M + 18, y + 22, name, { size: 11.5, weight: 600, fill: ink }));
  o.push(t(M + 230, y + 22, blocks, { size: 11, fill: muted }));
});

o.push(r(M, NEG_Y, W - 2 * M, NEG_H, okFill, ok));
o.push(t(M + 20, NEG_Y + 23, '⑨ 음성 시험 — 위 여덟이 실제로는 안 막고 있는 상태를 잡는다', { size: 12.5, weight: 600, fill: ink }));
o.push(t(M + 20, NEG_Y + 42, '각각이 CI 를 실패시키지 못하면 그 자체가 실패다. 검사를 세워 두고 안 도는 것이 이 저장소가 반복해 물린 자리다.', { size: 11, fill: ok }));
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H}`);
