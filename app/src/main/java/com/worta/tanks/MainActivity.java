package com.worta.tanks;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.*;
import android.webkit.*;
import android.widget.Toast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends Activity {
    private final RoomEngine engine = new RoomEngine();
    private WebView web;
    private LocalHttpServer server;
    private Discovery discovery;
    private WifiManager.MulticastLock multicast;
    private PowerManager.WakeLock awake;
    private volatile Discovery.Endpoint host;
    private byte[] page;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        try {
            page = read(getAssets().open("index.html"));
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicast = wifi.createMulticastLock("worta-rooms");
            multicast.setReferenceCounted(false);
            multicast.acquire();
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "worta:match");
            awake.acquire(4 * 60 * 60 * 1000L);
            discovery = new Discovery(this);
            server = new LocalHttpServer(this::handle);
            server.start();
        } catch (Exception failure) {
            Toast.makeText(this, "تعذّر تشغيل الشبكة المحلية", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("http://127.0.0.1:" + server.port() + "/");
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new ControlPreferences(), "TankPreferences");
        setContentView(web);
        web.loadUrl("http://127.0.0.1:" + server.port() + "/");
    }

    private LocalHttpServer.Response handle(String method, String path, Map<String, String> headers,
                                            byte[] body, boolean local) throws Exception {
        if (method.equals("GET") && path.equals("/") && local)
            return new LocalHttpServer.Response(200, "text/html; charset=utf-8", page);
        if (!method.equals("POST") || !path.matches("/room/(create|join|poll|ready|start|packet|leave|discover|team|decision)"))
            return LocalHttpServer.Response.json(404, Json.object("error", "طلب غير موجود"));
        String origin = headers.get("origin");
        if (origin != null && !origin.equals("http://127.0.0.1:" + server.port()))
            return LocalHttpServer.Response.json(403, Json.object("error", "مصدر غير مسموح"));
        String action = path.substring("/room/".length());
        Map<String, Object> data = Json.map(Json.parse(new String(body, StandardCharsets.UTF_8)));
        if(action.equals("discover")) {
            if(!local)return LocalHttpServer.Response.json(403,Json.object("error","البحث من الجهاز فقط"));
            return LocalHttpServer.Response.json(200,Json.object("rooms",discovery.rooms()));
        }
        if (action.equals("create")) {
            if (!local) return LocalHttpServer.Response.json(403, Json.object("error", "المضيف ينشئ الغرفة"));
            host = null;
            Object result = engine.call(action, data);
            try {
                discovery.advertise(engine.code(), engine.name(), server.port());
            } catch (Exception failed) {
                engine.clear();
                discovery.unadvertise();
                throw new IllegalArgumentException("تعذّر إعلان الغرفة. تأكد من الواي فاي ثم أعد المحاولة");
            }
            return LocalHttpServer.Response.json(200, result);
        }
        if (action.equals("join") && local && !engine.code().equals(Json.text(data, "code"))) {
            try { host = discovery.find(Json.text(data, "code")); }
            catch (IOException failed) { throw new IllegalArgumentException(failed.getMessage()); }
        }
        Discovery.Endpoint target = host;
        if (target != null && local) {
            LocalHttpServer.Response response;
            try { response = forward(target, path, body); }
            catch (IOException disconnected) {
                // Router address changes can be recovered using the same four-digit room and token.
                host = discovery.find(Json.text(data, "code"));
                response = forward(host, path, body);
            }
            if (action.equals("leave")) host = null;
            return response;
        }
        Object result = engine.call(action, data);
        if (engine.code().isEmpty()) discovery.unadvertise();
        return LocalHttpServer.Response.json(200, result);
    }

    private LocalHttpServer.Response forward(Discovery.Endpoint target, String path, byte[] body) throws IOException {
        URL url;
        try { url = new URI("http", null, target.address.getHostAddress(), target.port, path, null, null).toURL(); }
        catch (URISyntaxException invalid) { throw new IOException(invalid); }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            try (InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                return new LocalHttpServer.Response(status, "application/json; charset=utf-8", read(input));
            }
        } finally { connection.disconnect(); }
    }

    public final class ControlPreferences {
        @JavascriptInterface public String getControls() {
            return getSharedPreferences("tank-settings", MODE_PRIVATE).getString("controls-landscape", "{}");
        }
        @JavascriptInterface public void saveControls(String value) {
            if (value != null && value.length() <= 4096)
                getSharedPreferences("tank-settings", MODE_PRIVATE).edit().putString("controls-landscape", value).apply();
        }
    }

    private static byte[] read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Empty response");
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = source.read(buffer)) >= 0) {
                if (output.size() + length > 2_000_000) throw new IOException("Response too large");
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    @Override protected void onDestroy() {
        if (web != null) { web.stopLoading(); web.destroy(); }
        if (discovery != null) discovery.close();
        if (server != null) { try { server.close(); } catch (IOException ignored) {} }
        if (multicast != null && multicast.isHeld()) multicast.release();
        if (awake != null && awake.isHeld()) awake.release();
        super.onDestroy();
    }
}
