import { writeFileSync } from 'node:fs';
const W = Number(process.argv[3] || 1000), M = 24;
const ink='#1f2328', muted='#57606a', faint='#8b95a1', border='#d0d7de',
      nodeFill='#f6f8fa', laneFill='#fbfcfd', laneStroke='#e6e9ee',
      acc='#0969da', accFill='#ddf4ff', ok='#1a7f37';
const esc=s=>s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
const t=(x,y,s,o={})=>{const{size=11,fill=muted,weight=400,anchor='start',ls=null}=o;
  return `  <text x="${x}" y="${y}" font-size="${size}"${weight!==400?` font-weight="${weight}"`:''}`+
  `${anchor!=='start'?` text-anchor="${anchor}"`:''}${ls?` letter-spacing="${ls}"`:''} fill="${fill}">${esc(s)}</text>`;};
const r=(x,y,w,h,fill,stroke,rx=6)=>`  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"${stroke?` stroke="${stroke}"`:''}/>`;

const up = ['MES','WMS / WCS','SCADA','ERP','라인 제어기','…그 밖의 무엇이든'];
// ★기체 넷이다. Orbit 은 로봇이 아니라 플릿 관리자라 그렇게 적는다.
const down = [
  ['Unitree','G1','휴머노이드 · DDS'],
  ['Boston Dynamics','Spot','4족보행 · gRPC'],
  ['Agility Robotics','Digit','휴머노이드 · WebSocket'],
  ['Boston Dynamics','Orbit','플릿 관리자 · HTTP'],
  ['산업 설비','Modbus / TCP','설비 신호'],
  ['MiMic','에뮬레이터','실물이 아니어도 된다'],
];

const BAND=[118,0,0];
const H_UP=100, H_MID=104, H_DOWN=118, BOUND=42;
const Y_UP=118, Y_MID=Y_UP+H_UP+BOUND, Y_DOWN=Y_MID+H_MID+BOUND;
const FOOT_Y=Y_DOWN+H_DOWN+22, FOOT_H=56;
const H=FOOT_Y+FOOT_H+22;
const IW=W-2*M;

const o=[];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push(r(6,6,W-12,H-12,'#ffffff',border,14));
o.push(t(32,46,'경계는 두 곳뿐이다',{size:18,weight:600,fill:ink}));
o.push(t(32,72,'가운데만 우리가 정한다. 위아래는 구현하지 않은 것이 아니라, 무엇이 와도 상관없게 만든 것이다.',{size:12.5}));
o.push(t(32,93,'여기 적은 이름들은 예시다 — 경계가 둘이라는 것이 주장이고, 목록이 주장이 아니다.',{size:12.5}));

o.push(r(M,Y_UP,IW,H_UP,laneFill,laneStroke,10));
o.push(t(M+16,Y_UP+24,'① 상위 시스템 — 무엇이 와도 된다',{size:11,weight:700,fill:faint,ls:1}));
let x=M+16;
up.forEach(n=>{const w=[...n].reduce((a,c)=>a+(/[\u3131-\uD79D]/.test(c)?12:6.6),0)+28;
  o.push(r(x,Y_UP+40,w,34,'#ffffff',border,4));
  o.push(t(x+w/2,Y_UP+61,n,{size:11.5,fill:ink,anchor:'middle'})); x+=w+10;});

o.push(t(M+16,Y_UP+H_UP+26,'경계 ①  요구를 제시하고 협상하는 소비자. consumer.kind 는 CLIENT | UPSTREAM_SYSTEM 둘뿐이다',{size:11,fill:acc,weight:600}));

o.push(r(M,Y_MID,IW,H_MID,accFill,acc,10));
o.push(t(M+16,Y_MID+24,'② 계약과 레지스트리 — 여기만 고정이다',{size:11,weight:700,fill:acc,ls:1}));
const half=(IW-44)/2;
o.push(r(M+16,Y_MID+38,half,50,'#ffffff',acc,4));
o.push(t(M+16+half/2,Y_MID+59,'계약 · proto',{size:12.5,weight:600,fill:ink,anchor:'middle'}));
o.push(t(M+16+half/2,Y_MID+77,'스킬 · 상태 · 헤더. 되돌릴 수 없는 유일한 축이다',{size:10.5,fill:muted,anchor:'middle'}));
o.push(r(M+28+half,Y_MID+38,half,50,'#ffffff',acc,4));
o.push(t(M+28+half+half/2,Y_MID+59,'레지스트리',{size:12.5,weight:600,fill:ink,anchor:'middle'}));
o.push(t(M+28+half+half/2,Y_MID+77,'기종 프로파일 · 어댑터 바인딩 · 소비자 원장',{size:10.5,fill:muted,anchor:'middle'}));

o.push(t(M+16,Y_MID+H_MID+26,'경계 ②  프로파일을 선언하고 계약을 이행하는 어댑터. 벤더 통신과 벤더 코드는 전부 이 안에서만 산다',{size:11,fill:acc,weight:600}));

o.push(r(M,Y_DOWN,IW,H_DOWN,laneFill,laneStroke,10));
o.push(t(M+16,Y_DOWN+24,'③ 기체와 설비 — 무엇이 와도 된다',{size:11,weight:700,fill:faint,ls:1}));
const cw=(IW-32-5*8)/6;
down.forEach(([v,m,note],i)=>{const cx=M+16+i*(cw+8);
  o.push(r(cx,Y_DOWN+38,cw,62,'#ffffff',border,4));
  o.push(t(cx+cw/2,Y_DOWN+55,v,{size:10,fill:faint,anchor:'middle'}));
  o.push(t(cx+cw/2,Y_DOWN+73,m,{size:12,weight:600,fill:ink,anchor:'middle'}));
  o.push(t(cx+cw/2,Y_DOWN+89,note,{size:9.5,fill:muted,anchor:'middle'}));});

o.push(r(M,FOOT_Y,IW,FOOT_H,laneFill,laneStroke));
o.push(t(M+20,FOOT_Y+23,'③ 에 무엇을 더해도 ② 는 안 바뀐다 — 새 기종은 프로파일 한 장과 그 기종만 아는 어댑터 모듈 하나다.',{size:11}));
o.push(t(M+20,FOOT_Y+42,'게이트 검사 7번이 공용 여덟 모듈의 소스를 훑어 기종 이름이 ② 로 올라오는 것을 CI 실패 조건으로 막는다.',{size:11}));
o.push('</svg>');
writeFileSync(process.argv[2], o.join('\n')+'\n');
console.log(`${W}x${H}`);
