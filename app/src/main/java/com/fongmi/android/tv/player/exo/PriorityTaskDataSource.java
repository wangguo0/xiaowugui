package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class PriorityTaskDataSource implements DataSource {

    private static final long PRIORITY_WAIT_MS = 50;

    private final DataSource upstream;
    private final PriorityTaskManager taskManager;
    private final AtomicBoolean registered;
    private final boolean waitWhenPreempted;
    private final int priority;

    PriorityTaskDataSource(DataSource upstream, PriorityTaskManager taskManager, int priority, boolean waitWhenPreempted) {
        this.upstream = upstream;
        this.taskManager = taskManager;
        this.priority = priority;
        this.waitWhenPreempted = waitWhenPreempted;
        this.registered = new AtomicBoolean();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        register();
        try {
            proceed();
            return upstream.open(dataSpec);
        } catch (IOException | RuntimeException e) {
            unregister();
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        proceed();
        return upstream.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        try {
            upstream.close();
        } finally {
            unregister();
        }
    }

    private void proceed() throws IOException {
        if (!waitWhenPreempted) {
            taskManager.proceedOrThrow(priority);
            return;
        }
        while (registered.get() && !taskManager.proceedNonBlocking(priority)) {
            try {
                Thread.sleep(PRIORITY_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw interrupted(e);
            }
        }
        if (!registered.get()) throw interrupted(null);
    }

    private InterruptedIOException interrupted(@Nullable InterruptedException cause) {
        InterruptedIOException error = new InterruptedIOException("Preload priority wait cancelled");
        if (cause != null) error.initCause(cause);
        return error;
    }

    private void register() {
        if (registered.compareAndSet(false, true)) taskManager.add(priority);
    }

    private void unregister() {
        if (registered.compareAndSet(true, false)) taskManager.remove(priority);
    }

    static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstreamFactory;
        private final PriorityTaskManager taskManager;
        private final boolean waitWhenPreempted;
        private final int priority;

        Factory(DataSource.Factory upstreamFactory, PriorityTaskManager taskManager, int priority, boolean waitWhenPreempted) {
            this.upstreamFactory = upstreamFactory;
            this.taskManager = taskManager;
            this.priority = priority;
            this.waitWhenPreempted = waitWhenPreempted;
        }

        @Override
        public DataSource createDataSource() {
            return new PriorityTaskDataSource(upstreamFactory.createDataSource(), taskManager, priority, waitWhenPreempted);
        }
    }
}
