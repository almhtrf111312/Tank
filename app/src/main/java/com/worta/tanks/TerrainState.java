package com.worta.tanks;

import java.util.*;

/** Fixed-size authoritative collision mask; event retention never owns terrain history. */
final class TerrainState {
    static final int WIDTH=1600, HEIGHT=900;
    final byte[] mask=new byte[WIDTH*HEIGHT];
    int cuts,rects;
    void load(Object value,int cutCount,int rectCount){
        if(!(value instanceof List))throw new IllegalArgumentException("لقطة أرض غير صالحة");
        byte[] decoded=new byte[mask.length];int offset=0,bit=0;
        for(Object item:(List<?>)value){
            if(!(item instanceof Number))throw new IllegalArgumentException("لقطة أرض غير صالحة");
            double number=((Number)item).doubleValue();int n=(int)number;
            if(n<0 || n!=number || n>mask.length-offset)throw new IllegalArgumentException("لقطة أرض غير صالحة");
            if(bit==1)Arrays.fill(decoded,offset,offset+n,(byte)1);offset+=n;bit^=1;
        }
        if(offset!=mask.length || cutCount<0 || rectCount<0)throw new IllegalArgumentException("لقطة أرض غير مكتملة");
        System.arraycopy(decoded,0,mask,0,mask.length);cuts=cutCount;rects=rectCount;
    }
    List<Integer> encode(){
        List<Integer> runs=new ArrayList<>();int bit=0,count=0;
        for(byte pixel:mask){if(pixel!=bit){runs.add(count);count=0;bit=pixel;}count++;}runs.add(count);return runs;
    }
    void delta(Map<String,Object> msg){
        validate(msg,"cuts","cutBase",true);validate(msg,"rects","rectBase",false);
        apply(msg,"cuts","cutBase",true);apply(msg,"rects","rectBase",false);
    }
    private void validate(Map<String,Object> msg,String key,String baseKey,boolean circle){
        if(!msg.containsKey(key))return;
        if(!(msg.get(key) instanceof List))throw new IllegalArgumentException("دلتا أرض غير صالحة");
        int base=Json.number(msg,baseKey),current=circle?cuts:rects;
        if(base<0||base>current)throw new IllegalArgumentException("SNAPSHOT_REQUIRED");
        for(Object op:(List<?>)msg.get(key)){
            if(!(op instanceof List)||((List<?>)op).size()<(circle?3:4))throw new IllegalArgumentException("دلتا أرض غير صالحة");
            List<?> a=(List<?>)op;for(int k=0;k<(circle?3:4);k++)if(!(a.get(k) instanceof Number)||!Double.isFinite(((Number)a.get(k)).doubleValue())||Math.abs(((Number)a.get(k)).doubleValue())>10000)throw new IllegalArgumentException("دلتا أرض غير صالحة");
            if(((Number)a.get(2)).doubleValue()<0||(!circle&&((Number)a.get(3)).doubleValue()<0))throw new IllegalArgumentException("دلتا أرض غير صالحة");
        }
    }
    private void apply(Map<String,Object> msg,String key,String baseKey,boolean circle){
        if(!msg.containsKey(key))return;
        Object raw=msg.get(key);if(!(raw instanceof List))throw new IllegalArgumentException("دلتا أرض غير صالحة");
        List<?> operations=(List<?>)raw;int base=Json.number(msg,baseKey),current=circle?cuts:rects;
        if(base<0 || base>current)throw new IllegalArgumentException("SNAPSHOT_REQUIRED");
        // Validate the complete delta before mutating any pixels.
        for(Object op:operations){
            if(!(op instanceof List)||((List<?>)op).size()<(circle?3:4))throw new IllegalArgumentException("دلتا أرض غير صالحة");
            List<?> a=(List<?>)op;for(int k=0;k<(circle?3:4);k++)if(!(a.get(k) instanceof Number)||!Double.isFinite(((Number)a.get(k)).doubleValue())||Math.abs(((Number)a.get(k)).doubleValue())>10000)throw new IllegalArgumentException("دلتا أرض غير صالحة");
            if(((Number)a.get(2)).doubleValue()<0 || (!circle&&((Number)a.get(3)).doubleValue()<0))throw new IllegalArgumentException("دلتا أرض غير صالحة");
        }
        for(int i=Math.max(0,current-base);i<operations.size();i++){
            List<?> a=(List<?>)operations.get(i);double x=((Number)a.get(0)).doubleValue(),y=((Number)a.get(1)).doubleValue(),r=((Number)a.get(2)).doubleValue();
            if(circle){for(int row=Math.max(0,(int)Math.floor(y-r));row<Math.min(HEIGHT,(int)Math.ceil(y+r)+1);row++)for(int col=Math.max(0,(int)Math.floor(x-r));col<Math.min(WIDTH,(int)Math.ceil(x+r)+1);col++)if((col-x)*(col-x)+(row-y)*(row-y)<=r*r)mask[row*WIDTH+col]=0;}
            else {int x0=Math.max(0,(int)x),x1=Math.min(WIDTH,(int)(x+r));for(int row=Math.max(0,(int)y);row<Math.min(HEIGHT,(int)(y+((Number)a.get(3)).doubleValue()));row++)if(x1>=x0)Arrays.fill(mask,row*WIDTH+x0,row*WIDTH+x1,(byte)0);}
        }
        int next=Math.max(current,base+operations.size());if(circle)cuts=next;else rects=next;
    }
}
