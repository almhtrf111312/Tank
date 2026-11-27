package com.worta.tanks;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;

final class RoomEngine {
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
    synchronized Object listing() {return Json.object("rooms",room==null?Collections.emptyList():Collections.singletonList(Json.object("code",room.code,"name",room.name)));}
    private String cleanName(Map<String,Object> data,String key,String fallback){
        String value=Json.text(data,key).replaceAll("[\\p{Cntrl}]", "").trim();
        if(value.isEmpty())return fallback;
        return value.substring(0,value.offsetByCodePoints(0,Math.min(32,value.codePointCount(0,value.length()))));
    }
    synchronized void clear() { room = null; receipts.clear(); }

    synchronized Object call(String action, Map<String, Object> data) {
        String request = Json.text(data, "requestId");
        String receiptKey = action + "/" + Json.text(data, "token") + "/" + request;
        if (!request.isEmpty() && receipts.containsKey(receiptKey)) return receipts.get(receiptKey);
        Object result = execute(action, data);
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
            room.code = Integer.toString(1000 + random.nextInt(9000));
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
        if (now - room.seen >= 240_000) {
            clear();
            throw new IllegalArgumentException("انتهت جلسة الغرفة");
        }
        for (Iterator<Map.Entry<String, Player>> it = room.players.entrySet().iterator(); it.hasNext();) {
            Player p = it.next().getValue();
            if (now - p.seen < 180_000) continue;
            if (room.started || p.index == 0) {
                clear();
                throw new IllegalArgumentException("انقطع اتصال أحد اللاعبين. أنشئ غرفة جديدة");
            }
            it.remove();
        }
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
                require(room.events.isEmpty() || cursor >= Json.number(room.events.peekFirst(), "id") - 1,
                        "انقطع التزامن. أنشئ غرفة جديدة");
                List<Object> roster = new ArrayList<>(), events = new ArrayList<>();
                for (Player p : room.players.values()) roster.add(Json.object("idx", p.index, "ready", p.ready, "connected", now - p.seen < 8000,"approved",p.approved,"team",p.team,"name",p.name));
                for (Map<String, Object> e : room.events) if (Json.number(e, "id") > cursor) events.add(e);
                return Json.object("players", roster, "events", events, "started", room.started,"format",room.format,"name",room.name,"remainingMs",Math.max(0,room.deadline-now));
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
                room.turn = room.sequence = 0;
                room.match = token();
                emit(index, Json.object("type", "START", "seed", random.nextInt(1_000_000_000),
                        "wind", 0, "theme", theme, "matchId", room.match,"format",room.format,"seriesLength",room.seriesLength,"seriesRound",room.seriesRound,"trajectory",room.trajectory,"players",room.players.values().stream().map(p->Json.object("idx",p.index,"team",p.team,"name",p.name)).collect(java.util.stream.Collectors.toList())));
                break;
            case "packet":
                packet(index, Json.map(data.get("msg")));
                break;
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
        return Json.object("code", room.code, "token", token, "idx", index, "count", room.count, "hp", room.hp,"name",room.name,"format",room.format);
    }

    private void packet(int index, Map<String, Object> msg) {
        require(room.started && !room.done && room.match.equals(Json.text(msg, "matchId"))
                && room.sequence == Json.number(msg, "turnSeq"), "تغير الدور");
        String type = Json.text(msg, "type");
        if(type.equals("HEALTH_EVENT")){
            require(index==0,"المضيف يعتمد الضرر");
            int victim=Json.number(msg,"idx");require(victim>=0 && victim<room.count,"دبابة غير صالحة");
            require(msg.get("hp") instanceof Number && msg.get("delta") instanceof Number,"ضرر غير صالح");
            double hp=((Number)msg.get("hp")).doubleValue(),delta=((Number)msg.get("delta")).doubleValue();
            require(Double.isFinite(hp)&&hp>=0&&hp<=room.hp && Double.isFinite(delta),"دم غير صالح");
        } else if (type.equals("HOST_STATE")) {
            require(index == 0 && !room.fired && msg.get("tanks") instanceof List && ((List<?>) msg.get("tanks")).size() == room.count, "المضيف يزامن الحالة");
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
        emit(index, msg);
    }

    private void emit(int index, Map<String, Object> msg) {
        room.events.addLast(Json.object("id", ++room.cursor, "sender", index, "msg", msg));
        if (room.events.size() > 12000) room.events.removeFirst();
    }
}
