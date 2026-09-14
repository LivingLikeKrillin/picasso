// 폭을 인자로 받는다 — 저장소(1000)와 더 넓은 판을 한 파일에서 뽑기 위해서다.
import { writeFileSync } from 'node:fs';

const W = Number(process.argv[3] || 1000), M = 24;
const ink='#1f2328', muted='#57606a', faint='#8b95a1', border='#d0d7de',
      nodeFill='#f6f8fa', laneFill='#fbfcfd', laneStroke='#e6e9ee',
      ok='#1a7f37', okFill='#eaf3ea', danger='#cf222e', dangerFill='#ffebe9';

const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
const t = (x,y,s,o={}) => {
  const {size=11,fill=muted,weight=400,anchor='start',ls=null}=o;
  return `  <text x="${x}" y="${y}" font-size="${size}"${weight!==400?` font-weight="${weight}"`:''}` +
    `${anchor!=='start'?` text-anchor="${anchor}"`:''}${ls?` letter-spacing="${ls}"`:''} fill="${fill}">${esc(s)}</text>`;
};
const r = (x,y,w,h,fill,stroke,rx=6) =>
  `  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"${stroke?` stroke="${stroke}"`:''}/>`;

const axes = [
  ['계약 (Contract)', 'contracts/proto', 'SemVer 기반 배포를 통한 변경',
   '비가역적 (롤백 불가)', '소비자 클라이언트의 Codegen 의존성 고착', true],
  ['프로파일 (Profile)', 'profile/profiles/*.json', '신규 개정판(Revision) 등록을 통한 변경',
   'SUPERSEDED 개정판 재활성화(롤백)', 'BindingService의 이전 개정판 불변 보존 및 상태 전이 관리', false],
  ['어댑터 (Adapter)', 'adapter-<기종>', '기종별 프로토콜 변환 구현체 (바이너리 배포 단위)',
   '이전 바이너리 릴리즈 재배포', '기종 전용 지식은 어댑터 모듈 경계 내에만 캡슐화', false],
  ['바인딩 (Binding)', '기체 ID = 어댑터 + 프로파일 개정판', '런타임 무중단 동적 전환 (배포 불필요)',
   '이전 바인딩 매핑 복원', '원장 내 바인딩 전이 감사 이력(Audit Trail) 완전 보존', false],
];

const HEAD_Y = 118, ROW_H = 78, GAP = 6;
const LEFT = 176, MID = Math.round((W - 2 * M - LEFT) * 0.46);
const ROWS_Y = HEAD_Y + 26;
const END = ROWS_Y + axes.length * (ROW_H + GAP) - GAP;
const FOOT_Y = END + 20, FOOT_H = 56;
const H = FOOT_Y + FOOT_H + 22;

const o = [];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push(r(6, 6, W - 12, H - 12, '#ffffff', border, 14));
o.push(t(32, 46, '시스템 변경의 4대 독립 축과 가역성', { size: 18, weight: 600, fill: ink }));
o.push(t(32, 72, '계약·프로파일·어댑터·바인딩이 상호 독립적으로 변경되며, 비가역적 위험을 단일 축(계약)으로 국소화했습니다.', { size: 12.5 }));
o.push(t(32, 93, '하위 3개 축은 명확한 런타임/배포 롤백 절차를 지원하며, 최상위 계약 축만 엄격한 호환성 규칙을 적용받습니다.', { size: 12.5 }));

o.push(t(M + 20, HEAD_Y, '변경 축 (Axis)', { size: 10, weight: 700, fill: faint, ls: 1 }));
o.push(t(M + LEFT, HEAD_Y, '변경 메커니즘 및 영향 범위', { size: 10, weight: 700, fill: faint, ls: 1 }));
o.push(t(M + LEFT + MID, HEAD_Y, '롤백 전략 및 복구 메커니즘', { size: 10, weight: 700, fill: faint, ls: 1 }));

axes.forEach(([name, what, how, undo, why, irreversible], i) => {
  const y = ROWS_Y + i * (ROW_H + GAP);
  const w = W - 2 * M;
  o.push(r(M, y, w, ROW_H, irreversible ? dangerFill : nodeFill, irreversible ? danger : border));
  o.push(t(M + 20, y + 30, name, { size: 14, weight: 600, fill: ink }));
  o.push(t(M + 20, y + 50, what, { size: 10, fill: faint }));
  o.push(t(M + LEFT, y + 30, how, { size: 11, fill: ink }));
  o.push(t(M + LEFT + MID, y + 30, undo, {
    size: 11.5, weight: 600, fill: irreversible ? danger : ok,
  }));
  o.push(t(M + LEFT + MID, y + 50, why, { size: 10.5, fill: muted }));
});

o.push(r(M, FOOT_Y, W - 2 * M, FOOT_H, laneFill, laneStroke));
o.push(t(M + 20, FOOT_Y + 23, '기체 단위 독립 바인딩 매핑을 통해 무중단 카나리 배포를 구조적으로 지원 (선별 기체에만 신규 조합 적용)', { size: 11 }));
o.push(t(M + 20, FOOT_Y + 42, '기체별 활성 개정판은 요청 헤더의 profile_ref 필드로 전파 및 관측되며, 롤백 대상 기체 식별의 기준값으로 사용됨', { size: 11 }));
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H}`);
