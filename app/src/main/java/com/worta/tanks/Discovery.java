package com.worta.tanks;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

final class Discovery implements AutoCloseable {
    static final String TYPE = "_worta._tcp.";
    static final class Endpoint {
        final InetAddress address;
        final int port;
        String name;int protocol;
        Endpoint(InetAddress address, int port) { this.address = address; this.port = port; }
    }
    private final NsdManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Endpoint> found = new HashMap<>();
    private final Deque<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean resolving, closed;
    private NsdManager.RegistrationListener registration;
    private final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
        @Override public void onDiscoveryStarted(String type) {}
        @Override public void onStartDiscoveryFailed(String type, int code) {}
        @Override public void onStopDiscoveryFailed(String type, int code) {}
        @Override public void onDiscoveryStopped(String type) {}
        @Override public void onServiceFound(NsdServiceInfo info) {
            main.post(() -> {
                if (!closed && info.getServiceName().matches("Worta-[0-9]{4,6}")) {
                    pending.add(info);
                    resolveNext();
                }
            });
        }
        @Override public void onServiceLost(NsdServiceInfo info) {
            synchronized (found) { found.remove(info.getServiceName()); found.notifyAll(); }
        }
    };

    Discovery(Context context) {
        manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        main.post(() -> {
            if (!closed) manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        });
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (closed || resolving || pending.isEmpty()) return;
        NsdServiceInfo info = pending.removeFirst();
        resolving = true;
        manager.resolveService(info, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo service, int code) { finish(); }
            @Override public void onServiceResolved(NsdServiceInfo service) {
                if (service.getHost() != null) {
                    synchronized (found) {
                        Endpoint endpoint=new Endpoint(service.getHost(),service.getPort());
                        byte[] label=service.getAttributes().get("roomName");
                        byte[] version=service.getAttributes().get("protocol");
                        try{endpoint.protocol=version==null?0:Integer.parseInt(new String(version,java.nio.charset.StandardCharsets.US_ASCII));}catch(NumberFormatException invalid){endpoint.protocol=0;}
                        endpoint.name=label==null?"غرفة الدبابات":new String(label,java.nio.charset.StandardCharsets.UTF_8);
                        found.put(service.getServiceName(), endpoint);
                        found.notifyAll();
                    }
                }
                finish();
            }
            private void finish() { main.post(() -> { resolving = false; resolveNext(); }); }
        });
    }

    Endpoint find(String code) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        synchronized (found) {
            while (!closed && !found.containsKey("Worta-" + code)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("لم تظهر الغرفة. تأكد من نفس الواي فاي وبقاء المضيف داخل اللعبة");
                TimeUnit.NANOSECONDS.timedWait(found, remaining);
            }
            Endpoint endpoint = found.get("Worta-" + code);
            if (endpoint == null) throw new IOException("أُغلق البحث عن الغرف");
            if(endpoint.protocol!=Protocol.VERSION)throw new IOException("إصدار اللعبة غير متوافق؛ حدّث جميع الأجهزة إلى بروتوكول الشبكة 2");
            return endpoint;
        }
    }

    Endpoint cached(String code){synchronized(found){return found.get("Worta-"+code);}}

    List<Object> rooms() {
        List<Object> rooms=new ArrayList<>();
        synchronized(found){for(Map.Entry<String,Endpoint> entry:found.entrySet())
            rooms.add(Json.object("code",entry.getKey().substring(6),"name",entry.getValue().name,"protocol",entry.getValue().protocol));}
        return rooms;
    }

    void advertise(String code, String name, int port) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        main.post(() -> {
            if (closed) { ready.completeExceptionally(new IOException("أُغلقت الشبكة")); return; }
            unadvertise();
            NsdServiceInfo service = new NsdServiceInfo();
            service.setServiceName("Worta-" + code);
            service.setServiceType(TYPE);
            service.setPort(port);
            service.setAttribute("roomName",name);
            service.setAttribute("protocol",Integer.toString(Protocol.VERSION));
            registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo info) {
                    if (!info.getServiceName().equals("Worta-" + code))
                        ready.completeExceptionally(new IOException("الرقم مستخدم على الشبكة. أعد إنشاء الغرفة"));
                    else ready.complete(null);
                }
                @Override public void onRegistrationFailed(NsdServiceInfo info, int error) {
                    ready.completeExceptionally(new IOException("تعذّر إعلان الغرفة على الواي فاي"));
                }
                @Override public void onServiceUnregistered(NsdServiceInfo info) {}
                @Override public void onUnregistrationFailed(NsdServiceInfo info, int error) {}
            };
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
        });
        ready.get(6, TimeUnit.SECONDS);
    }

    void unadvertise() {
        NsdManager.RegistrationListener old = registration;
        registration = null;
        if (old != null) {
            try { manager.unregisterService(old); } catch (IllegalArgumentException ignored) {}
        }
    }
    @Override public void close() {
        closed = true;
        synchronized (found) { found.notifyAll(); }
        main.post(() -> {
            unadvertise();
            try { manager.stopServiceDiscovery(listener); } catch (IllegalArgumentException ignored) {}
        });
    }
}
