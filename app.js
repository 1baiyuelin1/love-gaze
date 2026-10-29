'use strict';

// Local prototype only: all activity is fictional. No real device APIs are used.
const viewer=document.body.dataset.viewer === 'champion' ? 'champion' : 'white';
const partnerId=viewer === 'white' ? 'champion' : 'white';
const screen=document.getElementById('screen');
const sheet=document.getElementById('sheet');
const toastElement=document.getElementById('toast');
const STORAGE_KEY='champion-autochat-v1';
let toastTimer;
let simulationActor=partnerId;
let syncAvailable=true;
const escapeHTML=value=>String(value).replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const NOW=16*3600+24*60;
const PLACES=[
  {id:'cafe',name:'湖畔咖啡',area:'湖滨路 26 号附近',line:'到了湖畔咖啡，咖啡因补给站已抵达。',x:226,y:133},
  {id:'home',name:'家',area:'梧桐街 · 小区附近',line:'到家了，外出模式已关闭。',x:73,y:191},
  {id:'park',name:'青川公园',area:'青川湖西侧',line:'到了青川公园，公园打卡成功。',x:185,y:93},
  {id:'studio',name:'工作室',area:'云杉路 12 号附近',line:'到了工作室，打工人已就位。',x:96,y:64},
];
const placeById=id=>PLACES.find(place=>place.id === id);
const clockText=(seconds,full=false)=>[Math.floor(seconds/3600)%24,Math.floor(seconds/60)%60,seconds%60].map(part=>String(part).padStart(2,'0')).slice(0,full ? 3 : 2).join(':');
const durationText=seconds=>seconds>=60 ? `${Math.floor(seconds/60)}分${seconds%60 ? `${seconds%60}秒` : ''}` : `${seconds}秒`;
function seedDevice(id) {
  const female=id === 'champion';
  return {id,battery:female ? 82 : 67,lastReport:NOW,
    current:{app:female ? 'cainiao' : 'deepseek',start:female ? 58970 : 58940},
    sessions:female ? [{app:'meituan',start:58960,end:58970},{app:'wechat',start:58900,end:58960},{app:'redbook',start:58500,end:58900},{app:'hongguo',start:57800,end:58500}] : [{app:'taobao',start:58810,end:58940},{app:'qq',start:58400,end:58810},{app:'jd',start:58000,end:58400}],
    moments:female ? [{type:'action',app:'redbook',action:'view_note',time:58600},{type:'place',place:'cafe',time:58870},{type:'action',app:'wechat',action:'message',time:58920},{type:'action',app:'cainiao',action:'pickup',time:59010}] : [{type:'place',place:'studio',time:56600},{type:'action',app:'qq',action:'call',time:58500},{type:'action',app:'deepseek',action:'ask',time:58990},{type:'place',place:'park',time:59025}],
    location:{place:female ? 'cafe' : 'park',time:female ? 58870 : 59025},idle:'desktop'};
}
const initialState=()=>({version:1,now:NOW,customApps:[],devices:{white:seedDevice('white'),champion:seedDevice('champion')}});
function loadState() {
  try {
    const saved=JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null');
    if(saved?.version === 1 && saved.devices?.white && saved.devices?.champion && Array.isArray(saved.customApps))return saved;
  } catch(error){syncAvailable=false;console.warn('无法读取本地演示状态',error);}
  return initialState();
}
let state=loadState();
const allApps=()=>[...BASE_APPS,...state.customApps];
const appById=id=>allApps().find(app=>app.id === id);
function saveState() {
  try {localStorage.setItem(STORAGE_KEY,JSON.stringify(state));syncAvailable=true;}
  catch(error){syncAvailable=false;console.warn('跨页面演示同步不可用',error);}
}
const BASE_APPS = [
  {id:'wechat',name:'微信',category:'社交通讯',color:'#36a86c',ink:'#ffffff',glyph:'chat'},
  {id:'qq',name:'QQ',category:'社交通讯',color:'#55a5da',ink:'#ffffff',mark:'QQ'},
  {id:'meituan',name:'美团',category:'生活服务',color:'#f4c449',ink:'#584411',mark:'美团'},
  {id:'taobao',name:'淘宝',category:'购物',color:'#f28649',ink:'#ffffff',mark:'淘'},
  {id:'jd',name:'京东',category:'购物',color:'#d95e64',ink:'#ffffff',mark:'JD'},
  {id:'redbook',name:'小红书',category:'生活分享',color:'#dd6c89',ink:'#ffffff',mark:'小红书'},
  {id:'douyin',name:'抖音',category:'短视频',color:'#403b49',ink:'#ffffff',glyph:'music'},
  {id:'hongguo',name:'红果',category:'短剧',color:'#ee815d',ink:'#ffffff',glyph:'fruit'},
  {id:'music',name:'网易云音乐',category:'音乐',color:'#b16e8d',ink:'#ffffff',glyph:'music'},
  {id:'browser',name:'浏览器',category:'工具',color:'#829aba',ink:'#ffffff',glyph:'globe'},
  {id:'bilibili',name:'哔哩哔哩',category:'视频',color:'#75acc4',ink:'#ffffff',mark:'哔'},
  {id:'alipay',name:'支付宝',category:'生活服务',color:'#719cdb',ink:'#ffffff',mark:'支'},
  {id:'amap',name:'高德地图',category:'出行',color:'#7899cd',ink:'#ffffff',glyph:'pin'},
  {id:'deepseek',name:'DeepSeek',category:'AI 问答',color:'#697ddb',ink:'#ffffff',mark:'DS'},
  {id:'doubao',name:'豆包',category:'AI 助手',color:'#85a6b2',ink:'#ffffff',mark:'豆'},
  {id:'zhihu',name:'知乎',category:'知识社区',color:'#588dce',ink:'#ffffff',mark:'知'},
  {id:'quark',name:'夸克浏览器',category:'浏览器',color:'#9380c4',ink:'#ffffff',mark:'Q'},
  {id:'camera',name:'相机',category:'拍摄',color:'#798697',ink:'#ffffff',glyph:'camera'},
  {id:'cainiao',name:'菜鸟驿站',category:'快递物流',color:'#61a4c6',ink:'#ffffff',mark:'菜'},
  {id:'baidudisk',name:'百度网盘',category:'云存储',color:'#80a2d5',ink:'#ffffff',glyph:'cloud'},
];
const icons = {
  camera:'<path d="m8 5 2-2h4l2 2h4a2 2 0 0 1 2 2v12H2V7a2 2 0 0 1 2-2Z"/><circle cx="12" cy="12" r="4"/>',
  cloud:'<path d="M6 19h12a4 4 0 0 0 .5-8 7 7 0 0 0-13-1A4.5 4.5 0 0 0 6 19Z"/><path d="m9 12 3-3 3 3m-3-3v8"/>',
  heart:'<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/>',
  home:'<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z"/><path d="M9 21v-8h6v8"/>',
  pin:'<path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/>',
  activity:'<path d="M3 12h4l3-8 4 16 3-8h4"/>',
  people:'<circle cx="9" cy="8" r="3.5"/><path d="M2 21v-3a7 7 0 0 1 14 0v3M17 5a3.5 3.5 0 0 1 0 7m2 3a6 6 0 0 1 3 5"/>',
  bell:'<path d="M18 8a6 6 0 0 0-12 0c0 7-3 8-3 9h18c0-1-3-2-3-9M10 21h4"/>',
  sun:'<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  shield:'<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z"/><path d="m8 12 3 3 5-6"/>',
  chevron:'<path d="m9 5 7 7-7 7"/>',
  arrow:'<path d="M5 12h14m-6-6 6 6-6 6"/>',
  expand:'<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/>',
  battery:'<rect x="2" y="6" width="17" height="12" rx="3"/><path d="M22 10v4M6 10v4m4-4v4m4-4v4"/>',
  wifi:'<path d="M2 8a16 16 0 0 1 20 0M5 12a11 11 0 0 1 14 0m-10 4a5 5 0 0 1 6 0"/><circle cx="12" cy="20" r=".5"/>',
  coffee:'<path d="M3 8h13v7a6 6 0 0 1-12 0V8m12 1h2a3 3 0 0 1 0 6h-2M2 22h17M7 2v2m5-2v2"/>',
  music:'<path d="M9 18V5l12-3v14M9 9l12-3"/><ellipse cx="6" cy="18" rx="3" ry="3"/><ellipse cx="18" cy="16" rx="3" ry="3"/>',
  edit:'<path d="m15 4 5 5M3 21l5-1L21 7a2 2 0 0 0-5-5L3 16Z"/>',
  pause:'<path d="M8 5v14M16 5v14"/>',
  play:'<path d="m7 3 14 9-14 9Z"/>',
  clock:'<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/>',
  close:'<path d="m6 6 12 12M6 18 18 6"/>',
  moon:'<path d="M21 13a9 9 0 1 1-10-10 7 7 0 0 0 10 10Z"/>',
  focus:'<path d="M8 3H4a1 1 0 0 0-1 1v4m13-5h4a1 1 0 0 1 1 1v4M3 16v4a1 1 0 0 0 1 1h4m13-5v4a1 1 0 0 1-1 1h-4"/><circle cx="12" cy="12" r="3"/>',
  walk:'<circle cx="14" cy="4" r="2"/><path d="m7 21 4-8m5 8-2-7-4-3 3-5m-7 6 3-4 4 1 4 4h4"/>',
  phone:'<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4"/>',
  check:'<path d="m5 12 4 4L20 5"/>',
  plus:'<path d="M12 5v14M5 12h14"/>',
  leaf:'<path d="M20 3S3 1 3 13a7 7 0 0 0 14 0Z"/><path d="M3 21 15 9"/>',
  info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.1"/>',
  chat:'<path d="M21 11a9 9 0 0 1-9 9 10 10 0 0 1-4-1l-5 2 1-5a9 9 0 1 1 17-5Z"/><path d="M8 11h.1m4 0h.1m4 0h.1"/>',
  route:'<circle cx="6" cy="6" r="3"/><circle cx="18" cy="18" r="3"/><path d="M9 6h7a4 4 0 0 1 0 8H8a4 4 0 0 0 0 8"/>',
  globe:'<circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18M5 6h14M5 18h14"/>',
  fruit:'<path d="M12 8c-9-5-12 10-5 13 3 1 4-1 5-1s2 2 5 1c7-3 4-18-5-13Z"/><path d="M12 8V4m0 2c0-4 3-5 7-4-1 4-4 5-7 4Z"/>',
  grid:'<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  swap:'<path d="M3 7h18m-5-5 5 5-5 5M21 17H3m5-5-5 5 5 5"/>',
};
const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.heart}</svg>`;

function avatar(person = viewer) {
  const partner = person === 'champion';
  return `<span class="avatar"><svg viewBox="0 0 80 80" role="img" aria-label="${person}的插画头像">
    <rect width="80" height="80" fill="${partner ? '#edd2d7' : '#dad6e6'}"/>
    <ellipse cx="41" cy="83" rx="30" ry="25" fill="${partner ? '#b25e7a' : '#7e7298'}"/>
    ${partner ? '<path d="M19 56V32C19 7 61 7 62 32v29Z" fill="#57443e"/>' : '<path d="M21 37C14 21 27 13 41 14c16-5 27 14 18 26Z" fill="#504c53"/>'}
    <path d="M34 50h14v15c-7 6-14 0-14 0Z" fill="#dfb198"/>
    <ellipse cx="40" cy="38" rx="18" ry="22" fill="#f4ceb4"/>
    ${partner ? '<path d="M22 34c1-25 34-26 37 0-7 0-14-5-19-12-4 7-10 10-18 12Z" fill="#57443e"/>' : '<path d="M22 32c0-20 33-25 38 0-6-2-9-5-11-9-7 8-19 10-27 9Z" fill="#504c53"/>'}
    <path d="M30 38h3m13 0h3" stroke="#685047" stroke-width="2.5" stroke-linecap="round"/>
    <path d="M36 48q5 4 9 0" fill="none" stroke="#b6726e" stroke-width="1.7" stroke-linecap="round"/>
    <ellipse cx="28" cy="44" rx="4" ry="2" fill="#eaa9a0" opacity=".7"/><ellipse cx="52" cy="44" rx="4" ry="2" fill="#eaa9a0" opacity=".7"/>
    ${partner ? '<circle cx="59" cy="28" r="4" fill="#e6b58b"/><path d="m29 67 10 10 12-10" fill="none" stroke="#dfadbd" stroke-width="2"/>' : '<path d="m30 64 10 8 11-8" fill="none" stroke="#b7adc9" stroke-width="3"/>'}
  </svg></span>`;
}

function mapArt() {
  const description = `虚构街区示意图`;
  return `<svg class="map-illustration" viewBox="0 0 360 260" preserveAspectRatio="xMidYMid slice" role="img" aria-label="${description}">
    <rect width="360" height="260" fill="#edefe6"/>
    <g fill="#e2e6da"><rect x="11" y="13" width="54" height="43" rx="5"/><rect x="76" y="12" width="65" height="41" rx="5"/><rect x="19" y="72" width="34" height="32" rx="4"/><rect x="70" y="73" width="68" height="36" rx="4"/><rect x="11" y="131" width="48" height="48" rx="4"/><rect x="78" y="130" width="43" height="34" rx="4"/><rect x="149" y="166" width="44" height="59" rx="5"/><rect x="143" y="73" width="32" height="39" rx="4"/><rect x="172" y="16" width="48" height="43" rx="5"/><rect x="263" y="191" width="45" height="39" rx="4"/><rect x="317" y="188" width="32" height="62" rx="5"/><rect x="18" y="198" width="43" height="53" rx="4"/></g>
    <path d="M365-8H260c-12 23-32 29-27 60s-30 43-9 78 76 20 77 61 46 41 66 29Z" fill="#cbded8"/>
    <path d="M257 0c-13 23-31 32-26 56s-26 40-8 74 83 24 80 66 46 40 58 29" fill="none" stroke="#b8d2c8" stroke-width="3"/>
    <path d="M188 84c15-22 29-14 33 2s-18 22-8 44 32 11 35 30-20 37-40 19-13-42-31-51 0-23 11-44" fill="#d9e5cb"/>
    <g fill="#c4d7b9"><circle cx="192" cy="87" r="7"/><circle cx="205" cy="105" r="6"/><circle cx="184" cy="114" r="5"/><circle cx="217" cy="149" r="8"/><circle cx="230" cy="165" r="6"/><circle cx="208" cy="171" r="6"/></g>
    <g class="road-edge"><path d="M-20 62 188 65M63-10l1 176 12 109M-20 123h193M135-5l-1 157 17 122M-10 191h154l41-34"/></g>
    <g class="road"><path d="M-20 62 188 65M63-10l1 176 12 109M-20 123h193M135-5l-1 157 17 122M-10 191h154l41-34"/></g>
    <g class="road minor"><path d="M10 0v116M94 0v52M23 153h99M20 224h49M100 201v68M164 3v61M270 174l81 11M317 195v55"/></g>
    <path d="M166-7c-9 39 9 65 3 92s-4 50 26 85 31 69 32 98" fill="none" stroke="#fcfbf1" stroke-width="8"/>
    <g class="street-label"><text x="84" y="67">知 行 路</text><text x="16" y="128">梧 桐 街</text><text x="72" y="211" transform="rotate(-90 72 211)">云 杉 路</text><text x="157" y="41">花园街区</text><text x="174" y="220">湖 滨 路</text></g>
    <text class="water-label" x="275" y="98" transform="rotate(15 275 98)">青 川 湖</text>
    <g fill="#bfccb6"><circle cx="33" cy="83" r="5"/><circle cx="116" cy="87" r="5"/><circle cx="29" cy="156" r="5"/></g>
  </svg>`;
}


function appBadge(app,extra='') {
  return `<span class="app-badge ${extra} app-${app.id}" style="--app-color:${app.color};--app-ink:${app.ink}" aria-hidden="true">${app.mark ? `<span class="app-mark ${app.mark.length>2 ? 'small-mark' : ''}">${escapeHTML(app.mark)}</span>` : icon(app.glyph)}</span>`;
}
function allMoments() {
  return Object.values(state.devices).flatMap(device=>{
    const sessions=[...(device.current ? [{...device.current,end:device.lastReport,ongoing:true}] : []),...device.sessions];
    return [...sessions.map(session=>({type:'app',app:session.app,time:session.start,end:session.end,ongoing:session.ongoing})),...device.moments].map(event=>({...event,owner:device.id,key:`${device.id}-${event.type}-${event.time}`}));
  }).sort((a,b)=>a.time-b.time || ({app:0,action:1,usage:2,place:3,status:4}[a.type]-{app:0,action:1,usage:2,place:3,status:4}[b.type]) || a.owner.localeCompare(b.owner));
}
function isPlainLog(event) {return event.app && !isPresetApp(appById(event.app));}
function logEventText(event) {
  if(event.type === 'app')return '打开应用';
  if(event.type === 'usage')return '前台持续使用';
  if(event.type === 'action')return `动作记录：${event.action}`;
  return event.status === 'locked' ? '设备锁屏，应用离开前台' : '离开应用';
}
function logFields(event) {
  const seconds=event.type === 'usage' ? event.seconds : event.type === 'app' ? event.end-event.time : null;
  return `<dl class="plain-log-fields"><div><dt>记录时间</dt><dd>${clockText(event.time,true)}</dd></div>${seconds !== null ? `<div><dt>前台时长</dt><dd>${durationText(seconds)}</dd></div>` : ''}</dl>`;
}
function momentCopy(event) {
  if(isPlainLog(event))return logEventText(event);
  if(event.type === 'app')return openedCopy(appById(event.app));
  if(event.type === 'action')return actionCopy(appById(event.app),event.action);
  if(event.type === 'usage')return `在${appById(event.app).name}待了${durationText(event.seconds)}，还没撤。`;
  if(event.type === 'place')return placeById(event.place).line;
  const app=event.app ? appById(event.app) : null;
  if(event.status === 'locked')return '手机锁屏了，屏幕先下班。';
  return app ? `离开了${app.name}，回桌面歇一会儿。` : '回到桌面，先歇一会儿。';
}
function momentLabel(event) {
  if(isPlainLog(event))return `${appById(event.app).name} · 应用日志`;
  if(event.type === 'app')return `${appById(event.app).name} · 已打开`;
  if(event.type === 'action')return `${appById(event.app).name} · ${actionFor(appById(event.app),event.action).label}`;
  if(event.type === 'usage')return `${appById(event.app).name} · 前台停留`;
  if(event.type === 'place')return '到了新地点';
  return event.status === 'locked' ? '手机锁屏' : '离开应用';
}
function momentBubble(event) {
  const own=event.owner === viewer;
  const place=event.type === 'place' ? placeById(event.place) : null;
  return `<article class="chat-message ${own ? 'from-me' : 'from-them'}" data-owner="${event.owner}" data-event-key="${event.key}" data-event-type="${event.type}">${avatar(event.owner)}<div class="message-column"><div class="message-sender"><b>${event.owner}</b><time>${clockText(event.time)}</time>${own ? '<span>我</span>' : ''}</div><button class="moment-bubble ${place ? 'place-bubble' : ''} ${event.type === 'action' ? 'action-bubble' : ''} ${isPlainLog(event) ? 'plain-log-bubble' : ''}" data-action="event-detail" data-event="${event.key}" aria-label="${event.owner}：${escapeHTML(momentCopy(event))} ${clockText(event.time)}，查看详情"><span class="bubble-label">${event.app && event.type !== 'status' ? appBadge(appById(event.app),'bubble-app') : icon(place ? 'pin' : 'moon')}${escapeHTML(momentLabel(event))}</span><span class="bubble-sentence">${escapeHTML(momentCopy(event))}</span>${isPlainLog(event) ? logFields(event) : ''}${place ? `<span class="bubble-place-card"><span class="place-line-art">${icon('pin')}</span><span><b>${place.name}</b><small>${place.area}</small></span>${icon('chevron')}</span>` : ''}</button></div></article>`;
}
function deviceNow(device) {
  const app=device.current ? appById(device.current.app) : null;
  return `<div class="chat-presence">${avatar(device.id)}<span><b>${device.id}<i class="presence-dot"></i></b><small>${app ? escapeHTML(app.name) : device.idle === 'locked' ? '已锁屏' : '桌面'} · ${placeById(device.location.place).name}</small></span></div>`;
}
function render(preserveScroll=false) {
  const previousLog=document.getElementById('chat-log');
  const previousTop=previousLog?.scrollTop || 0;
  const atBottom=!previousLog || previousLog.scrollHeight-previousLog.clientHeight-previousLog.scrollTop<60;
  const focusedEvent=document.activeElement?.dataset.event;
  screen.innerHTML=`<section class="diary-page"><header class="diary-header"><div class="topline"><div class="brand-block"><span class="brand-eyes"><i></i><i></i></span><span class="champion-word">champion</span></div><span class="fixed-identity">${avatar(viewer)}<span>${viewer} 的手机</span></span></div><div class="diary-title"><div><h1>咱俩在干啥</h1><p>你忙你的，新动作自动报到。</p></div><span class="always-sharing"><i></i>${syncAvailable ? '持续共享' : '本页演示'}</span></div><div class="presence-pair">${deviceNow(state.devices[partnerId])}${deviceNow(state.devices[viewer])}</div><div class="chat-stream-label"><span>${partnerId} 在左</span><span>全部 App · 位置动态</span><span>我在右</span></div></header><div class="chat-log" id="chat-log" role="log" aria-label="white 与 champion 的自动日常" tabindex="0"><div class="chat-day"><span>今天 · 9 月 15 日</span><small>动作自动记录</small></div>${allMoments().map(momentBubble).join('')}<div class="chat-tail">${icon('check')} 暂时就这些，待会儿接着播</div></div><footer class="diary-footer"><span class="update-dot"></span>${syncAvailable ? '新动作自动同步' : '仅本页演示 · 跨页面同步不可用'} · ${clockText(state.now,true)} 更新</footer></section>`;
  document.getElementById('demo-clock').textContent=clockText(state.now);
  const log=document.getElementById('chat-log');
  log.scrollTop=preserveScroll && !atBottom ? previousTop : log.scrollHeight;
  if(focusedEvent)screen.querySelector(`[data-event="${focusedEvent}"]`)?.focus({preventScroll:true});
}
function toast(message) {
  clearTimeout(toastTimer);toastElement.textContent=message;toastElement.classList.add('show');
  toastTimer=setTimeout(()=>{toastElement.classList.remove('show');toastElement.textContent='';},2800);
}
function closeSheet(){if(sheet.open)sheet.close();}
function openSheet(title,body) {
  sheet.innerHTML=`<div class="sheet-handle"></div><header class="dialog-head"><h2 id="sheet-title">${title}</h2><button data-action="close" aria-label="关闭弹窗">${icon('close')}</button></header><div class="sheet-body">${body}</div>`;
  if(!sheet.open)sheet.showModal();sheet.scrollTop=0;sheet.querySelector('button')?.focus({preventScroll:true});
}
const submitButton=(label,action)=>`<button class="sheet-primary" data-action="${action}">${label}</button>`;
function placePreview(place) {
  return `<div class="diary-map">${mapArt()}<span class="diary-map-pin" style="left:${place.x/360*100}%;top:${place.y/270*100}%">${icon('pin')}<b>${place.name}</b></span><span class="map-caption">虚构地点 · 演示地图</span></div>`;
}
function showMoment(key) {
  const event=allMoments().find(item=>item.key === key);if(!event)return;
  const copy=escapeHTML(momentCopy(event));
  const app=event.app ? appById(event.app) : null;
  let content='';
  if(isPlainLog(event))content=`<div class="detail-app-heading">${appBadge(app)}<div><h3>${escapeHTML(app.name)}</h3><p>应用日志</p></div></div><div class="plain-log-detail">${copy}${logFields(event)}</div><p class="subtle-note">来源：应用使用事件 · 演示。未取得的软件内部信息不显示。</p>`;
  else if(event.type === 'app')content=`<div class="detail-app-heading">${appBadge(app)}<div><h3>${escapeHTML(app.name)}</h3><p>${event.owner} 的打开记录</p></div></div><div class="moment-quote">${copy}</div><table class="data-table"><tbody><tr><td>打开时间</td><td>${clockText(event.time,true)}</td></tr><tr><td>${event.ongoing ? '最后上报' : '离开时间'}</td><td>${clockText(event.end,true)}</td></tr><tr><td>前台停留</td><td>${durationText(event.end-event.time)}</td></tr></tbody></table><p class="subtle-note">这条是应用打开记录。软件里的具体动作会另生成一条消息。</p>`;
  else if(event.type === 'action')content=`<div class="detail-app-heading">${appBadge(app)}<div><h3>${escapeHTML(actionFor(app,event.action).label)}</h3><p>${escapeHTML(app.name)} · ${event.owner}</p></div></div><div class="moment-quote">${copy}</div><table class="data-table"><tbody><tr><td>动作时间</td><td>${clockText(event.time,true)}</td></tr><tr><td>来源</td><td>动作确认信号 · 演示</td></tr><tr><td>分享内容</td><td>动作类型和时间</td></tr></tbody></table><p class="subtle-note">不包含聊天对象、消息正文、商品、视频名称或金额。真实动作识别尚未接入，打开 App 不会自动认定这些动作已经发生。</p>`;
  else if(event.type === 'usage')content=`<div class="detail-app-heading">${appBadge(app)}<div><h3>前台停留</h3><p>${escapeHTML(app.name)} · ${event.owner}</p></div></div><div class="moment-quote">${copy}</div><p>${clockText(event.time,true)} 更新 · 已记录 ${durationText(event.seconds)}<br>表示应用在前台的时长，不代表已识别内部操作。</p>`;
  else if(event.type === 'place')content=`${placePreview(placeById(event.place))}<div class="moment-quote">${copy}</div><p>${event.owner} · ${clockText(event.time,true)} 到达<br>${placeById(event.place).area} · 演示精度约 80 米</p>`;
  else content=`<div class="moment-quote">${copy}</div><p>${event.owner} · ${clockText(event.time,true)}</p>`;
  openSheet('动作详情',`${content}${submitButton('回到聊天','close')}`);
}
function beginApp(device,id) {
  if(device.current?.app === id)return false;
  if(device.current)device.sessions.unshift({...device.current,end:state.now});
  device.current={app:id,start:state.now};return true;
}
function publishDemo(message) {saveState();closeSheet();render();toast(message);}
function simulateApp(id) {
  if(!appById(id))return;
  const device=state.devices[simulationActor];state.now+=30;
  const changed=beginApp(device,id);device.lastReport=state.now;
  publishDemo(changed ? `${simulationActor} 的打开记录已同步` : '这个 App 已在前台，已更新停留时间');
}
function simulateUsage(id) {
  if(!appById(id))return;
  const device=state.devices[simulationActor];
  if(device.current?.app !== id){state.now+=30;beginApp(device,id);}
  state.now+=300;device.lastReport=state.now;
  device.moments.push({type:'usage',app:id,seconds:state.now-device.current.start,time:state.now});
  publishDemo(`${simulationActor} 的应用停留记录已同步`);
}
function simulateAction(id,actionId) {
  const app=appById(id);if(!app || !actionFor(app,actionId))return;
  const device=state.devices[simulationActor];state.now+=30;beginApp(device,id);device.lastReport=state.now;
  device.moments.push({type:'action',app:id,action:actionId,time:state.now});
  publishDemo(`${simulationActor} 的${actionFor(app,actionId).label}已同步`);
}
function simulateLocation(placeId) {
  const place=placeById(placeId);if(!place)return;
  const device=state.devices[simulationActor];
  if(device.location.place === placeId){toast(`${simulationActor} 已在${place.name}，不重复报到`);return;}
  state.now+=30;device.location={place:placeId,time:state.now};device.moments.push({type:'place',place:placeId,time:state.now});
  publishDemo(`${simulationActor} 的地点消息已同步`);
}
function simulateLeave(status) {
  const device=state.devices[simulationActor];state.now+=30;
  const app=device.current?.app;
  if(device.current)device.sessions.unshift({...device.current,end:state.now});
  device.current=null;device.idle=status;device.lastReport=state.now;
  device.moments.push({type:'status',status,app,time:state.now});
  publishDemo(`${simulationActor} 的设备动作已同步`);
}
function openSimulation() {
  openSheet('演示手机动作',`<span class="inline-demo">原型控制台 · 不属于正式聊天界面</span><p>模拟哪台手机发生了动作？这只影响演示事件的来源，当前手机始终是 ${viewer}。</p><div class="segmented simulation-people">${['champion','white'].map(id=>`<button data-sim-actor="${id}" aria-pressed="${id === simulationActor}">模拟 ${id} 的手机</button>`).join('')}</div><h3 class="section-title">App 和对应动作</h3><div class="simulate-grid">${allApps().map(app=>`<button data-action="app-actions" data-app="${app.id}">${appBadge(app)}<span>${escapeHTML(app.name)}</span></button>`).join('')}<button class="other-app" data-action="other-app">${icon('plus')}<span>其他 App</span></button></div><p class="subtle-note">所有 App 自动记录。预设软件显示对应动作，其他软件按日志展示。</p><h3 class="section-title">移动到了</h3><div class="movement-choices">${PLACES.map(place=>`<button data-move="${place.id}">${icon('pin')}<span>${place.name}</span></button>`).join('')}</div><div class="location-actions"><button class="pill-btn" data-leave="desktop">${icon('home')}回到桌面</button><button class="pill-btn" data-leave="locked">${icon('moon')}锁屏</button></div><button class="reset-preview demo-reset" data-action="reset">重置两台演示设备</button>`);
}
function openAppActions(id) {
  const app=appById(id);if(!app)return;
  const profile=behaviorFor(app);
  openSheet(`${escapeHTML(app.name)}的${isPresetApp(app) ? '动作' : '日志'}`, `<span class="inline-demo">正在模拟 ${simulationActor} 的手机</span><div class="detail-app-heading">${appBadge(app)}<div><h3>${escapeHTML(app.name)}</h3><p>自动记录 · 无需逐 App 勾选</p></div></div><div class="app-action-choices"><button data-open-app="${app.id}"><span class="action-row-icon">${icon('phone')}</span><span><b>打开应用</b><small>${escapeHTML(openedCopy(app))}</small></span>${icon('chevron')}</button><button data-usage-app="${app.id}"><span class="action-row-icon">${icon('clock')}</span><span><b>持续使用 5 分钟</b><small>按实际前台时长生成一条停留消息。</small></span>${icon('chevron')}</button>${profile.actions.map(action=>`<button data-confirm-action="${action.id}" data-app="${app.id}"><span class="action-row-icon">${icon('activity')}</span><span><b>${action.label}</b><small>${escapeHTML(actionCopy(app,action.id))}</small></span>${icon('chevron')}</button>`).join('')}<button data-app-leave="${app.id}"><span class="action-row-icon">${icon('home')}</span><span><b>离开应用</b><small>${isPresetApp(app) ? `离开了${escapeHTML(app.name)}，回桌面歇一会儿。` : `离开应用：${escapeHTML(app.name)}`}</small></span>${icon('chevron')}</button></div><p class="subtle-note">${profile.actions.length ? '这些是不同事件的演示。' : '这个软件按日志显示打开、停留和离开，不添加趣味文案。'}真实内部动作需要对应数据来源；打开应用只产生打开记录。</p><button class="simulate-entry" data-action="demo">返回演示控制台</button>`);
}
const actions={
  close:closeSheet,
  demo:openSimulation,
  'event-detail':button=>showMoment(button.dataset.event),
  'app-actions':button=>openAppActions(button.dataset.app),
  'other-app':()=>openSheet('模拟其他 App',`<p>输入应用名，体验未预设软件的日志消息。显示实际记录字段，不添加趣味描述。</p><label class="form-label" for="other-app-name">演示应用名称</label><input class="text-input" id="other-app-name" maxlength="20" placeholder="例如：番茄小说"><p id="other-app-error" class="inline-error" role="alert"></p>${submitButton('查看日志演示','create-demo-app')}`),
  'create-demo-app':()=>{
    const name=sheet.querySelector('#other-app-name').value.trim();if(!name){sheet.querySelector('#other-app-error').textContent='先填一个应用名。';return;}
    let app=allApps().find(item=>item.name.toLowerCase() === name.toLowerCase());
    if(!app){app={id:`custom${state.customApps.length}`,name,category:'未预设应用',color:'#9484b2',ink:'#ffffff',glyph:'grid'};state.customApps.push(app);saveState();}
    openAppActions(app.id);
  },
  reset:()=>{state=initialState();publishDemo('两台演示设备已重置');},
};
document.addEventListener('click',event=>{
  const button=event.target.closest('button');if(!button || button.disabled)return;
  if(button.dataset.simActor){simulationActor=button.dataset.simActor;openSimulation();return;}
  if(button.dataset.openApp){simulateApp(button.dataset.openApp);return;}
  if(button.dataset.usageApp){simulateUsage(button.dataset.usageApp);return;}
  if(button.dataset.confirmAction){simulateAction(button.dataset.app,button.dataset.confirmAction);return;}
  if(button.dataset.move){simulateLocation(button.dataset.move);return;}
  if(button.dataset.leave){simulateLeave(button.dataset.leave);return;}
  if(button.dataset.appLeave){const device=state.devices[simulationActor];if(device.current?.app !== button.dataset.appLeave){toast('请先模拟打开这个 App，再离开');return;}simulateLeave('desktop');return;}
  actions[button.dataset.action]?.(button);
});
sheet.addEventListener('click',event=>{
  if(event.target !== sheet)return;const rect=sheet.getBoundingClientRect();
  if(event.clientX<rect.left || event.clientX>rect.right || event.clientY<rect.top || event.clientY>rect.bottom)closeSheet();
});
window.addEventListener('storage',event=>{
  if(event.key !== STORAGE_KEY)return;
  state=loadState();render(true);
});
saveState();render();
