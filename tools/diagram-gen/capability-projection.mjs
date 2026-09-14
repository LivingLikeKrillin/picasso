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
  ['vendor · model · profile_revision', '기종 식별 및 활성 프로파일 버전 확인'],
  ['skills[] (major.minor)', '지원 스킬 목록 및 SemVer 호환성 판정 (SKILL_ABSENT 검출)'],
  ['skills[].pause_support · cancel_support', '일시 정지 및 취소 제어 가능 여부 결정'],
  ['exclusive_control_required', '작업 전 독점 제어권(Lease) 선점 필수 여부 판정'],
  ['skills[].parameters', '사전 파라미터 유효성 검증 (클라이언트 측 검증)'],
  ['optional_fields', '선택적 필드 지원 검증 (REQUIRED_OPTIONAL_MISSING 방지)'],
  ['publish_interval', '원격 측정 하트비트 타임아웃 및 장애 판정 기준'],
  ['protocol_limits', '메시지 크기/속도 제한 (LIMIT_EXCEEDED 방지)'],
];
const stay = [
  ['schema_version', '프로파일 문서 자체의 메타데이터 (기체 능력과 무관)'],
  ['derived_from', '벤더 원문 문서 출처 및 작성자 감사용 추적 정보'],
  ['durations', '시뮬레이터용 소요 시간/지터 (소비자는 런타임 진행률 수신)'],
  ['failure_modes', '가상화 에뮬레이터 전용 주입 오류 설정'],
  ['replay_buffer_size', '발신자(에뮬레이터/어댑터) 내부 버퍼 리소스 설정'],
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
o.push(t(32,46,'프로파일과 Capability 투영 경계 (Projection Boundary)',{size:18,weight:600,fill:ink}));
o.push(t(32,72,'프로파일 문서 전체가 외부에 노출되지 않으며, 소비자의 실행 판정에 필요한 필드만 Capability로 정제 투영됩니다.',{size:12.5}));
o.push(t(32,93,'프로파일 최상위 13개 필드 중 8개가 gRPC Capability 메시지로 투영되고, 5개는 내부 메타 설정으로 유지됩니다.',{size:12.5}));

o.push(r(M,CRIT_Y,W-2*M,CRIT_H,accFill,acc));
o.push(t(M+20,CRIT_Y+23,'투영 기준: 소비자의 런타임 제어 및 적합성 판단에 필수적인가',{size:12.5,weight:600,fill:ink}));
o.push(t(M+20,CRIT_Y+42,'해당 원칙이 Capability 투영 함수의 설계 규격이며, 에뮬레이터/어댑터는 계약에 부합하는 필드만을 직렬화합니다.',{size:11,fill:acc}));

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
col(M,'Capability 투영 필드 (8종)','소비자 호환성 판정 및 사전 검증에 사용',out,ok,okFill);
col(RX2,'프로파일 전용 내부 필드 (5종)','엔진 내부 동작 및 에뮬레이션 전용 설정',stay,faint,laneFill);

o.push(r(M,FOOT_Y,W-2*M,FOOT_H,laneFill,laneStroke));
o.push(t(M+20,FOOT_Y+23,'동일한 경계가 이중으로 보호됩니다. 런타임 gRPC 메시지 스키마에서 내부 필드가 배제되며,',{size:11}));
o.push(t(M+20,FOOT_Y+42,'CI 파이프라인(게이트 6번)이 투영 목록을 기반으로 능력 축소 및 호환성 파괴를 정적 검증합니다.',{size:11}));
o.push('</svg>');
writeFileSync(process.argv[2], o.join('\n')+'\n');
console.log(`${W}x${H}`);
