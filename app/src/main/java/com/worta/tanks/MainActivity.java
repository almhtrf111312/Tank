package com.worta.tanks;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.SystemClock;
import java.util.concurrent.*;
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
    private volatile byte[] sessionAuth;
    private final ScheduledExecutorService keepAlive=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> backgroundTask;
    private long backgroundSince;
    private final android.os.Handler lifecycleHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable backgroundPump=new Runnable(){@Override public void run(){
        if(web!=null&&sessionAuth!=null&&host==null)web.evaluateJavascript("window.__tankBackgroundTick&&window.__tankBackgroundTick()",null);
        lifecycleHandler.postDelayed(this,50);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(android.os.Build.VERSION.SDK_INT>=33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::confirmExit);
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
        if (!method.equals("POST") || !path.matches("/room/(create|join|poll|ready|start|packet|leave|discover|team|decision|heartbeat|snapshot|checkpoint|info|local|diagnostics)"))
            return LocalHttpServer.Response.json(404, Json.object("error", "طلب غير موجود"));
        String origin = headers.get("origin");
        if (origin != null && !origin.equals("http://127.0.0.1:" + server.port()))
            return LocalHttpServer.Response.json(403, Json.object("error", "مصدر غير مسموح"));
        String action = path.substring("/room/".length());
        Map<String, Object> data = Json.map(Json.parse(new String(body, StandardCharsets.UTF_8)));
        if(action.equals("diagnostics")){
            if(!local || (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)return LocalHttpServer.Response.json(404,Json.object("error","غير متاح"));
            return LocalHttpServer.Response.json(200,Json.object("engine",engine.stats(),"connections",server.connections.get(),"requests",server.requests.get(),"responseBytes",server.responseBytes.get()));
        }
        if(action.equals("info"))return LocalHttpServer.Response.json(200,engine.call("info",data));
        if(action.equals("local")){
            if(!local)return LocalHttpServer.Response.json(403,Json.object("error","طلب محلي فقط"));
            List<String> addresses=new ArrayList<>();
            for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress address:Collections.list(network.getInetAddresses()))
                if(address instanceof Inet4Address&&!address.isLoopbackAddress())addresses.add(address.getHostAddress()+":"+server.port());
            return LocalHttpServer.Response.json(200,Json.object("addresses",addresses,"protocol",Protocol.VERSION));
        }
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
                discovery.unadvertise();
                Json.map(result).put("discoveryWarning","تعذّر إعلان الغرفة؛ يمكن الانضمام بعنوان IP والمنفذ");
            }
            rememberSession(result);
            return LocalHttpServer.Response.json(200, result);
        }
        if(action.equals("join") && local){
            String manual=Json.text(data,"manualAddress");
            if(!manual.isEmpty()){
                EndpointAddress endpoint=EndpointAddress.parse(manual);
                if(endpoint.port==server.port() && (endpoint.address.isLoopbackAddress()||NetworkInterface.getByInetAddress(endpoint.address)!=null))throw new IllegalArgumentException("هذا عنوان جهازك؛ أدخل عنوان جهاز المضيف الآخر");
                host=new Discovery.Endpoint(endpoint.address,endpoint.port);host.protocol=Protocol.VERSION;
                LocalHttpServer.Response info=forward(host,"/room/info",Json.stringify(Json.object("protocol",Protocol.VERSION)).getBytes(StandardCharsets.UTF_8));
                Map<String,Object> metadata=Json.map(Json.parse(new String(info.body,StandardCharsets.UTF_8)));
                if(info.status!=200)throw new IllegalArgumentException("إصدار اللعبة غير متوافق أو العنوان لا يشغّل غرفة بروتوكول 2");Protocol.requireVersion(metadata);if(Json.text(metadata,"code").isEmpty())throw new IllegalArgumentException("لا توجد غرفة على هذا العنوان");data.put("code",Json.text(metadata,"code"));body=Json.stringify(data).getBytes(StandardCharsets.UTF_8);
            }else if(!engine.code().equals(Json.text(data,"code"))){host=discovery.find(Json.text(data,"code"));}
        }
        Discovery.Endpoint target = host;
        if (target != null && local) {
            LocalHttpServer.Response response;
            try { response = forward(target, path, body); }
            catch (IOException disconnected) {
                Discovery.Endpoint fresh=discovery.cached(Json.text(data,"code"));
                if(fresh==null||fresh.protocol!=Protocol.VERSION||fresh.port==target.port&&fresh.address.equals(target.address))throw disconnected;
                host=fresh;response=forward(fresh,path,body);
            }
            if(action.equals("join") && response.status==200)rememberSession(Json.parse(new String(response.body,StandardCharsets.UTF_8)));
            if (action.equals("leave")) {host = null;sessionAuth=null;}
            return response;
        }
        Object result = engine.call(action, data);
        if(action.equals("leave") && local)sessionAuth=null;
        if (engine.code().isEmpty()) discovery.unadvertise();
        return LocalHttpServer.Response.json(200, result);
    }

    private LocalHttpServer.Response forward(Discovery.Endpoint target, String path, byte[] body) throws IOException {
        URL url;
        try { url = new URI("http", null, target.address.getHostAddress(), target.port, path, null, null).toURL(); }
        catch (URISyntaxException invalid) { throw new IOException(invalid); }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(2500);
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
        } catch(IOException failure){connection.disconnect();throw failure;}
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
                if (output.size() + length > 8_000_000) throw new IOException("Response too large");
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private void rememberSession(Object result) {
        Map<String,Object> value=Json.map(result);
        sessionAuth=Json.stringify(Json.object("protocol",Protocol.VERSION,"code",value.get("code"),"token",value.get("token"))).getBytes(StandardCharsets.UTF_8);
    }
    @Override public void onBackPressed() { confirmExit(); }
    private void confirmExit() {
        new AlertDialog.Builder(this).setTitle("هل تريد الخروج؟")
            .setMessage("الخروج يغلق جلستك. للرجوع خلال دقيقتين انتقل مؤقتاً لتطبيق آخر بزر الرئيسية.")
            .setNegativeButton("متابعة اللعب",(dialog,which)->dialog.dismiss())
            .setPositiveButton("خروج",(dialog,which)->finish()).show();
    }
    @Override protected void onPause() {
        super.onPause();backgroundSince=SystemClock.elapsedRealtime();lifecycleHandler.removeCallbacks(backgroundPump);lifecycleHandler.post(backgroundPump);
        if(backgroundTask!=null)backgroundTask.cancel(false);
        backgroundTask=keepAlive.scheduleWithFixedDelay(()->{
            byte[] auth=sessionAuth;if(auth==null)return;
            try {
                Discovery.Endpoint target=host;
                if(target!=null)forward(target,"/room/heartbeat",auth);
                else engine.call("heartbeat",Json.map(Json.parse(new String(auth,StandardCharsets.UTF_8))));
            } catch(Exception ignored) { /* Returning to the app resumes normal reconnect retries. */ }
        },0,10,TimeUnit.SECONDS);
    }
    @Override protected void onResume() {
        super.onResume();lifecycleHandler.removeCallbacks(backgroundPump);if(web!=null)web.evaluateJavascript("window.__tankResume&&window.__tankResume()",null);if(backgroundTask!=null){backgroundTask.cancel(false);backgroundTask=null;}
    }
    @Override protected void onDestroy() {
        lifecycleHandler.removeCallbacksAndMessages(null);keepAlive.shutdownNow();
        if (web != null) { web.stopLoading(); web.destroy(); }
        if (discovery != null) discovery.close();
        if (server != null) { try { server.close(); } catch (IOException ignored) {} }
        if (multicast != null && multicast.isHeld()) multicast.release();
        if (awake != null && awake.isHeld()) awake.release();
        super.onDestroy();
    }
}
