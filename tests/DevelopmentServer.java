package com.worta.tanks;
import java.nio.charset.StandardCharsets;import java.util.concurrent.atomic.*;
public final class DevelopmentServer {
 public static void main(String[] args)throws Exception{
  AtomicLong clock=new AtomicLong();RoomEngine engine="1".equals(System.getenv("TANK_FAKE_CLOCK"))?new RoomEngine(clock::get):new RoomEngine();AtomicReference<LocalHttpServer> ref=new AtomicReference<>();
  LocalHttpServer server=new LocalHttpServer((method,path,headers,body,local)->{
   if(path.equals("/test/time")){clock.set(Json.number(Json.map(Json.parse(new String(body,StandardCharsets.UTF_8))),"time"));return LocalHttpServer.Response.json(200,Json.object());}
   if(path.equals("/test/stats"))return LocalHttpServer.Response.json(200,Json.object("engine",engine.stats(),"connections",ref.get().connections.get(),"requests",ref.get().requests.get()));
   return LocalHttpServer.Response.json(200,path.equals("/room/discover")?engine.listing():path.equals("/room/local")?Json.object("addresses",java.util.Collections.singletonList("127.0.0.1:"+ref.get().port())):engine.call(path.substring(6),Json.map(Json.parse(new String(body,StandardCharsets.UTF_8)))));
  });ref.set(server);server.start();System.out.println(server.port());System.out.flush();Thread.currentThread().join();
 }
}
