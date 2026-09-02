package net.kdt.pojavlaunch.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 会话数据模型（对应 iOS AiSession） */
public class AiSession {
    public String identifier;
    public String title;
    public long createdAt;
    public long updatedAt;
    public List<AiMessage> messages = new ArrayList<>();

    public static AiSession newSession() {
        AiSession s = new AiSession();
        s.identifier = UUID.randomUUID().toString();
        s.title = "";
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        return s;
    }

    public static AiSession fromJson(String json) {
        Type type = new TypeToken<AiSession>() {}.getType();
        AiSession s = new Gson().fromJson(json, type);
        if (s != null && s.messages == null) s.messages = new ArrayList<>();
        return s;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }
}
