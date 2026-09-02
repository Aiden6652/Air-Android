package net.kdt.pojavlaunch.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 会话存储（对应 iOS AiSessionStore）：单例。
 * sessions 读写 &lt;DIR_GAME_HOME&gt;/ai/sessions.json（按 updatedAt 倒序），
 * 最近会话 id 存 SharedPreferences。
 * 注意：IO 必须在后台线程调用（Agent 循环线程 / sExecutorService）。
 */
public class AiSessionStore {
    private static volatile AiSessionStore sInstance;
    private final List<AiSession> mSessions = new ArrayList<>();
    private boolean mLoaded = false;

    public static AiSessionStore getInstance() {
        if (sInstance == null) {
            synchronized (AiSessionStore.class) {
                if (sInstance == null) sInstance = new AiSessionStore();
            }
        }
        return sInstance;
    }

    private AiSessionStore() {}

    private static File storeFile() {
        return new File(Tools.DIR_GAME_HOME, "ai/sessions.json");
    }

    private synchronized void ensureLoaded() {
        if (mLoaded) return;
        mLoaded = true;
        File file = storeFile();
        if (file.exists()) {
            try {
                String json = Tools.read(file.getAbsolutePath());
                Type type = new TypeToken<List<AiSession>>() {}.getType();
                List<AiSession> list = new Gson().fromJson(json, type);
                if (list != null) {
                    mSessions.addAll(list);
                    for (AiSession s : mSessions) {
                        if (s.messages == null) s.messages = new ArrayList<>();
                    }
                }
            } catch (Exception ignored) {}
        }
        sortSessions();
    }

    private void sortSessions() {
        Collections.sort(mSessions, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
    }

    private void persist() {
        try {
            sortSessions();
            File file = storeFile();
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            Tools.write(file.getAbsolutePath(), new Gson().toJson(mSessions));
        } catch (Exception ignored) {}
    }

    /** 创建并持久化新会话（后台线程调用） */
    public synchronized AiSession newSession() {
        ensureLoaded();
        AiSession session = AiSession.newSession();
        session.title = "AI 助手";
        mSessions.add(0, session);
        persist();
        setLastActiveSessionId(session.identifier);
        return session;
    }

    public synchronized void deleteSession(AiSession session) {
        ensureLoaded();
        mSessions.remove(session);
        persist();
    }

    public synchronized void updateSession(AiSession session) {
        ensureLoaded();
        session.updatedAt = System.currentTimeMillis();
        if (!mSessions.contains(session)) mSessions.add(session);
        persist();
    }

    public synchronized AiSession sessionById(String identifier) {
        ensureLoaded();
        for (AiSession s : mSessions) {
            if (s.identifier.equals(identifier)) return s;
        }
        return null;
    }

    /** 最近会话：优先 last_session_id，否则最新会话，无则 null */
    public synchronized AiSession lastActiveSession() {
        ensureLoaded();
        String lastId = PojavApplication.getAppContext()
                .getSharedPreferences("ai_settings", 0)
                .getString("ai.last_session_id", null);
        if (lastId != null) {
            AiSession s = sessionById(lastId);
            if (s != null) return s;
        }
        return mSessions.isEmpty() ? null : mSessions.get(0);
    }

    public synchronized void setLastActiveSessionId(String identifier) {
        PojavApplication.getAppContext()
                .getSharedPreferences("ai_settings", 0)
                .edit()
                .putString("ai.last_session_id", identifier)
                .apply();
    }

    /** 自动命名：取消息前 20 字符 */
    public static String autoTitleForMessage(String message) {
        if (message == null) return "";
        String trimmed = message.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20) + "…";
    }
}
