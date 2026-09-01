package com.fongmi.android.tv.ui.custom;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.SiteHealthStore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SiteTester {

    private static final int CONCURRENCY = 6;
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long SLOW_MS = TimeUnit.SECONDS.toMillis(6);

    private final List<Site> sites;
    private final Callback callback;
    private final CountDownLatch latch;
    private final ExecutorService executor;
    private volatile boolean cancelled;
    private volatile int good;
    private volatile int warn;
    private volatile int bad;
    private volatile int done;

    public interface Callback {

        void onResult(Site site, SiteHealthStore.Status status, int index, int total);

        void onFinish(int good, int warn, int bad);
    }

    private SiteTester(List<Site> sites, Callback callback) {
        this.sites = new CopyOnWriteArrayList<>(sites);
        this.callback = callback;
        this.latch = new CountDownLatch(sites.size());
        this.executor = Executors.newFixedThreadPool(CONCURRENCY);
    }

    public static SiteTester start(List<Site> sites, Callback callback) {
        SiteTester tester = new SiteTester(sites, callback);
        tester.run();
        return tester;
    }

    public void cancel() {
        cancelled = true;
        executor.shutdownNow();
    }

    private void run() {
        for (int i = 0; i < sites.size(); i++) {
            int index = i;
            executor.execute(() -> testSite(sites.get(index), index));
        }
        Thread waiter = new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                return;
            }
            executor.shutdown();
            if (!cancelled) callback.onFinish(good, warn, bad);
        });
        waiter.setDaemon(true);
        waiter.start();
    }

    private void testSite(Site site, int index) {
        if (cancelled) {
            latch.countDown();
            return;
        }
        SiteHealthStore.Status status;
        long start = System.currentTimeMillis();
        try {
            var result = SiteApi.homeContent(site);
            long cost = System.currentTimeMillis() - start;
            status = result.getTypes().isEmpty() || cost > SLOW_MS ? SiteHealthStore.Status.WARN : SiteHealthStore.Status.GOOD;
        } catch (Throwable e) {
            status = SiteHealthStore.Status.BAD;
        }
        synchronized (this) {
            done += 1;
            if (status == SiteHealthStore.Status.GOOD) good++;
            else if (status == SiteHealthStore.Status.WARN) warn++;
            else bad++;
            SiteHealthStore.recordTest(site, status);
        }
        callback.onResult(site, status, done, sites.size());
        latch.countDown();
    }
}
