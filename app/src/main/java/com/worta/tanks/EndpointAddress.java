package com.worta.tanks;
import java.net.*;
/** Only literal local IPv4 addresses are accepted; never resolve arbitrary host names. */
final class EndpointAddress {
    final InetAddress address;final int port;
    private EndpointAddress(InetAddress address,int port){this.address=address;this.port=port;}
    static EndpointAddress parse(String text){
        try{
            String[] parts=text.trim().split(":",-1);if(parts.length!=2 || !parts[0].matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || !parts[1].matches("[0-9]{1,5}"))throw new Exception();
            for(String octet:parts[0].split("\\."))if(Integer.parseInt(octet)>255)throw new Exception();
            InetAddress address=InetAddress.getByName(parts[0]);int port=Integer.parseInt(parts[1]);
            if(port<1||port>65535||!(address.isSiteLocalAddress()||address.isLinkLocalAddress()||address.isLoopbackAddress()))throw new Exception();
            return new EndpointAddress(address,port);
        }catch(Exception invalid){throw new IllegalArgumentException("أدخل عنوان IPv4 محلياً مع المنفذ، مثل 192.168.1.5:54321");}
    }
}
