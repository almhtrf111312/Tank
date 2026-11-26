package com.worta.tanks;

import java.nio.charset.StandardCharsets;

/** Test-only launcher used to exercise the exact native HTTP server with browser clients. */
public final class DevelopmentServer {
    public static void main(String[] args) throws Exception {
        RoomEngine engine = new RoomEngine();
        LocalHttpServer server = new LocalHttpServer((method, path, headers, body, local) ->
            LocalHttpServer.Response.json(200,
                engine.call(path.substring(6), Json.map(Json.parse(new String(body, StandardCharsets.UTF_8))))));
        server.start();
        System.out.println(server.port());
        System.out.flush();
        Thread.currentThread().join();
    }
}
