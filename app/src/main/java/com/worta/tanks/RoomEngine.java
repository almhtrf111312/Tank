package com.worta.tanks;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;

final class RoomEngine {
    static final int MAX_EVENTS=512, MAX_EVENT_BYTES=524288;
    private long calls,packetBytes,pollBytes,maxPollBytes,snapshotRequests;
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private Room room;
    private final Map<String, Object> receipts = new LinkedHashMap<>();
    RoomEngine() { this(() -> System.nanoTime() / 1_000_000); }
    RoomEngine(LongSupplier clock) { this.clock = clock; }

    private static final class Player {
        final int index;
        boolean ready, approved;
        int team; String name;
        long seen;
        Player(int index, long now, String name) { this.index = index; ready = approved = index == 0; team=index%2; this.name=name; seen = now; }
    }
    private static final class Room {
        String code, name, match = "";
        int count, hp, cursor, turn, sequence, seriesLength = 1, seriesRound;
        String format = "free"; boolean trajectory = true;
        boolean started, fired, done;
        long seen,deadline;
        int eventBytes,revision;
        Map<String,Object> start,world;
        TerrainState terrain;
        final Set<String> rejected = new HashSet<>();
        final Map<String, Player> players = new LinkedHashMap<>();
        final Deque<Map<String, Object>> events = new ArrayDeque<>();
    }
    private String token() { return UUID.randomUUID().toString().replace("-", ""); }
    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    synchronized String code() { return room == null ? "" : room.code; }
    synchronized String name() { return room == null ? "" : room.name; }
    synchronized Object listing() {return Json.object("rooms",room==null?Collections.emptyList():Collections.singletonList(Json.object("code",room.code,"name",room.name,"protocol",Protocol.VERSION)));}
    private String cleanName(Map<String,Object> data,String key,String fallback){
        String value=Json.text(data,key).replaceAll("[\\p{Cntrl}]", "").trim();
        if(value.isEmpty())return fallback;
        return value.substring(0,value.offsetByCodePoints(0,Math.min(32,value.codePointCount(0,value.length()))));
    }
    synchronized void clear() { room = null; receipts.clear(); }

    synchronized Object stats(){return Json.object("calls",calls,"packetBytes",packetBytes,"pollBytes",pollBytes,"maxPollBytes",maxPollBytes,"snapshotRequests",snapshotRequests,"events",room==null?0:room.events.size(),"eventBytes",room==null?0:room.eventBytes,"terrainBytes",room==null||room.terrain==null?0:room.terrain.mask.length);}
    synchronized Object call(String action, Map<String, Object> data) {
        calls++;
        if(action.equals("info")){Protocol.requireVersion(data);return Json.object("protocol",Protocol.VERSION,"code",code(),"name",name());}
        if(action.equals("create") || action.equals("join"))Protocol.requireVersion(data);
        if(action.equals("poll") && Boolean.TRUE.equals(data.get("wait")) && room!=null && room.code.equals(Json.text(data,"code")) && room.players.containsKey(Json.text(data,"token")) && Json.number(data,"cursor")>=room.cursor){
            try{wait(700);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        }
        String request = Json.text(data, "requestId");
        String receiptKey = action + "/" + Json.text(data, "token") + "/" + request;
        if (!request.isEmpty() && receipts.containsKey(receiptKey)) return receipts.get(receiptKey);
        Object result = execute(action, data);
        if(action.equals("packet"))packetBytes+=Json.stringify(data).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(action.equals("poll")){int bytes=Json.stringify(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;pollBytes+=bytes;maxPollBytes=Math.max(maxPollBytes,bytes);}
        if(!action.equals("poll"))notifyAll();
        if (!request.isEmpty() && !action.equals("poll")) {
            receipts.put(receiptKey, result);
            if (receipts.size() > 256) receipts.remove(receipts.keySet().iterator().next());
        }
        return result;
    }

    private Object execute(String action, Map<String, Object> data) {
        long now = clock.getAsLong();
        if (action.equals("create")) {
            require(room == null, "أغلق الغرفة الحالية أولًا");
            int count = Json.number(data, "count"), hp = Json.number(data, "hp");
            require(count >= 2 && count <= 4 && Arrays.asList(100, 200, 300).contains(hp), "خيارات غرفة غير صالحة");
            String requestedFormat = Json.text(data,"format");
            require(requestedFormat.isEmpty() || requestedFormat.equals("free") || requestedFormat.equals("teams"), "نمط غير صالح");

            int requestedSeries = data.containsKey("seriesLength") ? Json.number(data,"seriesLength") : 1;
            require(requestedSeries == 1 || requestedSeries == 3, "عدد جولات غير صالح");
            room = new Room();
            room.code = Integer.toString(100000 + random.nextInt(900000));
            room.count = count;room.name=cleanName(data,"roomName","غرفة الدبابات");
            room.hp = hp;
            String format = Json.text(data, "format");
            require(format.isEmpty() || format.equals("free") || format.equals("teams"), "نمط غير صالح");

            room.format = format.isEmpty() ? "free" : format;
            room.seriesLength = data.containsKey("seriesLength") ? Json.number(data,"seriesLength") : 1;
            require(room.seriesLength == 1 || room.seriesLength == 3, "عدد جولات غير صالح");
            room.trajectory = !Boolean.FALSE.equals(data.get("trajectory"));
            room.seen = now;
            return join(now,data);
        }
        require(room != null && room.code.equals(Json.text(data, "code")), "الغرفة غير موجودة");
        // Seats are owned by tokens, not by recent traffic or HP. Offline spectators can resume.
        room.seen = now;
        if (action.equals("join")) {
            require(!room.started && room.players.size() < room.count, "الغرفة ممتلئة أو بدأت");
            return join(now,data);
        }
        require(!room.rejected.contains(Json.text(data,"token")),"رفض المضيف طلب انضمامك أو أزالك من الغرفة");
        Player player = room.players.get(Json.text(data, "token"));
        require(player != null, "انتهت جلسة اللاعب");
        player.seen = now;
        int index = player.index;
        switch (action) {
            case "poll":
                int cursor = Json.number(data, "cursor");
                boolean resync=cursor<room.cursor && (room.events.isEmpty() || cursor<Json.number(room.events.peekFirst(),"id")-1);
                List<Object> roster = new ArrayList<>(), events = new ArrayList<>();
                for (Player p : room.players.values()) roster.add(Json.object("idx", p.index, "ready", p.ready, "connected", now - p.seen < 8000,"approved",p.approved,"team",p.team,"name",p.name));
                if(resync && index==0 && room.world==null && room.start!=null){events.add(Json.object("id",room.cursor,"sender",0,"msg",room.start));resync=false;cursor=room.cursor;}
                int batchBytes=0;
                if(!resync)for(Map<String,Object> e:room.events)if(Json.number(e,"id")>cursor){int bytes=Json.stringify(e).length();if(events.size()>=64 || batchBytes+bytes>128000)break;events.add(e);batchBytes+=bytes;}
                return Json.object("players", roster, "events", events, "started", room.started,"format",room.format,"name",room.name,"remainingMs",Math.max(0,room.deadline-now),"resync",resync,"protocol",Protocol.VERSION);
            case "snapshot":
                require(player.approved,"بانتظار موافقة المضيف");snapshotRequests++;
                if(room.world==null || room.terrain==null)return Json.object("pending",true,"started",room.started,"start",room.start,"cursor",room.cursor);
                return Json.object("start",room.start,"world",Json.parse(Json.stringify(room.world)),"terrainRle",room.terrain.encode(),"cutCount",room.terrain.cuts,"rectCount",room.terrain.rects,"cursor",room.cursor,"remainingMs",Math.max(0,room.deadline-now),"protocol",Protocol.VERSION);
            case "checkpoint":
                require(index==0 && room.started && room.match.equals(Json.text(data,"matchId")),"المضيف ينشئ اللقطة");
                TerrainState terrain=new TerrainState();terrain.load(data.get("terrainRle"),Json.number(data,"cutCount"),Json.number(data,"rectCount"));
                room.terrain=terrain;room.world=new LinkedHashMap<>(Json.map(data.get("world")));room.revision=data.containsKey("revision")?Json.number(data,"revision"):room.revision;room.world.put("revision",room.revision);
                return Json.object("cutCount",terrain.cuts,"rectCount",terrain.rects);
            case "decision":
                require(index==0 && !room.started,"المضيف يقرر قبل بدء المعركة");
                int target=Json.number(data,"target");
                Player candidate=room.players.values().stream().filter(p->p.index==target).findFirst().orElse(null);
                require(candidate!=null && target!=0,"لاعب غير موجود");
                require(data.get("accept") instanceof Boolean,"قرار غير صالح");
                if(Boolean.TRUE.equals(data.get("accept")))candidate.approved=true;
                else {
                    String remove=room.players.entrySet().stream().filter(e->e.getValue()==candidate).findFirst().get().getKey();
                    room.rejected.add(remove);room.players.remove(remove);
                }
                break;
            case "team":
                require(!room.started && player.approved && room.format.equals("teams"),"اختر الفريق بعد قبول المضيف وقبل المعركة");
                int team=Json.number(data,"team");require(team>=-1 && team<=1,"فريق غير صالح");
                require(team==-1 || room.players.values().stream().filter(p->p!=player && p.team==team).count()<2,"الفريق مكتمل؛ الحد لاعبان");
                if(player.team!=team){player.team=team;player.ready=index==0 && team>=0;}
                break;
            case "heartbeat": break;
            case "ready":
                require(!room.started && player.approved, "بانتظار قبول المضيف أو بدأت المعركة");
                require(!room.format.equals("teams") || player.team>=0,"اختر فريقك أولاً");
                player.ready = true;
                break;
            case "start":
                require(index == 0 && (!room.started || room.done) && room.players.size() == room.count
                        && room.players.values().stream().allMatch(p -> p.ready && p.approved && (!room.format.equals("teams") || p.team>=0)), "بانتظار اكتمال اللاعبين والجاهزية");
                require(!room.format.equals("teams") || (room.players.values().stream().anyMatch(p->p.team==0) && room.players.values().stream().anyMatch(p->p.team==1)),"يجب وجود لاعب في كل فريق");
                String theme = Json.text(data, "theme");
                require(Arrays.asList("aurora", "canyon", "cyber").contains(theme), "ساحة غير صالحة");
                room.seriesRound = room.seriesRound >= room.seriesLength ? 1 : room.seriesRound + 1;
                room.started = true;room.deadline=now+600_000;
                room.fired = room.done = false;
                room.turn = room.sequence = 0;room.revision=0;room.world=null;room.terrain=null;room.events.clear();room.eventBytes=0;
                room.match = token();
                room.start=Json.object("type", "START", "seed", random.nextInt(1_000_000_000),
                        "wind", 0, "theme", theme, "matchId", room.match,"format",room.format,"seriesLength",room.seriesLength,"seriesRound",room.seriesRound,"trajectory",room.trajectory,"players",room.players.values().stream().map(p->Json.object("idx",p.index,"team",p.team,"name",p.name)).collect(java.util.stream.Collectors.toList()));
                emit(index,room.start);
                break;
            case "packet":
                packet(index, Json.map(data.get("msg")));
                return Json.object("cutCount",room.terrain==null?0:room.terrain.cuts,"rectCount",room.terrain==null?0:room.terrain.rects);
            case "leave":
                if (index == 0 || room.started) clear();
                else room.players.remove(Json.text(data, "token"));
                break;
            default: throw new IllegalArgumentException("طلب غير صالح");
        }
        return Json.object();
    }

    private Object join(long now,Map<String,Object> data) {
        int index = 0;
        Set<Integer> used = new HashSet<>();
        for (Player p : room.players.values()) used.add(p.index);
        while (used.contains(index)) index++;
        String token = token();
        Player player=new Player(index,now,cleanName(data,"playerName","اللاعب "+(index+1)));
        if(room.format.equals("teams") && index!=0)player.team=-1;
        room.players.put(token, player);
        return Json.object("code", room.code, "token", token, "idx", index, "count", room.count, "hp", room.hp,"name",room.name,"format",room.format,"protocol",Protocol.VERSION);
    }

    private void packet(int index, Map<String, Object> msg) {
        String requestedType=Json.text(msg,"type");
        int revision=msg.containsKey("revision")?Json.number(msg,"revision"):room.revision+1;
        if(index==0 && requestedType.equals("HOST_STATE") && (revision<room.revision || room.fired&&Json.text(msg,"phase").equals("aim")))return;
        if(index==0 && requestedType.equals("HOST_STATE") && (room.done || room.sequence!=Json.number(msg,"turnSeq")))return;
        require(room.started && !room.done && room.match.equals(Json.text(msg, "matchId"))
                && room.sequence == Json.number(msg, "turnSeq"), "تغير الدور");
        String type = Json.text(msg, "type");
        if(index==0 && (type.equals("HOST_STATE")||type.equals("LAN_RESULT"))){
            require(room.terrain!=null,"SNAPSHOT_REQUIRED");room.terrain.delta(msg);
        }
        if(type.equals("HEALTH_EVENT")){
            require(index==0,"المضيف يعتمد الضرر");
            int victim=Json.number(msg,"idx");require(victim>=0 && victim<room.count,"دبابة غير صالحة");
            require(msg.get("hp") instanceof Number && msg.get("delta") instanceof Number,"ضرر غير صالح");
            double hp=((Number)msg.get("hp")).doubleValue(),delta=((Number)msg.get("delta")).doubleValue();
            require(Double.isFinite(hp)&&hp>=0&&hp<=room.hp && Double.isFinite(delta),"دم غير صالح");
        } else if (type.equals("HOST_STATE")) {
            require(index == 0 && msg.get("tanks") instanceof List && ((List<?>) msg.get("tanks")).size() == room.count, "المضيف يزامن الحالة");
        } else if (type.equals("CONTROL") || type.equals("FIRE_REQUEST")) {
            require(index == room.turn && index == Json.number(msg,"idx") && !room.fired, "انتظر دورك");
            require(msg.get("aimAngle") instanceof Number && Double.isFinite(((Number)msg.get("aimAngle")).doubleValue()), "زاوية غير صالحة");
            if(type.equals("CONTROL")) require(msg.get("keys") instanceof Map, "تحكم غير صالح");
            else {
                require(msg.get("power") instanceof Number, "قوة غير صالحة");
                double power=((Number)msg.get("power")).doubleValue();
                require(Double.isFinite(power) && power>=0 && power<=1,"قوة غير صالحة");
            }
        } else if (type.equals("ENVIRONMENT")) {
            require(index == 0 && !room.fired,"المضيف يعتمد تغير الأرض");room.fired=true;
        } else if (type.equals("LAN_RESULT")) {
            require(index == 0 && room.fired, "المضيف يعتمد نتيجة الضربة");
            Object tanks = msg.get("tanks");
            require(tanks instanceof List && ((List<?>) tanks).size() == room.count, "نتيجة غير صالحة");
            int next = Json.number(msg, "nextIdx");
            require(next >= 0 && next < room.count && msg.get("done") instanceof Boolean, "نتيجة غير صالحة");
            room.turn = next;
            room.sequence++;
            room.fired = false;
            room.done = (Boolean) msg.get("done");
        } else {
            require((type.equals("FIRE") || type.equals("SYNC_TANK"))
                    && !room.fired && index == 0 && room.turn == Json.number(msg, "idx"), "انتظر دورك");
            for (String key : new String[]{"x", "y", "hp", "fuel", "aimAngle", "vx", "vy", "slope"}) {
                require(msg.get(key) instanceof Number && Double.isFinite(((Number) msg.get(key)).doubleValue()), "حركة غير صالحة");
            }
            double x = ((Number) msg.get("x")).doubleValue(), y = ((Number) msg.get("y")).doubleValue();
            double fuel = ((Number) msg.get("fuel")).doubleValue();
            require(x >= 28 && x <= 1572 && y >= 0 && y <= 900 && fuel >= 0 && fuel <= 100, "حركة غير صالحة");
            if (type.equals("FIRE")) {
                require(msg.get("power") instanceof Number, "قوة غير صالحة");
                double power = ((Number) msg.get("power")).doubleValue();
                require(Double.isFinite(power) && power >= 0 && power <= 1, "قوة غير صالحة");
                Json.number(msg, "shotSeed");
                room.fired = true;
            }
        }
        if(index==0 && room.world!=null && (type.equals("FIRE")||type.equals("ENVIRONMENT"))){room.world.put("phase",type.equals("FIRE")?"firing":"settle");room.world.put("activeIdx",room.turn);room.world.put("revision",revision);}
        if(index==0 && type.equals("HOST_STATE")){
            Map<String,Object> previous=room.world;
            room.world=Json.map(Json.parse(Json.stringify(msg)));room.world.remove("cuts");room.world.remove("rects");
            if(previous!=null){List<?> old=(List<?>)previous.get("tanks"),next=(List<?>)room.world.get("tanks");for(int i=0;i<next.size();i++){Map<String,Object> t=Json.map(next.get(i));if(!t.containsKey("ammo") && old!=null && i<old.size())t.put("ammo",Json.map(old.get(i)).get("ammo"));}}
        }
        if(index==0 && type.equals("HEALTH_EVENT") && revision>=room.revision && room.world!=null){
            List<?> tanks=(List<?>)room.world.get("tanks");int target=Json.number(msg,"idx");
            if(tanks!=null && target<tanks.size()){Map<String,Object> tank=Json.map(tanks.get(target));tank.put("hp",msg.get("hp"));tank.put("shield",msg.get("shield"));room.world.put("revision",revision);}
        }
        if(index==0 && type.equals("LAN_RESULT")){
            room.world=Json.map(Json.parse(Json.stringify(msg)));room.world.remove("cuts");room.world.remove("rects");room.world.put("phase",room.done?"gameover":"aim");room.world.put("activeIdx",room.turn);room.world.put("turnSeq",room.sequence);
        }
        if(index==0)room.revision=Math.max(room.revision,revision);
        emit(index, msg);
    }

    private void emit(int index, Map<String, Object> msg) {
        Map<String,Object> event=Json.object("id",++room.cursor,"sender",index,"msg",msg);
        int bytes=Json.stringify(event).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        room.events.addLast(event);room.eventBytes+=bytes;
        while(room.events.size()>MAX_EVENTS || room.eventBytes>MAX_EVENT_BYTES){Map<String,Object> old=room.events.removeFirst();room.eventBytes-=Json.stringify(old).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
        notifyAll();
    }
}
