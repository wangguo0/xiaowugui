package com.fongmi.android.tv.remote;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.remote.RemoteModels.PollResponse;
import com.fongmi.android.tv.remote.RemoteModels.RemoteCommand;
import com.fongmi.android.tv.remote.RemoteModels.RemoteCommandResult;
import com.fongmi.android.tv.remote.RemoteModels.RemoteProfile;
import com.fongmi.android.tv.remote.RemoteModels.RemoteStoreFile;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class RemoteAgent {

    private static final long POLL_INTERVAL_MS = 4_000L;
    private static final long REGISTER_INTERVAL_MS = 60_000L;

    private static volatile RemoteAgent instance;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private RemoteAgent() {
    }

    public static RemoteAgent get() {
        if (instance == null) {
            synchronized (RemoteAgent.class) {
                if (instance == null) instance = new RemoteAgent();
            }
        }
        return instance;
    }

    public void start() {
        start(true);
    }

    void startFromService() {
        start(false);
    }

    public synchronized void stop() {
        for (Session session : sessions.values()) session.stop();
        sessions.clear();
        RemoteAgentService.stop(App.get());
    }

    public boolean isRunning() {
        return !sessions.isEmpty();
    }

    private synchronized void start(boolean manageService) {
        try {
            RemoteStore.reloadFromFile();
            RemoteStoreFile store = RemoteStore.get();
            Set<String> active = new HashSet<>();
            boolean keepOnline = false;
            for (RemoteProfile profile : store.profiles) {
                if (!RemoteStore.shouldStart(profile)) continue;
                active.add(profile.serverOrigin);
                keepOnline |= profile.keepOnline;
                Session session = sessions.get(profile.serverOrigin);
                if (session == null) {
                    session = new Session(profile.serverOrigin);
                    sessions.put(profile.serverOrigin, session);
                }
                session.start();
            }
            for (Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Session> entry = it.next();
                if (active.contains(entry.getKey())) continue;
                entry.getValue().stop();
                it.remove();
            }
            if (manageService) {
                if (keepOnline) RemoteAgentService.start(App.get());
                else RemoteAgentService.stop(App.get());
            }
        } catch (Throwable e) {
            SpiderDebug.log("remote", "agent start failed error=%s", e.getMessage());
        }
    }

    private static final class Session {
        private final String serverOrigin;
        private volatile ScheduledFuture<?> future;
        private volatile boolean busy;
        private volatile long lastRegister;
        private volatile long lastErrorLog;

        private Session(String serverOrigin) {
            this.serverOrigin = serverOrigin;
        }

        private synchronized void start() {
            if (future != null && !future.isCancelled()) return;
            future = Task.scheduler().scheduleWithFixedDelay(() -> Task.execute(this::pollSafely), 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            SpiderDebug.log("remote", "session started origin=%s", serverOrigin);
        }

        private synchronized void stop() {
            if (future != null) future.cancel(false);
            future = null;
            SpiderDebug.log("remote", "session stopped origin=%s", serverOrigin);
        }

        private void pollSafely() {
            if (busy) return;
            busy = true;
            try {
                RemoteProfile profile = RemoteStore.getProfileByOrigin(serverOrigin);
                if (!RemoteStore.shouldStart(profile)) return;
                RemoteClient client = new RemoteClient(profile);
                long now = System.currentTimeMillis();
                if (lastRegister <= 0 || now - lastRegister > REGISTER_INTERVAL_MS) {
                    client.capabilities();
                    client.register();
                    profile.updatedAt = now;
                    RemoteStore.upsertProfile(profile);
                    lastRegister = now;
                }
                PollResponse response = client.poll();
                RemoteCommand command = response == null ? null : response.command;
                if (command == null || TextUtils.isEmpty(command.id)) return;
                SpiderDebug.log("remote", "command received origin=%s id=%s type=%s", serverOrigin, command.id, command.type);
                RemoteCommandResult result = RemoteCommandExecutor.execute(profile, command);
                client.commandResult(command.id, result);
            } catch (Throwable e) {
                if (System.currentTimeMillis() - lastErrorLog > 30_000L) {
                    lastErrorLog = System.currentTimeMillis();
                    SpiderDebug.log("remote", "poll failed origin=%s error=%s", serverOrigin, e.getMessage());
                }
                lastRegister = 0;
            } finally {
                busy = false;
            }
        }
    }
}
