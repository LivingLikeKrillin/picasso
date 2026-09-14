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
  ['ACCEPTED', '태스크 등록 완료 (실행 대기)'],
  ['RUNNING', '스킬 실행 중'],
  ['PAUSED', 'PauseTask 요청으로 일시 정지'],
  ['RETRIABLE', '자율 재시도 가능 오류 상태'],
  ['NEEDS_INTERVENTION', '운영자 수동 개입 필요 상태'],
  ['CANCELLING', '취소 처리 및 안전 원복 진행 중'],
];
const done = [
  ['SUCCEEDED', '스킬 정상 완료'],
  ['FAILED', '비가역적 영구 실패 (재시도 불가)'],
  ['CANCELLED', '취소 및 안전 원복 완결'],
  ['CANCELLED_RECOVERY_FAILED', '물리 복구 실패 (HoldState 잔류)'],
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
o.push(t(32, 46, '태스크 상태 머신과 종착 상태 래칭(Latch Invariant)', { size: 18, weight: 600, fill: ink }));
o.push(t(32, 72, '종착 상태에 진입한 태스크는 비가역적으로 고정되며, 비종착 상태로의 역전이가 엄격히 금지됩니다.', { size: 12.5 }));
o.push(t(32, 93, '상태 영역 간 전이는 기체 어댑터에서 보고된 이벤트 및 실행 결과에 의해 결정됩니다.', { size: 12.5 }));
o.push(t(32, 114, '상위 제어 명령(StartTask · PauseTask · RetryTask · CancelTask)은 비종착 영역 내부 전이만을 유발합니다.', { size: 12.5 }));

o.push(r(LX, LANE_Y, LW, LANE_H, laneFill, laneStroke, 10));
o.push(t(LX + 16, LANE_Y + 24, '비종착 상태군 (Non-terminal States: 6종) — 가역적 전이 영역', { size: 11, weight: 700, fill: faint, ls: 1 }));
o.push(r(RX, LANE_Y, RW, LANE_H, okFill, ok, 10));
o.push(t(RX + 16, LANE_Y + 24, '종착 상태군 (Terminal States: 4종) — 불변 래칭 영역', { size: 11, weight: 700, fill: ok, ls: 1 }));

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
const cross = [[1, 0, '스킬 정상 완료'], [1, 1, '스킬 Halt · 치명적 오류'], [5, 2, '안전 복구 완료'], [5, 3, '복구 중 Halt 발생']];
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
o.push(t(M + 20, NOTE_Y + 23, 'RetryTask 명령은 RETRIABLE 및 NEEDS_INTERVENTION 상태에서만 허용되며, 타 상태에서 호출 시 INVALID_TRANSITION 에러 반환', { size: 11 }));
o.push(t(M + 20, NOTE_Y + 42, 'CancelTask 명령은 6개 비종착 상태 전체에서 유효하며, 물리 안전 원복 시퀀스를 위해 반드시 CANCELLING 단계를 거침', { size: 11 }));
o.push(t(M + 20, NOTE_Y + 61, 'CANCELLED_RECOVERY_FAILED는 물리적 복구 실패 시에도 상태 머신의 종착성을 보장하며, 현장 수동 해제 절차로 이관됨', { size: 11, fill: warn }));
o.push('</svg>');

writeFileSync(process.argv[2], o.join('\n') + '\n');
console.log(`${W}x${H}`);
