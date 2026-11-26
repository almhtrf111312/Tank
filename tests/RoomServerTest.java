package com.worta.tanks;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class RoomServerTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Operation should have been rejected");
    }
    static Map<String, Object> auth(Map<String, Object> room, Object... extra) {
        Map<String, Object> value = Json.object("code", room.get("code"), "token", room.get("token"));
        value.putAll(Json.object(extra));
        return value;
    }
    static Map<String, Object> http(LocalHttpServer server, String action, Map<String, Object> body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + server.port() + "/room/" + action).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            try (InputStream input = connection.getInputStream()) {
                return Json.map(Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        } finally { connection.disconnect(); }
    }

    public static void main(String[] args) throws Exception {
        Object json = Json.parse("{\"عربي\":[true,false,null,1.25,-2e3,\"a\\nب\\u1234\"]}");
        check(Json.stringify(Json.parse(Json.stringify(json))).equals(Json.stringify(json)), "JSON roundtrip");
        rejected(() -> Json.parse("[1,]"));
        rejected(() -> Json.parse("{\"a\":NaN}"));
        rejected(() -> Json.parse("01"));
        for (int count : new int[]{2, 3, 4}) {
            RoomEngine engine = new RoomEngine();
            Map<String, Object> host = Json.map(engine.call("create", Json.object("count", count, "hp", count * 100 - 100, "requestId", "create")));
            check(Json.text(host, "code").matches("[0-9]{4}"), "Room code");
            check(engine.call("create", Json.object("count", count, "hp", count * 100 - 100, "requestId", "create")) == host, "Idempotent create");
            List<Map<String, Object>> players = new ArrayList<>();
            players.add(host);
            rejected(() -> engine.call("start", auth(host, "theme", "aurora")));
            for (int i = 1; i < count; i++) {
                Map<String, Object> guest = Json.map(engine.call("join", Json.object("code", host.get("code"), "requestId", "join-" + i)));
                players.add(guest);
            }
            rejected(() -> engine.call("join", Json.object("code", host.get("code"))));
            rejected(() -> engine.call("start", auth(host, "theme", "aurora")));
            for (Map<String, Object> p : players) engine.call("ready", auth(p));
            rejected(() -> engine.call("start", auth(players.get(1), "theme", "aurora")));
            engine.call("start", auth(host, "theme", "aurora"));
            Map<String, Object> poll = Json.map(engine.call("poll", auth(host, "cursor", 0)));
            Map<String, Object> event = Json.map(((List<?>) poll.get("events")).get(0));
            String match = Json.text(Json.map(event.get("msg")), "matchId");
            for (int i = 0; i < count; i++) {
                Map<String, Object> fire = Json.object("type", "FIRE", "idx", i, "turnSeq", i, "matchId", match,
                        "x", 200, "y", 400, "fuel", 95, "hp", 200, "aimAngle", -.8, "vx", 0, "vy", 0, "slope", 0,
                        "power", .75, "shotSeed", 123, "weaponId", "shell");
                int other = (i + 1) % count;
                final int actor = i;
                rejected(() -> engine.call("packet", auth(players.get(other), "msg", fire)));
                Map<String, Object> request = auth(players.get(i), "msg", fire, "requestId", "fire-" + i);
                engine.call("packet", request);
                engine.call("packet", request);
                rejected(() -> engine.call("packet", auth(players.get(actor), "msg", fire)));
                Map<String, Object> result = Json.object("type", "LAN_RESULT", "turnSeq", i, "matchId", match,
                        "nextIdx", other, "done", i == count - 1, "tanks", Collections.nCopies(count, Json.object()));
                rejected(() -> engine.call("packet", auth(players.get(1), "msg", result)));
                engine.call("packet", auth(host, "msg", result));
            }
            poll = Json.map(engine.call("poll", auth(host, "cursor", 0)));
            check(((List<?>) poll.get("events")).size() == 1 + count * 2, "Ordered events, duplicate fire removed");
            engine.call("start", auth(host, "theme", "cyber"));
            engine.call("leave", auth(players.get(1)));
            check(engine.code().isEmpty(), "Leaving live match closes room");
        }

        // Concurrent joins must fill exactly the available seats.
        RoomEngine engine = new RoomEngine();
        Map<String, Object> host = Json.map(engine.call("create", Json.object("count", 4, "hp", 300)));
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<Boolean>> joined = new ArrayList<>();
        for (int i = 0; i < 6; i++) joined.add(executor.submit(() -> {
            try { engine.call("join", Json.object("code", host.get("code"))); return true; }
            catch (IllegalArgumentException full) { return false; }
        }));
        int accepted = 0;
        for (Future<Boolean> future : joined) if (future.get()) accepted++;
        executor.shutdownNow();
        check(accepted == 3, "Exactly three guests");

        AtomicLong clock = new AtomicLong();
        RoomEngine expiring = new RoomEngine(clock::get);
        Map<String, Object> expired = Json.map(expiring.call("create", Json.object("count", 2, "hp", 100)));
        clock.set(65_000);
        rejected(() -> expiring.call("poll", auth(expired, "cursor", 0)));

        RoomEngine httpEngine = new RoomEngine();
        try (LocalHttpServer server = new LocalHttpServer((method, path, headers, body, local) -> {
            check(local, "Loopback test");
            Object result = httpEngine.call(path.substring(6), Json.map(Json.parse(new String(body, StandardCharsets.UTF_8))));
            return LocalHttpServer.Response.json(200, result);
        })) {
            server.start();
            Map<String, Object> room = http(server, "create", Json.object("count", 2, "hp", 100));
            Map<String, Object> guest = http(server, "join", Json.object("code", room.get("code")));
            http(server, "ready", auth(guest));
            http(server, "start", auth(room, "theme", "canyon"));
            Map<String, Object> result = http(server, "poll", auth(guest, "cursor", 0));
            check(((List<?>) result.get("events")).size() == 1, "Real HTTP room request");
        }
        System.out.println("PASS JSON, 2/3/4 players, HP, readiness, concurrent joins, duplicate requests, turn authority, rematch, expiry, native HTTP.");
    }
}
