const assert = require('node:assert/strict');
const {createCanvas} = require('@napi-rs/canvas');
const {readFileSync,writeFileSync} = require('node:fs');
const vm = require('node:vm');
const elements = JSON.parse(readFileSync(__dirname+'/dom.json','utf8'));
let src = readFileSync(__dirname+'/../app/src/main/assets/index.html','utf8').split('<script>')[1].split('</script>')[0];
src = src.replace('      window.onload = () => {', `      window.testing = {
  terrain:()=>solidMask, WORLD_W, isSolid, crumbleTankSupport, net, state, config, render, resizeCanvas, enterRoom,roomCommand,pollNow:()=>poll(session),sent:()=>session.out,session:()=>session, stepPhysics, fireWeapon,
  selectWeapon, aimAtWorld, toggleOverview, finishLanTurn, applyTankDamage, carveTerrain, startNewGame, getWeapon, shotSpeed, traceBeam, fireBeam, onProjectileImpact, chooseComputerShot, traceShot,
  collapseTerrain,prefs,applyControls,battleFinished,upgradeStep,endGame,survivors,prepareAITurn,refreshRooms,receiveLanAction,sendNet,handleRoomFailure,timeoutWinner,expireMatch,observeFrame,renderScale,economicQuality,effectCount,
  mask: () => {
    let hash=2166136261;
    for (const value of solidMask) hash=Math.imul(hash ^ value,16777619);
    return hash >>> 0;
  }
};
      window.onload = () => {`);
class Classes extends Set {
  remove(v) {this.delete(v)}
  contains(v) {return this.has(v)}
  toggle(v, on=!this.has(v)) {on?this.add(v):this.delete(v);return on}
}
function domNode(tag='div',attrs={},text='') {
  const node = tag==='canvas'?createCanvas(Number(attrs.width||300),Number(attrs.height||150)):{};
  const listeners={};
  Object.assign(node, {
    tagName:tag.toUpperCase(), id:attrs.id, textContent:text, style:{setProperty(k,v){this[k]=v}}, disabled:attrs.disabled!==undefined,
    hidden:attrs.hidden!==undefined, value:'', dataset:{mode:attrs['data-mode'],theme:attrs['data-theme']},
    classList:new Classes(attrs.class||[]), setAttribute(k,v){this[k]=v}, addEventListener(k,f){listeners[k]=f},
    children:[],replaceChildren(...items){this.children=items;}, appendChild(item){this.children.push(item);}, setPointerCapture(){}, getBoundingClientRect(){return {left:0,top:0}},
    click(){if(!this.disabled)return listeners.click?.({})},
    dispatch(k,e){return listeners[k]?.(e)}
  });
  return node;
}
let now=0;
function page(w,h) {
  const byId=new Map();
  const options=[];
  let netRequests=0;
  const document = {body:{classList:new Classes()},documentElement:{outerHTML:'',style:{setProperty(){}}},
    getElementById(id){assert.ok(byId.has(id),id);return byId.get(id)},
    querySelectorAll(sel){
      if(sel==='#modeOptions .opt-btn')return options.filter(i=>i.dataset.mode);
      if(sel==='#themeOptions .opt-btn')return options.filter(i=>i.dataset.theme);
      if(sel==='.dropdown-panel')return [...byId.values()].filter(n=>n.classList.contains('dropdown-panel'));
      return [];
    },
    querySelector(){return [...byId.values()].find(n=>n.classList.contains('modal-backdrop')&&!n.classList.contains('hidden'));},
    createElement:domNode,addEventListener(){},
    head:{append(){netRequests++}}
  };
  for (const {tag,attrs,text} of elements) {
    const node=domNode(tag,attrs,text);
    if (attrs.id) byId.set(attrs.id,node);
    if (node.classList.contains('opt-btn'))options.push(node);
  }
  const canvas=document.getElementById('gameCanvas');
  const hero=document.getElementById('heroCanvas');
  Object.defineProperties(canvas,{clientWidth:{get:()=>sandbox.innerWidth},clientHeight:{get:()=>sandbox.innerHeight}});
  Object.assign(hero,{clientWidth:w>h?260:w-24,clientHeight:w>h?276:164});
  const sandbox = {location:{protocol:"file:",hostname:""},crypto:require("crypto").webcrypto,document,devicePixelRatio:1,performance:{now:()=>now},innerWidth:w,innerHeight:h,
    AbortController,localStorage:{getItem(){return null},setItem(){}},matchMedia:()=>({matches:false}), ResizeObserver:class {observe(){}},
    navigator:{}, AudioContext: null,
    addEventListener(){},requestAnimationFrame:()=>1,cancelAnimationFrame(){},
    fetch:async(route,opts)=>{
      const response=await fetch(`http://127.0.0.1:${process.env.TANK_TEST_PORT}`+route,opts);
      if(sandbox.dropNextPacketReply && route==='/room/packet'){sandbox.dropNextPacketReply=false;throw Error('simulated lost response after server accepted request');}
      return response;
    },
    setTimeout:(fn,ms)=>[75,320].includes(ms)?1:setTimeout(fn,ms),clearTimeout,setInterval:()=>1,clearInterval(){},
    btoa,atob,console};
  sandbox.window=sandbox;
  vm.runInNewContext(src,sandbox);
  for(const [id,value] of [['health','100'],['slots','2'],['seatCount','2'],['battleFormat','free'],['seriesLength','1']])byId.get(id).value=value;byId.get('trajectoryOption').checked=true;
  sandbox.onload();
  return {api:sandbox.testing,el:id=>document.getElementById(id),options,sandbox,netRequests:()=>netRequests};
}
const frames=(pages,n=1)=>{for(let i=0;i<n;i++){now+=1000/60;pages.forEach(p=>p.api.stepPhysics())}};

(async()=>{const host=page(844,390),guest=page(844,390);await host.api.enterRoom('create');guest.el('roomCode').value=host.api.session().code;await guest.api.enterRoom('join');await host.api.roomCommand('decision',{target:1,accept:true});await guest.api.roomCommand('ready');await host.api.roomCommand('start');await host.api.pollNow();await guest.api.pollNow();
const a=guest.api;a.state.activeIdx=1;const t=a.state.tanks[1];t.aimAngle=-2.7;const poses=a.state.tanks.map(t=>({...t,aimAngle:-.4}));a.receiveLanAction({type:'HOST_STATE',matchId:a.net.lastMatchId,turnSeq:0,tanks:poses,cuts:[],rects:[],mines:[]});console.log('baseline aim_before=-2.7 aim_after='+t.aimAngle);
const particles=a.state.particles.length;a.receiveLanAction({type:'HEALTH_EVENT',matchId:a.net.lastMatchId,turnSeq:0,idx:1,hp:0,shield:0,delta:83,x:t.x,y:t.y,label:'-83'});console.log('baseline death_explosion_particles='+ (a.state.particles.length-particles)+' session_retained='+!!a.session());
a.receiveLanAction({type:'HOST_STATE',matchId:a.net.lastMatchId,turnSeq:1,tanks:poses,cuts:[],rects:[],mines:[]});console.log('baseline future_host_state_turn_after='+a.state.turnSeq);
const original=host.sandbox.fetch,starts=[],begin=Date.now();host.sandbox.fetch=async(route,opts)=>{const msg=JSON.parse(opts.body).msg;if(msg){starts.push([msg.type,Date.now()-begin]);if(msg.type==='HOST_STATE')await new Promise(r=>setTimeout(r,180));}return original(route,opts);};
const h=host.api.state.tanks[0];host.api.sendNet({type:'HOST_STATE',turnSeq:0,tanks:host.api.state.tanks,cuts:[],rects:[]});await new Promise(r=>setTimeout(r,5));host.api.fireWeapon(.3);await host.api.session().out;console.log('baseline shared_queue_request_start_ms='+JSON.stringify(starts));
})().catch(e=>{console.error(e);process.exitCode=1});
