import { writeFileSync } from 'node:fs';
const W = Number(process.argv[3] || 1000), M = 24;
const ink='#1f2328', muted='#57606a', faint='#8b95a1', border='#d0d7de',
      nodeFill='#f6f8fa', laneFill='#fbfcfd', laneStroke='#e6e9ee',
      acc='#0969da', accFill='#ddf4ff', ok='#1a7f37', okFill='#eaf3ea';
const esc = s => s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
const t=(x,y,s,o={})=>{const{size=11,fill=muted,weight=400,anchor='start',ls=null}=o;
  return `  <text x="${x}" y="${y}" font-size="${size}"${weight!==400?` font-weight="${weight}"`:''}`+
  `${anchor!=='start'?` text-anchor="${anchor}"`:''}${ls?` letter-spacing="${ls}"`:''} fill="${fill}">${esc(s)}</text>`;};
const r=(x,y,w,h,fill,stroke,rx=6)=>`  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"${stroke?` stroke="${stroke}"`:''}/>`;

const out = [
  ['vendor · model · profile_revision', '어느 기종을 상대하는지 안다'],
  ['skills[] (major.minor)', 'SKILL_ABSENT 와 호환 판정'],
  ['skills[].pause_support · cancel_support', '시도할지 말지를 가른다'],
  ['exclusive_control_required', '제어권을 먼저 잡아야 하는가'],
  ['skills[].parameters', '보내기 전에 값을 거른다'],
  ['optional_fields', 'REQUIRED_OPTIONAL_MISSING'],
  ['publish_interval', '침묵이 고장인지 가른다'],
  ['protocol_limits', 'LIMIT_EXCEEDED'],
];
const stay = [
  ['schema_version', '문서의 메타이지 능력이 아니다'],
  ['derived_from', '이 선언이 어느 벤더 원문에서 나왔는지 — 사람이 읽는다'],
  ['durations', '소요시간 상수와 지터. 소비자는 진행률을 받는다'],
  ['failure_modes', '시뮬레이션 값이고 발신자 안에서만 쓴다'],
  ['replay_buffer_size', '발신자 내부 자원이다'],
];

const HEAD=118, CRIT_H=56, CRIT_Y=HEAD;
const COLS_Y=CRIT_Y+CRIT_H+40, CW=Math.round((W-2*M-28)/2), RX2=M+CW+28;
const ROW_H=44, GAP=5;
const COL_H=44+Math.max(out.length,stay.length)*(ROW_H+GAP)-GAP+14;
const FOOT_Y=COLS_Y+COL_H+20, FOOT_H=56;
const H=FOOT_Y+FOOT_H+22;

const o=[];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push(r(6,6,W-12,H-12,'#ffffff',border,14));
o.push(t(32,46,'능력만 나가고 설정은 남는다',{size:18,weight:600,fill:ink}));
o.push(t(32,72,'프로파일 문서가 통째로 나가지 않는다. 소비자가 판정에 쓰는 것만 걸러 나가고, 나머지는 문서에 남는다.',{size:12.5}));
o.push(t(32,93,'프로파일의 최상위 열셋 중 여덟이 Capability 로 나가고 다섯이 남는다.',{size:12.5}));

o.push(r(M,CRIT_Y,W-2*M,CRIT_H,accFill,acc));
o.push(t(M+20,CRIT_Y+23,'거르는 기준 — 소비자가 행동을 결정하는 데 필요한가',{size:12.5,weight:600,fill:ink}));
o.push(t(M+20,CRIT_Y+42,'이 한 줄이 투영 함수의 명세다. 프로파일은 저작 형식이고, 로봇이 선언하는 것이 아니라 벤더 원문에서 사람이 파생한다.',{size:11,fill:acc}));

const col=(x,title,sub,rows,accent,fill)=>{
  o.push(r(x,COLS_Y,CW,COL_H,fill,accent,10));
  o.push(t(x+16,COLS_Y+24,title,{size:11,weight:700,fill:accent,ls:1}));
  o.push(t(x+16,COLS_Y+40,sub,{size:10,fill:muted}));
  rows.forEach(([k,why],i)=>{
    const y=COLS_Y+52+i*(ROW_H+GAP);
    o.push(r(x+12,y,CW-24,ROW_H,'#ffffff',border,4));
    o.push(t(x+26,y+19,k,{size:11,weight:600,fill:ink}));
    o.push(t(x+26,y+35,why,{size:10,fill:muted}));
  });
};
col(M,'Capability 로 나간다 — 여덟','소비자가 이것으로 판정한다',out,ok,okFill);
col(RX2,'문서에 남는다 — 다섯','Capability 에 실을 자리가 없다',stay,faint,laneFill);

o.push(r(M,FOOT_Y,W-2*M,FOOT_H,laneFill,laneStroke));
o.push(t(M+20,FOOT_Y+23,'같은 경계를 두 곳이 지킨다. 런타임은 Capability 에 그 필드가 아예 없고,',{size:11}));
o.push(t(M+20,FOOT_Y+42,'CI 에서는 게이트 6번이 같은 목록을 지운 뒤 버전 규칙을 판정한다.',{size:11}));
o.push('</svg>');
writeFileSync(process.argv[2], o.join('\n')+'\n');
console.log(`${W}x${H}`);
