import { writeFileSync } from 'node:fs';

const W = Number(process.argv[3] || 1000), M = 24;
const ink='#1f2328', muted='#57606a', faint='#8b95a1', border='#d0d7de',
      nodeFill='#f6f8fa', laneFill='#fbfcfd', laneStroke='#e6e9ee',
      ok='#1a7f37', okFill='#eaf3ea', warn='#9a6700';

const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
const t = (x,y,s,o={}) => {
  const {size=11,fill=muted,weight=400,anchor='start',ls=null}=o;
  return `  <text x="${x}" y="${y}" font-size="${size}"${weight!==400?` font-weight="${weight}"`:''}` +
    `${anchor!=='start'?` text-anchor="${anchor}"`:''}${ls?` letter-spacing="${ls}"`:''} fill="${fill}">${esc(s)}</text>`;
};
const r = (x,y,w,h,fill,stroke,rx=6) =>
  `  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"${stroke?` stroke="${stroke}"`:''}/>`;
const edge = (pts, stroke, marker) =>
  `  <polyline points="${pts.map(p=>p.join(',')).join(' ')}" fill="none" stroke="${stroke}" stroke-width="1.6" marker-end="url(#${marker})"/>`;

// 왼쪽 = 비종착 여섯, 오른쪽 = 종착 넷. 칸 폭은 두 지대가 같다.
// ★거터가 라벨을 담는다. 좁게 두면 칩이 옆 지대의 칸 위로 넘어가고, 검증기가 그것을 잡는다.
const LANE_GAP = 152;
const LW = Math.round((W - 2 * M - LANE_GAP) * 0.52);
const RW = W - 2 * M - LANE_GAP - LW;
const LX = M, RX = M + LW + LANE_GAP;

const LANE_Y = 150, NODE_H = 52, NV = 16;
const live = [
  ['ACCEPTED', '접수됐고 아직 시작 전'],
  ['RUNNING', '스킬이 돌고 있다'],
  ['PAUSED', 'PauseTask 로 멈춰 있다'],
  ['RETRIABLE', '로봇 혼자 다시 하면 된다'],
  ['NEEDS_INTERVENTION', '사람이 무언가 해야 한다'],
  ['CANCELLING', '취소는 즉시가 아니라 되돌리는 구간이다'],
];
const done = [
  ['SUCCEEDED', '스킬이 완료했다'],
  ['FAILED', '재시도해도 같은 결과다'],
  ['CANCELLED', '되돌리기까지 마쳤다'],
  ['CANCELLED_RECOVERY_FAILED', '물건을 든 채 멈춰 있다'],
];

const LANE_H = Math.max(live.length, done.length) * (NODE_H + NV) - NV + 56;
const NOTE_Y = LANE_Y + LANE_H + 20, NOTE_H = 78;
const H = NOTE_Y + NOTE_H + 24;

const ly = i => LANE_Y + 44 + i * (NODE_H + NV);
const ry = i => LANE_Y + 44 + i * (NODE_H + NV) + 34;

const o = [];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push('  <defs>');
o.push(`    <marker id="am" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="${muted}"/></marker>`);
o.push(`    <marker id="aw" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="${warn}"/></marker>`);
o.push('  </defs>');
o.push(r(6, 6, W - 12, H - 12, '#ffffff', border, 14));
o.push(t(32, 46, '종착은 래치된다', { size: 18, weight: 600, fill: ink }));
o.push(t(32, 72, '종착에 든 태스크는 어떤 이유로도 비종착으로 돌아오지 않는다. 그래서 오른쪽에서 왼쪽으로 가는 선이 없다.', { size: 12.5 }));
o.push(t(32, 93, '지대를 건너는 선은 로봇에서 올라오는 사건이고, 라벨은 그 사건이 무엇이었는지다.', { size: 12.5 }));
o.push(t(32, 114, '상위가 부르는 명령(StartTask · PauseTask · RetryTask · CancelTask)은 왼쪽 지대 안에서만 움직인다.', { size: 12.5 }));

o.push(r(LX, LANE_Y, LW, LANE_H, laneFill, laneStroke, 10));
o.push(t(LX + 16, LANE_Y + 24, '비종착 여섯 — 되돌아가는 화살표는 이 안에만 있다', { size: 11, weight: 700, fill: faint, ls: 1 }));
o.push(r(RX, LANE_Y, RW, LANE_H, okFill, ok, 10));
o.push(t(RX + 16, LANE_Y + 24, '종착 넷 — 나가는 화살표가 없다', { size: 11, weight: 700, fill: ok, ls: 1 }));

live.forEach(([name, note], i) => {
  const y = ly(i);
  o.push(r(LX + 16, y, LW - 32, NODE_H, nodeFill, border));
  o.push(t(LX + 32, y + 22, name, { size: 12.5, weight: 600, fill: ink }));
  o.push(t(LX + 32, y + 40, note, { size: 10.5, fill: muted }));
});
done.forEach(([name, note], i) => {
  const y = ry(i);
  o.push(r(RX + 16, y, RW - 32, NODE_H, '#ffffff', ok));
  o.push(t(RX + 32, y + 22, name, { size: 12, weight: 600, fill: ink }));
  o.push(t(RX + 32, y + 40, note, { size: 10.5, fill: muted }));
});

// 지대를 건너는 선은 전부 왼→오른쪽이다. 그것이 이 그림의 주장이다.
const cross = [[1, 0, '스킬 완료'], [1, 1, '스킬 Halt · TERMINAL'], [5, 2, '복구 완료'], [5, 3, '복구 중 Halt']];
cross.forEach(([from, to, label]) => {
  const y1 = ly(from) + NODE_H / 2, y2 = ry(to) + NODE_H / 2;
  const midX = LX + LW + LANE_GAP / 2;
  o.push(edge([[LX + LW - 16, y1], [midX, y1], [midX, y2], [RX + 16, y2]], muted, 'am'));
  // ★라벨을 정의만 하고 안 그린 판이 있었다. 선이 무엇이었는지가 이 그림의 절반이다.
  // CJK 는 글자당 font-size, 라틴은 약 0.55 배다. 한 수로 곱하면 한쪽이 반드시 틀린다.
  const lw = [...label].reduce((a, c) => a + (/[ㄱ-힝]/.test(c) ? 10 : 5.5), 0) + 12;
  o.push(r(midX - lw / 2, y2 - 9, lw, 16, '#ffffff', null, 3));
  o.push(t(midX, y2 + 3, label, { size: 10, fill: muted, anchor: 'middle' }));
});

o.push(r(M, NOTE_Y, W - 2 * M, NOTE_H, laneFill, laneStroke));
o.push(t(M + 20, NOTE_Y + 23, 'RetryTask 는 RETRIABLE · NEEDS_INTERVENTION 의 탈출구 하나뿐이다. 그 밖의 상태에서 부르면 INVALID_TRANSITION 이다.', { size: 11 }));
o.push(t(M + 20, NOTE_Y + 42, 'CancelTask 는 비종착 여섯 어디서나 합법이다 — 취소가 CANCELLING 을 지나는 것은 되돌릴 것이 남아 있기 때문이다.', { size: 11 }));
o.push(t(M + 20, NOTE_Y + 61, 'CANCELLED_RECOVERY_FAILED 가 종착인 것이 요점이다. 되돌리기에 실패해도 태스크는 끝난 것이고, 남은 것은 사람의 일이다.', { size: 11, fill: warn }));
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H}`);
