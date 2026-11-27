const assert = require('node:assert/strict');
const {createCanvas} = require('@napi-rs/canvas');
const {readFileSync,writeFileSync} = require('node:fs');
const vm = require('node:vm');
const elements = JSON.parse(readFileSync(__dirname+'/dom.json','utf8'));
let src = readFileSync(__dirname+'/../app/src/main/assets/index.html','utf8').split('<script>')[1].split('</script>')[0];
src = src.replace('      window.onload = () => {', `      window.testing = {
  terrain:()=>solidMask, WORLD_W, isSolid, crumbleTankSupport, net, state, config, render, resizeCanvas, enterRoom,roomCommand,pollNow:()=>poll(session),sent:()=>session.out,session:()=>session, stepPhysics, fireWeapon,
  selectWeapon, aimAtWorld, toggleOverview, finishLanTurn, applyTankDamage, carveTerrain, startNewGame, getWeapon, shotSpeed, traceBeam, fireBeam, onProjectileImpact, chooseComputerShot, traceShot,
  collapseTerrain,prefs,applyControls,battleFinished,upgradeStep,endGame,survivors,prepareAITurn,refreshRooms,timeoutWinner,expireMatch,observeFrame,renderScale,economicQuality,effectCount,
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
async function test(){
 const pages=Array.from({length:4},()=>page(844,390)),host=pages[0];
 host.el('roomName').value='غرفة الأصدقاء';host.el('playerName').value='Host';
 host.el('slots').value='4';host.el('battleFormat').value='teams';host.el('seriesLength').value='3';
 await host.api.enterRoom('create');assert.ok(host.api.session(),'room created: '+host.el('p2pStatus').textContent);
 for(let i=1;i<4;i++){
  const p=pages[i];p.el('playerName').value='Guest '+i;
  await p.api.refreshRooms();const roomButton=p.el('availableRooms').children[0];assert.ok(roomButton.textContent.includes('غرفة الأصدقاء'));
  await roomButton.onclick();await p.api.pollNow();assert.equal(p.el('roomReady').disabled,true,'pending player cannot ready');
  await host.api.roomCommand('decision',{target:i,accept:true});await p.api.roomCommand('team',{team:i===1?0:1});await p.api.roomCommand('ready');
 }
 await host.api.roomCommand('start');
 async function drain(){for(const p of pages)await p.api.sent();for(const p of pages)await p.api.pollNow();}
 await drain();
 for(const p of pages){assert.equal(p.api.state.tanks.length,4);assert.equal(p.api.config.format,'teams');assert.equal(p.api.config.seriesLength,3);assert.equal(p.api.state.tanks[0].healthLabel,100);assert.equal(p.api.state.tanks[1].team,0);assert.equal(p.api.state.tanks[2].team,1);assert.equal(p.api.state.tanks[1].name,'Guest 1');}
 // Health and floating damage arrive before the turn changes, and are never computed by guests.
 const victim=host.api.state.tanks[1],hpBefore=victim.hp;
 host.api.applyTankDamage(victim,17);await drain();
 for(const p of pages.slice(1)){assert.equal(p.api.state.tanks[1].hp,hpBefore-17);assert.equal(p.api.state.turnSeq,0);assert.ok(p.api.state.floatingTexts.some(t=>t.text==='-17'));}
 // Complete host turn, let delayed guests reconcile exactly to host outcome.
 host.api.selectWeapon('shield');host.api.fireWeapon(.5);await drain();frames([host],300);await drain();frames(pages.slice(1),350);await drain();
 for(const p of pages){assert.equal(p.api.state.activeIdx,1);assert.equal(p.api.state.turnSeq,1);}
 const guest=pages[1],before=host.api.state.tanks[1].x;
 guest.el('btnLeft').dispatch('pointerdown',{pointerType:'touch',button:-1,pointerId:14,preventDefault(){}});
 frames([guest],8);assert.equal(guest.api.state.tanks[1].x,before,'guest does not decide its own position');await drain();
 frames([host],10);await drain();assert.ok(host.api.state.tanks[1].x<before,'host executes guest input');
 guest.el('btnLeft').dispatch('pointerup',{pointerId:14});await drain();frames([host],8);await drain();
 frames([guest],16);
 assert.ok(Math.abs(guest.api.state.tanks[1].x-host.api.state.tanks[1].x)<.001,'authoritative movement broadcast');
 guest.api.selectWeapon('shell');guest.api.aimAtWorld(guest.api.state.tanks[1].x+100,guest.api.state.tanks[1].y+60);guest.api.fireWeapon(.22);await drain();await drain();
 frames([host],700);await drain();frames(pages.slice(1),750);await drain();
 for(const p of pages){assert.equal(p.api.state.turnSeq,2);assert.equal(p.api.mask(),host.api.mask(),'terrain authority');assert.deepEqual(Array.from(p.api.state.tanks,t=>t.hp),Array.from(host.api.state.tanks,t=>t.hp));p.api.render();}
 const retryActor=pages[2];retryActor.api.selectWeapon('shield');await drain();retryActor.sandbox.dropNextPacketReply=true;retryActor.api.fireWeapon(.5);
 await drain();await drain();frames([host],350);await drain();frames(pages.slice(1),400);await drain();
 for(const p of pages)assert.equal(p.api.state.turnSeq,3,'lost packet reply retries exactly once without duplicate turn');
 // All peers end a timed match with the host's team-health winner.
 [30,20,40,30].forEach((hp,i)=>host.api.state.tanks[i].hp=hp);
 host.api.expireMatch();await drain();frames(pages.slice(1),2);await drain();
 for(const p of pages){assert.equal(p.api.state.gameOver,true);assert.match(p.el('winnerTitle').textContent,/الثاني/);}
 const solo=page(844,390),api=solo.api;api.config.mode='pvp';solo.el('seatCount').value='4';api.config.format='teams';api.startNewGame(818,0);
 assert.equal(api.state.tanks.length,4);api.state.tanks[1].hp=api.state.tanks[3].hp=0;assert.ok(api.battleFinished());api.endGame(api.state.tanks[0]);assert.match(solo.el('winnerTitle').textContent,/فريق/);assert.match(solo.el('winnerStats').textContent,/الدبابة/);
 api.config.format='free';api.startNewGame(819,0);const mask=api.terrain(),w=api.WORLD_W,tank=api.state.tanks[0];
 mask.fill(0);for(let y=880;y<900;y++)mask.fill(1,y*w,(y+1)*w);for(let y=380;y<420;y++)mask.fill(1,y*w+350,y*w+450);
 api.carveTerrain(400,850,2);api.collapseTerrain();assert.equal(api.isSolid(400,400),false,'floating island removed');assert.ok(api.isSolid(400,890),'bedrock retained');
 api.startNewGame(820,0);api.state.turnSeq=0;const enemy=api.state.tanks[1];api.onProjectileImpact({weapon:api.getWeapon('mine'),x:enemy.x,y:enemy.y+8,ownerIdx:0});
 assert.equal(api.state.mines.length,1);api.upgradeStep();assert.equal(api.state.mines.length,1,'not armed on placement turn');api.state.turnSeq=1;const hp=enemy.hp;api.upgradeStep();assert.equal(api.state.mines.length,0);assert.ok(enemy.hp<hp,'mine harms nearby tank');
 for(const level of ['normal','medium','hard']){api.config.mode='pvai';api.config.aiLevel=level;api.startNewGame(821,0);api.state.activeIdx=1;api.prepareAITurn();assert.ok(api.state.aiState && api.state.tanks[1].ammo[api.state.tanks[1].selectedWeapon]>0);for(let i=0;i<2;i++)api.stepPhysics();const plan=api.chooseComputerShot(api.state.tanks[1],api.state.tanks[0],level==='hard');assert.ok(Number.isFinite(plan.power)&&plan.power<=1);}
 // Barrage emits exactly three missiles and its full attack cannot erase a fresh tank.
 api.config.mode='pvp';api.config.format='free';solo.el('seatCount').value='2';api.startNewGame(912,0);api.selectWeapon('itano');api.fireWeapon(.6);
 assert.equal(api.state.projectiles.length,3);assert.equal(api.getWeapon('itano').name,'وابل صواريخ');
 const target=api.state.tanks[1],initialHP=target.hp;
 for(let i=0;i<3;i++)api.applyTankDamage(target,10);
 api.applyTankDamage(target,10);assert.equal(initialHP-target.hp,30,'entire barrage capped at 30');assert.ok(target.hp>0);
 for(const [weapon,cap] of [['volcano',38],['mirv',36],['plasma',36],['meteor',40]]){
   api.startNewGame(913,0);api.selectWeapon(weapon);api.fireWeapon(.5);const t=api.state.tanks[1],hp=t.hp;for(let i=0;i<12;i++)api.applyTankDamage(t,20);assert.equal(hp-t.hp,cap,weapon+' total cap');
 }
 api.startNewGame(914,0);const first=api.state.tanks[0],second=api.state.tanks[1];first.hp=30;second.hp=40;assert.equal(api.timeoutWinner().idx,1);second.hp=30;assert.equal(api.timeoutWinner(),null,'equal HP draws');second.hp=40;api.state.matchDeadline=now-1;api.upgradeStep();assert.equal(api.state.gameOver,true,'deadline ends the match');assert.match(solo.el('winnerTitle').textContent,/الزرقاء/);api.startNewGame(915,0);
 api.prefs.quality='auto';solo.sandbox.devicePixelRatio=3;assert.equal(api.renderScale(),1.25);for(let i=0;i<90;i++)api.observeFrame(35);assert.equal(api.economicQuality(),true);assert.equal(api.renderScale(),1);assert.ok(api.effectCount(100)<30);api.state.particles=[{type:'smoke',x:400,y:300,r:9,life:20,maxLife:30,color:'#64748b'},{type:'shockwave',x:400,y:300,r:15,life:18,maxLife:25,color:'#f59e0b'}];api.render();
 if(process.env.TANK_PREVIEW)writeFileSync(process.env.TANK_PREVIEW,pages[0].el('gameCanvas').toBuffer('image/png'));
 api.prefs.quality='low';api.state.particles=Array.from({length:400},()=>({life:10}));const beforeHP=api.state.tanks[0].hp;api.upgradeStep();assert.ok(api.state.particles.length<=100);assert.equal(api.state.tanks[0].hp,beforeHP,'quality never changes gameplay damage');
 console.log('PASS 3-missile barrage and attack caps, adaptive resolution/effects, ten-minute expiry with shared team winner and ties, plus named lobbies, admission, teams, immediate damage, reconnection, physics and rendering.');
}
test().catch(e=>{console.error(e);process.exitCode=1});
