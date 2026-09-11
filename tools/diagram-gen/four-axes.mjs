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
  ['계약', 'contracts/proto', '배포와 semver 로 바뀐다',
   '되돌릴 수 없다', '소비자가 이미 생성 코드를 갖고 있다', true],
  ['프로파일', 'profile/profiles/*.json', '개정판 제출로 바뀐다',
   'SUPERSEDED 된 개정판을 다시 활성화한다', 'BindingService 가 옛 판을 지우지 않고 내려 둔다', false],
  ['어댑터', 'adapter-<기종>', '그 기종을 계약에 붙이는 구현체. 배포로 바뀐다',
   '이전 버전을 다시 배포한다', '기종을 아는 코드는 이 모듈 안에서만 산다', false],
  ['바인딩', '이 기체 = 이 어댑터 + 이 프로파일', '런타임에 바뀐다 — 배포 없이',
   '이전 바인딩으로 되돌린다', '이력이 남는다', false],
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
o.push(t(32, 46, '되돌릴 수 없는 축은 하나뿐이다', { size: 18, weight: 600, fill: ink }));
o.push(t(32, 72, '네 축이 따로 움직인다. 설계의 요점은 그중 하나만 비가역이 되도록 몰아 둔 것이다.', { size: 12.5 }));
o.push(t(32, 93, '되돌리는 법이 있는 축은 셋이고, 없는 축은 맨 위 하나다.', { size: 12.5 }));

o.push(t(M + 20, HEAD_Y, '축', { size: 10, weight: 700, fill: faint, ls: 1 }));
o.push(t(M + LEFT, HEAD_Y, '무엇이 어떻게 바뀌는가', { size: 10, weight: 700, fill: faint, ls: 1 }));
o.push(t(M + LEFT + MID, HEAD_Y, '되돌리는 법', { size: 10, weight: 700, fill: faint, ls: 1 }));

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
o.push(t(M + 20, FOOT_Y + 23, '카나리는 이 구조에서 공짜로 나온다 — 바인딩이 기체 단위여서 일부만 새 조합으로 묶으면 된다.', { size: 11 }));
o.push(t(M + 20, FOOT_Y + 42, '어느 기체가 어느 개정판인지는 헤더의 profile_ref 로 관측된다. 되돌릴 때 세는 것도 같은 값이다.', { size: 11 }));
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H}`);
