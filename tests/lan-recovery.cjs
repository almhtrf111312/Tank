const assert=require('node:assert/strict');
const {page,frames,setNow,getNow}=require('./game.cjs');
async function test(){
 const pages=Array.from({length:3},()=>page(844,390)),[host,dead,guest]=pages;
 host.el('slots').value='3';host.el('health').value='300';await host.api.enterRoom('create');
 for(let i=1;i<3;i++){pages[i].el('roomCode').value=host.api.session().code;await pages[i].api.enterRoom('join');await host.api.roomCommand('decision',{target:i,accept:true});await pages[i].api.roomCommand('ready');}
 await host.api.roomCommand('start');
 async function drain(selected=pages){for(const p of selected)await p.api.sent();for(const p of selected)await p.api.pollNow();for(const p of selected)await p.api.sent();}
 await drain();
 // Verify guest aim ownership even across the opposite direction.
 guest.api.state.activeIdx=2;guest.api.state.tanks[2].aimAngle=-2.8;
 const poses=guest.api.state.tanks.map(t=>({...t,aimAngle:-.3}));guest.api.receiveLanAction({type:'HOST_STATE',matchId:guest.api.net.lastMatchId,turnSeq:0,activeIdx:2,phase:'aim',wind:0,round:1,tanks:poses,cuts:[],rects:[],cutBase:0,rectBase:0});assert.equal(guest.api.state.tanks[2].aimAngle,-2.8);
 guest.api.state.activeIdx=0;
 const target=host.api.state.tanks[1];host.api.applyTankDamage(target,999);await drain();assert.equal(dead.api.net.spectating,true);assert.ok(dead.api.state.particles.length>0,'dead player sees death animation');assert.ok(dead.api.session());
 // A delayed pose cannot block a reliable shot or rewind the fired phase.
 const fetchHost=host.sandbox.fetch,starts=[],start=Date.now();let delayed=true;
 host.sandbox.fetch=async(route,options)=>{const msg=JSON.parse(options.body).msg;if(msg){starts.push([msg.type,Date.now()-start]);if(msg.type==='HOST_STATE'&&delayed){delayed=false;await new Promise(r=>setTimeout(r,180));}}return fetchHost(route,options);};
 setNow(1000);host.api.publishHostState();host.api.fireWeapon(.2);await host.api.sent();
 assert.ok(starts.find(p=>p[0]==='FIRE')[1]<120,'shot does not wait 180ms behind pose');await drain();
 for(let i=0;i<60;i++){frames([host],6);await drain();if(host.api.state.turnSeq>0)break;}
 assert.ok(host.api.state.turnSeq>0);assert.equal(dead.api.state.turnSeq,host.api.state.turnSeq,'spectator continues into next turn');
 host.sandbox.fetch=fetchHost;
 let lost=0,requests=0;for(const p of pages){const original=p.sandbox.fetch;p.sandbox.fetch=async(route,options)=>{
   if(p.sandbox.offline)throw Error('simulated Wi-Fi off');const n=++requests;await new Promise(r=>setTimeout(r,n%4));const response=await original(route,options);
   if(n%83===0 && (route==='/room/poll'||route==='/room/packet')){lost++;throw Error('simulated response lost after acceptance');}return response;
 };}
 const base=Math.ceil(getNow()/1000);let recoveryChecked=false,maxPoseBytes=0;
 for(let second=base;second<=600;second++){
   setNow(second*1000);await fetch(`http://127.0.0.1:${process.env.TANK_TEST_PORT}/test/time`,{method:'POST',body:JSON.stringify({time:second*1000})});
   if(second===100){guest.sandbox.offline=true;await guest.api.pollNow();assert.ok(guest.api.session());}
   if(second===310){guest.sandbox.offline=false;await guest.api.pollNow();assert.ok(guest.api.net.connected);assert.equal(guest.api.state.turnSeq,host.api.state.turnSeq);assert.equal(guest.api.mask(),host.api.mask());recoveryChecked=true;}
   if(second<600){for(let i=0;i<5;i++)host.api.carveTerrain(700+i,650+(second%20),2,'#f97316');}
   host.api.upgradeStep();await host.api.sent();
   // Advance real authoritative turn protocol throughout the simulated match.
   if(second<600&&second%30===0&&!host.api.state.gameOver){host.api.sendNet({type:'ENVIRONMENT',turnSeq:host.api.state.turnSeq});host.api.finishLanTurn();await host.api.sent();}
   const online=pages.filter(p=>!p.sandbox.offline);await drain(online);
   maxPoseBytes=Math.max(maxPoseBytes,host.api.netMetrics.maxRequestBytes);
   if(second%100===0){assert.equal(dead.api.net.connected,true);assert.equal(dead.api.state.turnSeq,host.api.state.turnSeq);assert.equal(dead.api.mask(),host.api.mask());}
 }
 assert.ok(recoveryChecked);assert.ok(lost>0);assert.ok(guest.api.netMetrics.resyncs>0);
 for(const p of pages){assert.equal(p.api.state.gameOver,true);assert.equal(p.el('winnerStats').textContent,host.el('winnerStats').textContent,'shared scores are not incremented twice');assert.ok(p.api.session(),'spectator/guest remains in room for final result');}
 const stats=await(await fetch(`http://127.0.0.1:${process.env.TANK_TEST_PORT}/test/stats`,{method:'POST',body:'{}'})).json();assert.ok(stats.engine.events<=512);assert.ok(stats.engine.eventBytes<=524288);console.log('transport_connections='+stats.connections+' requests='+stats.requests);assert.ok(stats.connections<stats.requests/3,'persistent sockets reused');
 console.log(JSON.stringify({simulatedSeconds:600,losses:lost,offlineSeconds:210,resyncs:pages.map(p=>p.api.netMetrics.resyncs),requestP95Ms:pages.map(p=>{const values=p.api.netMetrics.requestMs.slice().sort((a,b)=>a-b);return values[Math.floor(values.length*.95)]}),maxUrgentQueue:host.api.netMetrics.maxUrgentQueue,poseDrops:host.api.netMetrics.poseDrops,server:stats}));
 console.log('PASS lossy 10-minute match, early dead spectator, separate pose/shot channels, opposite aim, 210-second Wi-Fi loss, full terrain/turn resume and shared final result.');
}
test().catch(e=>{console.error(e);process.exitCode=1});
