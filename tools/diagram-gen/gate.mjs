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
  ['① buf lint', 'Protobuf 스타일 가이드 및 네이밍 규칙 준수 검증'],
  ['② buf breaking', '필드 번호 재사용 및 하위 호환성 파괴 변경 차단'],
  ['③ 스키마 및 구조 규칙', '값 범위 역전(min > max) · 미등록 error_type 검출'],
  ['④ proto 교차 검증', '능력 카탈로그와 프로파일 간 양방향 선언 정합성 검증'],
  ['⑤ contracts 의존성 격리', '코어 계약 모듈(contracts)의 외부 의존성 제로 보장'],
  ['⑥ 능력 어휘 파괴 검사', '영향도 분석 없는 임의의 능력(LogicalCapability) 축소 차단'],
  ['⑦ 기종 분기 금지', '공용 코어 소스 및 주석 내 벤더/기종 심볼 침투 원천 차단'],
  ['⑧ 프로파일 전용 변경', '프로파일 단독 변경 PR 내 소스 코드 혼입 차단'],
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
o.push(t(32, 46, '단일 게이트 로직과 다중 실행 진입점 (Single Gate, Dual Entrypoints)', { size: 18, weight: 600, fill: ink }));
o.push(t(32, 72, 'CI 빌드 파이프라인과 런타임 레지스트리 등록 검증 로직이 분리되면 정합성 불일치가 발생합니다.', { size: 12.5 }));
o.push(t(32, 93, '따라서 단일 gate 공용 라이브러리를 공유하여 검증 판정의 절대적 일관성을 보장합니다.', { size: 12.5 }));

const callers = [
  ['CI 빌드 파이프라인', '기준선: 베이스 브랜치의 동일 프로파일 파일'],
  ['런타임 레지스트리 등록', '기준선: 동일 profile_id의 직전 ACTIVE 개정판'],
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
o.push(t(W / 2, LIB_Y + 26, 'gate 공용 검증 라이브러리 (:gate)', { size: 13.5, weight: 600, fill: ink, anchor: 'middle' }));
o.push(t(W / 2, LIB_Y + 46, '기준선(Baseline)을 파라미터로 주입받아 동일한 프로파일 문서에 대해 불변의 검증 결과 보장', { size: 11, fill: acc, anchor: 'middle' }));

o.push(t(32, LBL_Y, '8대 게이트 검증 항목 및 차단 대상', { size: 10, weight: 700, fill: faint, ls: 1 }));
checks.forEach(([name, blocks], i) => {
  const y = ROWS_Y + i * (ROW_H + GAP);
  o.push(r(M, y, W - 2 * M, ROW_H, i % 2 ? '#ffffff' : laneFill, laneStroke));
  o.push(t(M + 18, y + 22, name, { size: 11.5, weight: 600, fill: ink }));
  o.push(t(M + 230, y + 22, blocks, { size: 11, fill: muted }));
});

o.push(r(M, NEG_Y, W - 2 * M, NEG_H, okFill, ok));
o.push(t(M + 20, NEG_Y + 23, '⑨ 음성 검증(Negative Testing) — 게이트 차단 유효성 자동 검증', { size: 12.5, weight: 600, fill: ink }));
o.push(t(M + 20, NEG_Y + 42, '위성 테스트케이스가 정상적으로 빌드를 실패시키지 못할 경우 테스트 자체 실패 처리 (False Negative 방지)', { size: 11, fill: ok }));
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H}`);
