package com.fongmi.android.tv.player.danmaku;

import androidx.annotation.Nullable;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class LiveDanmakuWebSocketSession {

    private static final int NORMAL_CLOSE_CODE = 1000;

    private final LiveDanmakuWebSocketClient client;
    private final Listener listener;
    private WebSocket webSocket;
    private String currentUrl;
    private boolean released;

    public LiveDanmakuWebSocketSession(Listener listener) {
        this.client = new LiveDanmakuWebSocketClient();
        this.listener = listener;
    }

    public synchronized void connect(String url) {
        if (released || url == null || url.isBlank()) return;
        if (url.equals(currentUrl) && webSocket != null) return;
        stopLocked("replace");
        currentUrl = url;
        webSocket = client.newWebSocket(url, new SessionListener());
    }

    public synchronized void stop(String reason) {
        if (released) return;
        stopLocked(reason);
    }

    public synchronized void release() {
        if (released) return;
        stopLocked("release");
        released = true;
        client.release();
    }

    private void stopLocked(String reason) {
        WebSocket socket = webSocket;
        webSocket = null;
        currentUrl = null;
        if (socket != null && !socket.close(NORMAL_CLOSE_CODE, safeReason(reason))) socket.cancel();
    }

    private boolean isCurrent(WebSocket socket) {
        synchronized (this) {
            return !released && socket == webSocket;
        }
    }

    private boolean clearIfCurrent(WebSocket socket) {
        synchronized (this) {
            if (released || socket != webSocket) return false;
            webSocket = null;
            currentUrl = null;
            return true;
        }
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) return "stop";
        return reason.length() <= 48 ? reason : reason.substring(0, 48);
    }

    private final class SessionListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (isCurrent(webSocket)) listener.onOpen();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (isCurrent(webSocket)) listener.onMessage(text);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            if (isCurrent(webSocket)) webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (!clearIfCurrent(webSocket)) return;
            listener.onClosed(code, reason);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, @Nullable Response response) {
            if (!clearIfCurrent(webSocket)) return;
            listener.onFailure(throwable, response == null ? -1 : response.code());
        }
    }

    public interface Listener {

        void onOpen();

        void onMessage(String text);

        void onClosed(int code, String reason);

        void onFailure(Throwable throwable, int httpCode);
    }
}
