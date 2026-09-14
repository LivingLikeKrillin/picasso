import { writeFileSync } from 'node:fs';
const W = Number(process.argv[3] || 1000), M = 24;
const ink='#1f2328', muted='#57606a', faint='#8b95a1', border='#d0d7de',
      nodeFill='#f6f8fa', laneFill='#fbfcfd', laneStroke='#e6e9ee',
      danger='#cf222e', dangerFill='#ffebe9';
const esc=s=>s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
const t=(x,y,s,o={})=>{const{size=11,fill=muted,weight=400,anchor='start',ls=null}=o;
  return `  <text x="${x}" y="${y}" font-size="${size}"${weight!==400?` font-weight="${weight}"`:''}`+
  `${anchor!=='start'?` text-anchor="${anchor}"`:''}${ls?` letter-spacing="${ls}"`:''} fill="${fill}">${esc(s)}</text>`;};
const r=(x,y,w,h,fill,stroke,rx=6)=>`  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"${stroke?` stroke="${stroke}"`:''}/>`;
const arrow=(pts,stroke,marker='am')=>`  <polyline points="${pts.map(p=>p.join(',')).join(' ')}" fill="none" stroke="${stroke}" stroke-width="1.6" marker-end="url(#${marker})"/>`;

const steps=['프로파일 로드','스키마 검증','오버라이드 적용','Capability 투영','상태 머신 초기화','세션 ID 발급','gRPC 포트 바인딩','ONLINE 이벤트 발행'];

const ROW_Y=136, NODE_H=50, GAPX=14;
const NW=Math.round((W-2*M-(steps.length-1)*GAPX)/steps.length);
const REJ_Y=ROW_Y+NODE_H+64, REJ_H=62;
const FOOT_Y=REJ_Y+REJ_H+20, FOOT_H=76;
const H=FOOT_Y+FOOT_H+22;

const o=[];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push('  <defs>');
o.push(`    <marker id="am" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="${muted}"/></marker>`);
o.push(`    <marker id="ad" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 Z" fill="${danger}"/></marker>`);
o.push('  </defs>');
o.push(r(6,6,W-12,H-12,'#ffffff',border,14));
o.push(t(32,46,'원자적 기동 시퀀스와 전체 실패(Fail-Fast) 불변식',{size:18,weight:600,fill:ink}));
o.push(t(32,72,'단일 프로세스가 복수 기체를 호스팅하더라도, 단 하나의 기체 프로파일이라도 검증 실패 시 전체 기동을 즉시 중단합니다.',{size:12.5}));
o.push(t(32,93,'기체 능력이 완전히 검증되지 않은 불완전한 상태에서는 통신 엔드포인트를 노출하지 않으며(Fail-Closed), 레지스트리 불통 시에도 동일 원칙 적용',{size:12.5}));

o.push(t(M,ROW_Y-14,'기체별 8단계 순차 부트스트랩 파이프라인',{size:10,weight:700,fill:faint,ls:1}));
steps.forEach((s,i)=>{
  const x=M+i*(NW+GAPX);
  o.push(r(x,ROW_Y,NW,NODE_H,nodeFill,border));
  o.push(t(x+NW/2,ROW_Y+29,s,{size:10.5,weight:600,fill:ink,anchor:'middle'}));
  if(i<steps.length-1) o.push(arrow([[x+NW,ROW_Y+NODE_H/2],[x+NW+GAPX,ROW_Y+NODE_H/2]],muted));
  // 어느 칸에서 걸려도 같은 곳으로 간다 — 그것이 이 그림의 주장이다.
  o.push(arrow([[x+NW/2,ROW_Y+NODE_H],[x+NW/2,REJ_Y]],danger,'ad'));
});

o.push(r(M,REJ_Y,W-2*M,REJ_H,dangerFill,danger));
o.push(t(M+20,REJ_Y+25,'단일 단계 검증 실패 시 기동 전면 거부 (Fail-Fast: 프로세스 중단)',{size:13,weight:600,fill:ink}));
o.push(t(M+20,REJ_Y+45,'특정 기체의 검증 실패는 전체 프로세스 기동 실패로 전파되며, 실패 원인과 해당 기체 식별자를 상세 로그로 기록',{size:11,fill:danger}));

o.push(r(M,FOOT_Y,W-2*M,FOOT_H,laneFill,laneStroke));
o.push(t(M+20,FOOT_Y+23,'부분 기동 금지 사유: 일부 기체만 정상 기동할 경우 소비자 관점에서 원인 규명 모호성 발생 (설정 미등록과 검증 실패의 구분 불가)',{size:11}));
o.push(t(M+20,FOOT_Y+42,'NOT_FOUND 에러 반환 시 단순 미등록 기체인지 부트스트랩 실패 기체인지 판별 불가능하므로 완전 거부 정책 준수',{size:11}));
o.push(t(M+20,FOOT_Y+61,'mimic :: Main.kt — 원자적 부트스트랩 불변식: "단일 기체 실패 시 전체 기동 중단"',{size:10,fill:faint}));
o.push('</svg>');
writeFileSync(process.argv[2], o.join('\n')+'\n');
console.log(`${W}x${H}`);
