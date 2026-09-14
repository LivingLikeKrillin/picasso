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

const up = ['MES','WMS / WCS','SCADA','ERP','라인 제어기','기타 상위 도메인 시스템'];
// ★기체 넷이다. Orbit 은 로봇이 아니라 플릿 관리자라 그렇게 적는다.
const down = [
  ['Unitree','G1','휴머노이드 · DDS'],
  ['Boston Dynamics','Spot','4족보행 · gRPC'],
  ['Agility Robotics','Digit','휴머노이드 · WebSocket'],
  ['Boston Dynamics','Orbit','플릿 관리자 · HTTP'],
  ['산업 설비','Modbus / TCP','설비 신호'],
  ['MiMic','에뮬레이터','고충실도 가상화 기체'],
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
o.push(t(32,46,'3계층 아키텍처 및 2대 시스템 경계',{size:18,weight:600,fill:ink}));
o.push(t(32,72,'미들웨어 코어 계약만 고정하며, 상위 시스템 및 하위 기종은 플러그형 확장 구조를 취함',{size:12.5}));
o.push(t(32,93,'상위 소비자 및 하위 물리 기종 목록은 예시이며, 핵심 설계 목표는 두 경계면의 완전한 격리입니다.',{size:12.5}));

o.push(r(M,Y_UP,IW,H_UP,laneFill,laneStroke,10));
o.push(t(M+16,Y_UP+24,'① 상위 시스템 계층 (Upstream Consumers)',{size:11,weight:700,fill:faint,ls:1}));
let x=M+16;
up.forEach(n=>{const w=[...n].reduce((a,c)=>a+(/[\u3131-\uD79D]/.test(c)?12:6.6),0)+28;
  o.push(r(x,Y_UP+40,w,34,'#ffffff',border,4));
  o.push(t(x+w/2,Y_UP+61,n,{size:11.5,fill:ink,anchor:'middle'})); x+=w+10;});

o.push(t(M+16,Y_UP+H_UP+26,'경계 ① (상위 경계): 작업 요구를 제시하고 협상하는 소비자 인터페이스 (consumer.kind: CLIENT | UPSTREAM_SYSTEM)',{size:11,fill:acc,weight:600}));

o.push(r(M,Y_MID,IW,H_MID,accFill,acc,10));
o.push(t(M+16,Y_MID+24,'② 코어 계약 및 거버넌스 레지스트리 (Core Contracts & Registry)',{size:11,weight:700,fill:acc,ls:1}));
const half=(IW-44)/2;
o.push(r(M+16,Y_MID+38,half,50,'#ffffff',acc,4));
o.push(t(M+16+half/2,Y_MID+59,'표준 계약 (Protobuf)',{size:12.5,weight:600,fill:ink,anchor:'middle'}));
o.push(t(M+16+half/2,Y_MID+77,'스킬 명세 · 수명주기 상태 · 메타데이터 헤더 (불변 코어 축)',{size:10.5,fill:muted,anchor:'middle'}));
o.push(r(M+28+half,Y_MID+38,half,50,'#ffffff',acc,4));
o.push(t(M+28+half+half/2,Y_MID+59,'거버넌스 레지스트리',{size:12.5,weight:600,fill:ink,anchor:'middle'}));
o.push(t(M+28+half+half/2,Y_MID+77,'기종 프로파일 · 어댑터 바인딩 · 소비자 접근 제어 원장',{size:10.5,fill:muted,anchor:'middle'}));

o.push(t(M+16,Y_MID+H_MID+26,'경계 ② (하위 경계): 프로파일을 선언하고 표준 계약을 이행하는 어댑터 (벤더 통신 및 기종별 의존성 완전 격리)',{size:11,fill:acc,weight:600}));

o.push(r(M,Y_DOWN,IW,H_DOWN,laneFill,laneStroke,10));
o.push(t(M+16,Y_DOWN+24,'③ 물리 기체 및 현장 설비 계층 (Physical Robots & Facility Devices)',{size:11,weight:700,fill:faint,ls:1}));
const cw=(IW-32-5*8)/6;
down.forEach(([v,m,note],i)=>{const cx=M+16+i*(cw+8);
  o.push(r(cx,Y_DOWN+38,cw,62,'#ffffff',border,4));
  o.push(t(cx+cw/2,Y_DOWN+55,v,{size:10,fill:faint,anchor:'middle'}));
  o.push(t(cx+cw/2,Y_DOWN+73,m,{size:12,weight:600,fill:ink,anchor:'middle'}));
  o.push(t(cx+cw/2,Y_DOWN+89,note,{size:9.5,fill:muted,anchor:'middle'}));});

o.push(r(M,FOOT_Y,IW,FOOT_H,laneFill,laneStroke));
o.push(t(M+20,FOOT_Y+23,'기종 확장 시에도 코어 계약은 불변 유지: 신규 기종 추가는 독립 프로파일 선언 및 전용 어댑터 모듈 구현만으로 완결',{size:11}));
o.push(t(M+20,FOOT_Y+42,'게이트 검증(Gate Check 7)이 공용 코어 8개 모듈의 의존성을 정적 분석하여 기종 전용 심볼 침투를 빌드 실패로 원천 차단',{size:11}));
o.push('</svg>');
writeFileSync(process.argv[2], o.join('\n')+'\n');
console.log(`${W}x${H}`);
