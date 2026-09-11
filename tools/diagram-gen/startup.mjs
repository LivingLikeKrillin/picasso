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

const steps=['프로파일 로드','스키마 검증','오버라이드 적용','Capability 투영','상태머신','session_id 발급','포트 개방','ONLINE 발행'];

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
o.push(t(32,46,'부분 기동은 없다',{size:18,weight:600,fill:ink}));
o.push(t(32,72,'한 프로세스가 여러 기체를 호스팅하지만, 그중 하나의 프로파일이 검증에서 걸리면 전부 뜨지 않는다.',{size:12.5}));
o.push(t(32,93,'능력을 모르는 채로는 표면을 열지 않는다. 레지스트리가 기동 시점에 불통이어도 같은 판단을 한다.',{size:12.5}));

o.push(t(M,ROW_Y-14,'기체마다 이 여덟을 지난다',{size:10,weight:700,fill:faint,ls:1}));
steps.forEach((s,i)=>{
  const x=M+i*(NW+GAPX);
  o.push(r(x,ROW_Y,NW,NODE_H,nodeFill,border));
  o.push(t(x+NW/2,ROW_Y+29,s,{size:10.5,weight:600,fill:ink,anchor:'middle'}));
  if(i<steps.length-1) o.push(arrow([[x+NW,ROW_Y+NODE_H/2],[x+NW+GAPX,ROW_Y+NODE_H/2]],muted));
  // 어느 칸에서 걸려도 같은 곳으로 간다 — 그것이 이 그림의 주장이다.
  o.push(arrow([[x+NW/2,ROW_Y+NODE_H],[x+NW/2,REJ_Y]],danger,'ad'));
});

o.push(r(M,REJ_Y,W-2*M,REJ_H,dangerFill,danger));
o.push(t(M+20,REJ_Y+25,'하나라도 걸리면 기동 거부 — 프로세스 전체가 뜨지 않는다',{size:13,weight:600,fill:ink}));
o.push(t(M+20,REJ_Y+45,'걸린 기체 하나가 아니라 전부다. 거부 메시지는 어느 기체가 왜 걸렸는지를 적는다.',{size:11,fill:danger}));

o.push(r(M,FOOT_Y,W-2*M,FOOT_H,laneFill,laneStroke));
o.push(t(M+20,FOOT_Y+23,'부분 기동이 소비자에게 거짓말인 이유 — 없는 기체를 물으면 NOT_FOUND 가 나오는데,',{size:11}));
o.push(t(M+20,FOOT_Y+42,'설정에 없는 것인지 프로파일이 깨진 것인지 소비자가 구별할 방법이 없다.',{size:11}));
o.push(t(M+20,FOOT_Y+61,'mimic · Main.kt — 「기체 하나가 거부되면 전부 기동하지 않는다」',{size:10,fill:faint}));
o.push('</svg>');
writeFileSync(process.argv[2], o.join('\n')+'\n');
console.log(`${W}x${H}`);
