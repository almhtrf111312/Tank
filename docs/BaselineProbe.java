package com.worta.tanks;
import java.util.*;import java.util.concurrent.atomic.AtomicLong;
public class BaselineProbe {
 public static void main(String[] args){
  AtomicLong time=new AtomicLong();RoomEngine e=new RoomEngine(time::get);
  Map<String,Object> h=Json.map(e.call("create",Json.object("count",3,"hp",100))),a=Json.map(e.call("join",Json.object("code",h.get("code")))),b=Json.map(e.call("join",Json.object("code",h.get("code"))));
  for(Map<String,Object> p:Arrays.asList(a,b)){e.call("decision",auth(h,"target",p.get("idx"),"accept",true));e.call("ready",auth(p));}e.call("start",auth(h,"theme","aurora"));
  Map<String,Object> start=Json.map(Json.map(((List<?>)Json.map(e.call("poll",auth(h,"cursor",0))).get("events")).get(0)).get("msg"));Object match=start.get("matchId");
  List<Object> cuts=new ArrayList<>();long sent=0,pollBytes=0,max=0;int cursor=1;
  for(int second=1;second<=600;second++){
   time.set(second*1000L);for(int i=0;i<5;i++)cuts.add(Arrays.asList(100+i,500,13,"#f97316"));
   String wire=Json.stringify(auth(h,"msg",Json.object("type","HOST_STATE","matchId",match,"turnSeq",0,"tanks",Arrays.asList(Json.object(),Json.object(),Json.object()),"cuts",cuts,"rects",Collections.emptyList())));
   long bytes=wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;sent+=bytes;max=Math.max(max,bytes);
   e.call("packet",Json.map(Json.parse(wire)));
   for(Map<String,Object> p:Arrays.asList(a,b)){Map<String,Object> poll=Json.map(e.call("poll",auth(p,"cursor",cursor)));pollBytes+=Json.stringify(poll).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
   cursor++;
  }
  System.out.println("baseline stale_poll_reply_bytes="+Json.stringify(e.call("poll",auth(a,"cursor",0))).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
  int retained=((List<?>)Json.map(e.call("poll",auth(h,"cursor",0))).get("events")).size();
  System.out.println("baseline simulated_seconds=600 publish_hz=1 final_cuts="+cuts.size()+" max_request_bytes="+max+" total_host_bytes="+sent+" two_guest_response_bytes="+pollBytes+" retained_events="+retained);
  while(Json.stringify(cuts).length()<270000)cuts.add(Arrays.asList(100,500,13,"#f97316"));
  System.out.println("baseline HTTP_body_limit=262144 oversized_cuts="+cuts.size()+" payload_bytes="+Json.stringify(cuts).length());
 }
 static Map<String,Object> auth(Map<String,Object> p,Object... more){Map<String,Object> m=Json.object("code",p.get("code"),"token",p.get("token"));m.putAll(Json.object(more));return m;}
}
