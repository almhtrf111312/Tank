package com.worta.tanks;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded persistent HTTP/1.1 endpoint. Fully framed responses permit socket reuse. */
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
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 32, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private final Handler handler;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    final AtomicLong connections=new AtomicLong(),requests=new AtomicLong(),responseBytes=new AtomicLong();
    LocalHttpServer(Handler handler) throws IOException {
        this.handler = handler;
        server = new ServerSocket(0, 16);
    }
    int port() { return server.getLocalPort(); }
    void start() {
        Thread accept = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();socket.setTcpNoDelay(true);sockets.add(socket);connections.incrementAndGet();
                    try { workers.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException busy) { sockets.remove(socket);socket.close(); }
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
            connection.setSoTimeout(10000);
            InputStream input = new BufferedInputStream(connection.getInputStream());
            OutputStream output=connection.getOutputStream();
            for(int served=0;served<100;served++){
            Response response;boolean keep=served<99;
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
                if (length < 0 || length > 4194304 || headers.containsKey("transfer-encoding"))
                    throw new IOException("Body size");
                byte[] body = new byte[length];
                new DataInputStream(input).readFully(body);
                keep &= !"close".equalsIgnoreCase(headers.get("connection"));requests.incrementAndGet();
                response = handler.handle(start[0], start[1], headers, body, connection.getInetAddress().isLoopbackAddress());
            } catch (EOFException | SocketTimeoutException idle) {return;} catch (Exception invalid) {
                keep=false;
                response = Response.json(invalid instanceof IOException?503:400, Json.object("retryable",invalid instanceof IOException,"error",
                        invalid instanceof IllegalArgumentException ? invalid.getMessage() : "تعذّر تنفيذ الطلب"));
            }
            String header = "HTTP/1.1 " + response.status + " " + (response.status == 200 ? "OK" : "Error")
                    + "\r\nContent-Type: " + response.type + "\r\nContent-Length: " + response.body.length
                    + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff"
                    + "\r\nConnection: "+(keep?"keep-alive":"close")+"\r\nKeep-Alive: timeout=10, max=100\r\n\r\n";
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            output.write(response.body);
            output.flush();responseBytes.addAndGet(response.body.length);if(!keep)return;
            }
        } catch (IOException ignored) {
            // A peer may leave while its HTTP response is being sent.
        } finally{sockets.remove(socket);}
    }
    private void closeQuietly() { try { close(); } catch (IOException ignored) {} }
    @Override public void close() throws IOException { server.close();for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}sockets.clear();workers.shutdownNow(); }
}
