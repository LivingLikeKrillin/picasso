// 간선과 칸을 **빌드 파일에서 읽어** 그린다. 손으로 적으면 어느 날 그림만 낡는다.
import { writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const W = Number(process.argv[3] || 1000), M = 24;
const ink='#1f2328', muted='#57606a', faint='#8b95a1', border='#d0d7de',
      nodeFill='#f6f8fa', laneFill='#fbfcfd', laneStroke='#e6e9ee',
      acc='#0969da', accFill='#ddf4ff', ok='#1a7f37', okFill='#eaf3ea';
const esc=s=>s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
const t=(x,y,s,o={})=>{const{size=11,fill=muted,weight=400,anchor='start',ls=null}=o;
  return `  <text x="${x}" y="${y}" font-size="${size}"${weight!==400?` font-weight="${weight}"`:''}`+
  `${anchor!=='start'?` text-anchor="${anchor}"`:''}${ls?` letter-spacing="${ls}"`:''} fill="${fill}">${esc(s)}</text>`;};
const r=(x,y,w,h,fill,stroke,rx=6)=>`  <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}" fill="${fill}"${stroke?` stroke="${stroke}"`:''}/>`;
const poly=(pts,stroke)=>`  <polyline points="${pts.map(p=>p.join(',')).join(' ')}" fill="none" stroke="${stroke}" stroke-width="1.5" marker-end="url(#am)"/>`;

// ── 빌드에서 출하 의존을 읽는다 (DocumentClaimsTest 와 같은 규칙)
const ROOT = process.argv[4] || '.';
const SHIP = new Set(['api','implementation','compileOnly','runtimeOnly']);
const mods = [...new Set([...readFileSync(join(ROOT,'settings.gradle.kts'),'utf8')
  .split('rootProject.name')[1].matchAll(/"([a-z0-9-]+)"/g)].map(m=>m[1]))].sort();
const graph = {};
for (const m of mods) {
  const f = join(ROOT, m, 'build.gradle.kts');
  if (!existsSync(f)) continue;
  const s = readFileSync(f,'utf8');
  graph[m] = [...new Set([...s.matchAll(/^\s*(\w+)\s*\(\s*(?:testFixtures\s*\(\s*)?project\("[:]([a-z0-9-]+)"\)/gm)]
    .filter(x=>SHIP.has(x[1])).map(x=>x[2]))].sort();
}
const ROOTS = ['contracts','profile-model'];
const VENDOR3 = ['adapter-unitree-g1','adapter-boston-dynamics-spot','adapter-agility-digit'];
const GROUP = 'adapter-<기종> 셋';

// 그리는 간선 = 바닥 둘로 가는 것을 뺀 나머지. 기종 어댑터 셋은 한 칸으로 묶는다.
const drawn = [];
for (const [m, deps] of Object.entries(graph)) {
  const from = VENDOR3.includes(m) ? GROUP : m;
  for (const d of deps) {
    if (ROOTS.includes(d)) continue;
    const to = VENDOR3.includes(d) ? GROUP : d;
    if (from === to) continue;
    if (!drawn.some(([a,b])=>a===from&&b===to)) drawn.push([from,to]);
  }
}
const hidden = Object.values(graph).flat().filter(d=>ROOTS.includes(d)).length;

const rows = [
  ['harness',null,null,null,'adapter-boston-dynamics-orbit'],  // 빈 칸은 자리만 잡는다 — 채널까지의 거리를 줄인다
  ['picasso','mimic','registry','adapter-host',GROUP],
  ['client','capability','uplink','gate','adapter-core'],
];
const KNOWS = new Set([GROUP,'adapter-boston-dynamics-orbit']); // 기종을 아는 자리

const ROW_Y=[132,236,340], NH=58, ROW_GAP=10;
const BASE_Y=444, BASE_H=70;
const FOOT_Y=BASE_Y+BASE_H+20, FOOT_H=76;
const H=FOOT_Y+FOOT_H+22;
// ★양옆에 채널을 남긴다. 한 줄을 건너뛰는 간선이 가운데 줄을 관통하지 않게 하는 유일한 방법이다.
const CH = 34;
const box={}, rowOf={};
rows.forEach((row,ri)=>{
  const n=row.length, avail=W-2*M-2*CH, w=(avail-(n-1)*ROW_GAP)/n;
  row.forEach((name,i)=>{ if(!name) return; box[name]={x:M+CH+i*(w+ROW_GAP), y:ROW_Y[ri], w, h:NH}; rowOf[name]=ri; });
});
const LCH=M+CH/2, RCH=W-M-CH/2;

const o=[];
o.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="Malgun Gothic,Apple SD Gothic Neo,Noto Sans KR,Segoe UI,-apple-system,Helvetica,Arial,sans-serif">`);
o.push('  <defs>');
o.push(`    <marker id="am" markerWidth="8" markerHeight="8" refX="6.5" refY="2.75" orient="auto"><path d="M0,0 L6.5,2.75 L0,5.5 Z" fill="${muted}"/></marker>`);
o.push('  </defs>');
// ★시험이 읽는다. 그린 것과 같은 배열에서 나오되, **표시 라벨이 아니라 실제 모듈 이름**으로 편다 —
// 그래야 시험이 묶음 규칙을 한 벌도 안 옮긴다.
const real = n => n === GROUP ? VENDOR3 : [n];
drawn.forEach(([a,b])=>real(a).forEach(f=>real(b).forEach(t=>{
  if (f !== t && (graph[f]||[]).includes(t)) o.push(`  <!-- edge: ${f} -> ${t} -->`);
})));
o.push(r(6,6,W-12,H-12,'#ffffff',border,14));
o.push(t(32,46,'기종을 아는 모듈은 넷뿐이다',{size:18,weight:600,fill:ink}));
o.push(t(32,72,`모듈 ${Object.keys(graph).length} 개. 화살표는 «쓴다» 이고, 아래로만 간다 — 순환이 없다.`,{size:12.5}));
o.push(t(32,93,'바닥 둘은 프로젝트 안의 무엇도 안 쓴다. 거기로 가는 화살표 ' + hidden + ' 개는 안 그렸다.',{size:12.5}));

drawn.forEach(([a,b])=>{
  const A=box[a], B=box[b]; if(!A||!B) return;
  const x1=A.x+A.w/2, y1=A.y+A.h, x2=B.x+B.w/2, y2=B.y;
  if (rowOf[b] - rowOf[a] > 1) {
    // 줄을 건너뛴다 — 가장자리 채널로 돌아간다. 왼쪽에서 난 것은 왼쪽, 오른쪽은 오른쪽.
    const ch = (x1 < W/2) ? LCH : RCH;
    o.push(poly([[x1,y1],[x1,y1+14],[ch,y1+14],[ch,y2-14],[x2,y2-14],[x2,y2]],muted));
  } else {
    const mid=(y1+y2)/2;
    o.push(poly(x1===x2?[[x1,y1],[x2,y2]]:[[x1,y1],[x1,mid],[x2,mid],[x2,y2]],muted));
  }
});

rows.flat().filter(Boolean).forEach(name=>{
  const b=box[name], knows=KNOWS.has(name);
  o.push(r(b.x,b.y,b.w,b.h,knows?okFill:nodeFill,knows?ok:border));
  const short=name.replace('adapter-boston-dynamics-','adapter-');
  o.push(t(b.x+b.w/2,b.y+26,short,{size:11.5,weight:600,fill:ink,anchor:'middle'}));
  if(name===GROUP) o.push(t(b.x+b.w/2,b.y+44,'g1 · spot · digit',{size:9.5,fill:ok,anchor:'middle'}));
  else if(knows) o.push(t(b.x+b.w/2,b.y+44,'플릿 · 기종을 안다',{size:9.5,fill:ok,anchor:'middle'}));
});

o.push(r(M+CH,BASE_Y,W-2*M-2*CH,BASE_H,accFill,acc,10));
o.push(t(M+CH+16,BASE_Y+24,'바닥 — 프로젝트 내 의존 0',{size:11,weight:700,fill:acc,ls:1}));
const bw=(W-2*M-2*CH-44)/2;
ROOTS.forEach((n,i)=>{
  const x=M+CH+16+i*(bw+12);
  o.push(r(x,BASE_Y+34,bw,26,'#ffffff',acc,4));
  o.push(t(x+bw/2,BASE_Y+52,n,{size:11.5,weight:600,fill:ink,anchor:'middle'}));
});

o.push(r(M,FOOT_Y,W-2*M,FOOT_H,laneFill,laneStroke));
o.push(t(M+20,FOOT_Y+23,'초록 둘만 기종 이름을 안다. 나머지에는 벤더·모델 문자열이 들어오면 안 되고,',{size:11}));
o.push(t(M+20,FOOT_Y+42,'게이트 검사 7번이 그 모듈들의 소스를 훑어 CI 실패 조건으로 막는다 — 주석이라도 걸린다.',{size:11}));
o.push(t(M+20,FOOT_Y+61,'이 그림의 칸과 화살표는 각 build.gradle.kts 에서 읽어 그린다. ComponentMapTest 가 같은 것을 다시 읽어 댄다.',{size:10,fill:faint}));
o.push('</svg>');
writeFileSync(process.argv[2], o.join('\n')+'\n');
console.log(`${W}x${H} · 칸 ${rows.flat().filter(Boolean).length}+2 · 그린 간선 ${drawn.length} · 숨긴 간선 ${hidden}`);
