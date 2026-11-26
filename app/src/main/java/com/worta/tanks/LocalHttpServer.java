package com.worta.tanks;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Bounded HTTP endpoint. Every connection handles one request and closes. */
final class LocalHttpServer implements Closeable {
    interface Handler {
        Response handle(String method, String path, Map<String, String> headers, byte[] body, boolean local) throws Exception;
    }
    static final class Response {
        final int status;
        final String type;
        final byte[] body;
        Response(int status, String type, byte[] body) { this.status = status; this.type = type; this.body = body; }
        static Response json(int status, Object value) {
            return new Response(status, "application/json; charset=utf-8", Json.stringify(value).getBytes(StandardCharsets.UTF_8));
        }
    }
    private final ServerSocket server;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    private final Handler handler;
    LocalHttpServer(Handler handler) throws IOException {
        this.handler = handler;
        server = new ServerSocket(0, 16);
    }
    int port() { return server.getLocalPort(); }
    void start() {
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    try { workers.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException busy) { socket.close(); }
                } catch (IOException stopped) { if (!server.isClosed()) closeQuietly(); }
            }
        }, "worta-http");
        accept.setDaemon(true);
        accept.start();
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') return text.toString(StandardCharsets.US_ASCII.name()).replace("\r", "");
            if (text.size() >= 8192) throw new IOException("Header too long");
            text.write(c);
        }
        throw new EOFException();
    }
    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(8000);
            InputStream input = new BufferedInputStream(connection.getInputStream());
            Response response;
            try {
                String[] start = line(input).split(" ");
                if (start.length != 3) throw new IOException("Request line");
                Map<String, String> headers = new HashMap<>();
                for (int i = 0; ; i++) {
                    String field = line(input);
                    if (field.isEmpty()) break;
                    if (i >= 40 || !field.contains(":")) throw new IOException("Invalid headers");
                    int colon = field.indexOf(':');
                    String key = field.substring(0, colon).toLowerCase(Locale.ROOT);
                    if (headers.containsKey(key)) throw new IOException("Repeated header");
                    headers.put(key, field.substring(colon + 1).trim());
                }
                int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                if (length < 0 || length > 262144 || headers.containsKey("transfer-encoding"))
                    throw new IOException("Body size");
                byte[] body = new byte[length];
                new DataInputStream(input).readFully(body);
                response = handler.handle(start[0], start[1], headers, body, connection.getInetAddress().isLoopbackAddress());
            } catch (Exception invalid) {
                response = Response.json(400, Json.object("error",
                        invalid instanceof IllegalArgumentException ? invalid.getMessage() : "تعذّر تنفيذ الطلب"));
            }
            OutputStream output = connection.getOutputStream();
            String header = "HTTP/1.1 " + response.status + " " + (response.status == 200 ? "OK" : "Error")
                    + "\r\nContent-Type: " + response.type + "\r\nContent-Length: " + response.body.length
                    + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"
                    + "\r\nConnection: close\r\n\r\n";
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            output.write(response.body);
            output.flush();
        } catch (IOException ignored) {
            // A peer may leave while its HTTP response is being sent.
        }
    }
    private void closeQuietly() { try { close(); } catch (IOException ignored) {} }
    @Override public void close() throws IOException { server.close(); workers.shutdownNow(); }
}
