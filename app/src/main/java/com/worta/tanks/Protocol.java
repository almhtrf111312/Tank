package com.worta.tanks;
final class Protocol {
    static final int VERSION=2;
    static void requireVersion(java.util.Map<String,Object> data){
        if(!(data.get("protocol") instanceof Number)||Json.number(data,"protocol")!=VERSION)
            throw new IllegalArgumentException("إصدار اللعبة غير متوافق؛ حدّث جميع الأجهزة إلى نفس إصدار بروتوكول الشبكة (2)");
    }
}
