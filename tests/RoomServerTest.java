package com.worta.tanks;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicLong;
public final class RoomServerTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void rejected(Runnable r){try{r.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Should reject");}
 static Map<String,Object> auth(Map<String,Object> p,Object... extra){Map<String,Object> m=Json.object("code",p.get("code"),"token",p.get("token"),"protocol",2);m.putAll(Json.object(extra));return m;}
 static class Match {
  final RoomEngine e;final List<Map<String,Object>> players=new ArrayList<>();final Map<String,Object> host;final String match;
  Match(RoomEngine e,int count){
   this.e=e;host=Json.map(e.call("create",Json.object("protocol",2,"count",count,"hp",100,"roomName","غرفة الاختبار")));players.add(host);
   check(Json.text(host,"code").matches("[0-9]{6}"),"Six-digit collision-checked discovery ID");
   for(int i=1;i<count;i++){Map<String,Object> g=Json.map(e.call("join",Json.object("protocol",2,"code",host.get("code"))));players.add(g);rejected(()->e.call("ready",auth(g)));e.call("decision",auth(host,"target",i,"accept",true));e.call("ready",auth(g));}
   e.call("start",auth(host,"theme","aurora"));Map<String,Object> response=Json.map(e.call("poll",auth(host,"cursor",0)));Map<String,Object> start=Json.map(Json.map(((List<?>)response.get("events")).get(0)).get("msg"));match=Json.text(start,"matchId");
   e.call("checkpoint",auth(host,"matchId",match,"terrainRle",Arrays.asList(0,1600*900),"cutCount",0,"rectCount",0,"world",world(0)));
  }
  Map<String,Object> world(int seq){List<Object> tanks=new ArrayList<>();for(int i=0;i<players.size();i++)tanks.add(Json.object("x",200+i*300,"y",400,"hp",i==1?0:83,"fuel",100,"aimAngle",-.7,"shield",0,"vx",0,"vy",0,"slope",0,"selectedWeapon","shell","ammo",Json.object("shell",null)));return Json.object("turnSeq",seq,"activeIdx",0,"phase","aim","wind",.2,"round",1,"tanks",tanks,"projectiles",Collections.emptyList(),"beams",Collections.emptyList());}
  Map<String,Object> packet(String type,Object... more){Map<String,Object> p=Json.object("type",type,"matchId",match,"turnSeq",0);p.putAll(Json.object(more));return p;}
 }
 public static void main(String[] args)throws Exception{
  check(Json.stringify(Json.parse("{\"a\":[1,true,null,\"عربي\"]}")).contains("عربي"),"JSON");rejected(()->Json.parse("[1,]"));
  RoomEngine incompatible=new RoomEngine();rejected(()->incompatible.call("create",Json.object("count",2,"hp",100)));rejected(()->incompatible.call("create",Json.object("protocol",1,"count",2,"hp",100)));
  for(int count:new int[]{2,3,4}){
   Match m=new Match(new RoomEngine(),count);Map<String,Object> control=m.packet("CONTROL","idx",0,"aimAngle",-2.7,"keys",Json.object("left",false));m.e.call("packet",auth(m.host,"msg",control));
   Map<String,Object> fire=m.packet("FIRE","idx",0,"x",200,"y",400,"vx",0,"vy",0,"slope",0,"fuel",100,"hp",83,"aimAngle",-2.7,"power",.5,"shotSeed",1,"weaponId","shell");
   rejected(()->m.e.call("packet",auth(m.players.get(1),"msg",fire)));Map<String,Object> id=auth(m.host,"msg",fire,"requestId","one-fire");m.e.call("packet",id);m.e.call("packet",id);
   Map<String,Object> pose=m.world(0);pose.putAll(m.packet("HOST_STATE","cutBase",0,"rectBase",0,"cuts",Collections.emptyList(),"rects",Collections.emptyList()));m.e.call("packet",auth(m.host,"msg",pose));
   Map<String,Object> result=m.packet("LAN_RESULT","nextIdx",Math.min(2,count-1),"done",false,"tanks",m.world(0).get("tanks"),"cutBase",0,"rectBase",0,"cuts",Collections.emptyList(),"rects",Collections.emptyList());m.e.call("packet",auth(m.host,"msg",result));
   m.e.call("packet",auth(m.host,"msg",pose)); // Old pose after result is harmless, not a fatal room error.
   Map<String,Object> snap=Json.map(m.e.call("snapshot",auth(m.players.get(1))));check(Json.number(Json.map(snap.get("world")),"turnSeq")==1,"Full snapshot follows authoritative turn");check(Json.number(Json.map(((List<?>)Json.map(snap.get("world")).get("tanks")).get(1)),"hp")==0,"Dead guest retains snapshot access");
  }
  AtomicLong clock=new AtomicLong();Match m=new Match(new RoomEngine(clock::get),3);long hostBytes=0,guestBytes=0,maxRequest=0;int cursor=1;
  for(int second=1;second<=600;second++){
   clock.set(second*1000L);List<Object> cuts=new ArrayList<>();for(int i=0;i<5;i++)cuts.add(Arrays.asList(100+i,500,13,"#f97316"));
   Map<String,Object> msg=m.world(0);msg.putAll(m.packet("HOST_STATE","cutBase",(second-1)*5,"rectBase",0,"cuts",cuts,"rects",Collections.emptyList()));String wire=Json.stringify(auth(m.host,"msg",msg));long bytes=wire.getBytes(StandardCharsets.UTF_8).length;hostBytes+=bytes;maxRequest=Math.max(maxRequest,bytes);m.e.call("packet",Json.map(Json.parse(wire)));
   for(Map<String,Object> guest:m.players.subList(1,3)){Map<String,Object> reply=Json.map(m.e.call("poll",auth(guest,"cursor",cursor)));check(!Boolean.TRUE.equals(reply.get("resync")),"Active clients do not lose cursor");guestBytes+=Json.stringify(reply).getBytes(StandardCharsets.UTF_8).length;}cursor++;
  }
  Map<String,Object> stats=Json.map(m.e.stats());check(Json.number(stats,"events")<=RoomEngine.MAX_EVENTS && Json.number(stats,"eventBytes")<=RoomEngine.MAX_EVENT_BYTES,"Event count and byte cap");check(maxRequest<4000,"Delta size independent of match duration");
  Map<String,Object> old=Json.map(m.e.call("poll",auth(m.players.get(1),"cursor",0)));check(Boolean.TRUE.equals(old.get("resync")),"Old cursor requests snapshot instead of kicking spectator");
  clock.set(900000);Map<String,Object> resumed=Json.map(m.e.call("snapshot",auth(m.players.get(1))));check(Json.number(resumed,"cutCount")==3000,"Resume after >150s retains all terrain");check(Json.number(resumed,"cursor")==601,"Snapshot captures atomic cursor");
  TerrainState mask=new TerrainState();mask.load(resumed.get("terrainRle"),3000,0);check(mask.mask[500*1600+100]==0&&mask.mask[800*1600+800]==1,"Full RLE mask matches terrain deletions");
  System.out.println("after simulated_seconds=600 publish_hz=1 max_request_bytes="+maxRequest+" total_host_bytes="+hostBytes+" two_guest_response_bytes="+guestBytes+" retained_events="+stats.get("events")+" event_bytes="+stats.get("eventBytes"));
  long begin=System.nanoTime();m.e.call("poll",auth(m.host,"cursor",601,"wait",true));check((System.nanoTime()-begin)/1000000>=500,"Idle long-poll waits");
  check(EndpointAddress.parse("192.168.1.2:45555").port==45555,"Manual local endpoint");rejected(()->EndpointAddress.parse("8.8.8.8:80"));rejected(()->EndpointAddress.parse("localhost:1234"));rejected(()->EndpointAddress.parse("192.168.1.2:99999"));
  // Reuse one real socket for two fully framed HTTP requests.
  try(LocalHttpServer server=new LocalHttpServer((method,path,headers,body,local)->LocalHttpServer.Response.json(200,Json.object("ok",true)))){
   server.start();try(Socket socket=new Socket("127.0.0.1",server.port())){socket.setSoTimeout(3000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();for(int i=0;i<2;i++){out.write("POST /test HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));out.flush();String line=readLine(in);check(line.contains("200"),"HTTP status");int size=0;boolean keep=false;while(!(line=readLine(in)).isEmpty()){if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());if(line.equalsIgnoreCase("Connection: keep-alive"))keep=true;}check(keep,"Persistent response");new DataInputStream(in).readFully(new byte[size]);}check(server.connections.get()==1&&server.requests.get()==2,"Real connection reuse");}
  }
  System.out.println("PASS protocol mismatch, 2/3/4 clients, dead spectator, late pose, snapshot reconnect, bounded event bytes, delta growth, long-poll, manual IP and persistent HTTP.");
 }
 static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n')return b.toString("US-ASCII").replace("\r","");b.write(c);}throw new EOFException();}
}
