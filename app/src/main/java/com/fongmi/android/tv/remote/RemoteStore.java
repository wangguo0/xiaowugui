package com.fongmi.android.tv.remote;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.remote.RemoteModels.RemoteBindGrant;
import com.fongmi.android.tv.remote.RemoteModels.RemoteGroup;
import com.fongmi.android.tv.remote.RemoteModels.RemoteProfile;
import com.fongmi.android.tv.remote.RemoteModels.RemoteStoreFile;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;

public final class RemoteStore {

    private static final String KEY_STORE = "remote_trust_store";
    private static final String FILE_STORE = "WebHTV/RemoteTrust/profile.json";

    private static RemoteStoreFile cache;
    private static boolean loaded;

    private RemoteStore() {
    }

    public static synchronized RemoteStoreFile get() {
        if (!loaded) reloadFromFile();
        return ensure(cache);
    }

    public static synchronized void reloadFromFile() {
        loaded = true;
        RemoteStoreFile store = null;
        if (Setting.hasFileAccess()) {
            File file = file();
            if (file.exists() && file.length() > 0) {
                store = parse(Path.read(file));
                if (store != null) Prefers.put(KEY_STORE, App.gson().toJson(ensure(store)));
            }
        }
        if (store == null) store = parse(Prefers.getString(KEY_STORE));
        cache = ensure(store);
    }

    public static synchronized void save(RemoteStoreFile store) {
        cache = ensure(store);
        String json = App.gson().toJson(cache);
        Prefers.put(KEY_STORE, json);
        if (!Setting.hasFileAccess()) return;
        try {
            File target = file();
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            Path.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(target)) Path.move(tmp, target);
        } catch (Throwable e) {
            SpiderDebug.log("remote", "save profile file failed error=%s", e.getMessage());
        }
    }

    public static synchronized RemoteProfile prepareProfile(String serverUrl, boolean enabled, boolean keepOnline) {
        String origin = RemoteTokens.normalizeOrigin(serverUrl);
        if (TextUtils.isEmpty(origin)) throw new IllegalArgumentException(App.get().getString(R.string.remote_trust_server_required));
        RemoteStoreFile store = get();
        RemoteProfile profile = findProfile(store, origin);
        if (profile == null) {
            profile = new RemoteProfile();
            profile.serverOrigin = origin;
            store.profiles.add(profile);
        }
        profile.serverUrl = serverUrl.trim();
        profile.serverOrigin = origin;
        profile.enabled = enabled;
        profile.keepOnline = keepOnline;
        ensureProfile(profile);
        if (TextUtils.isEmpty(profile.deviceToken)) profile.deviceToken = RemoteTokens.randomCapability("dtk");
        profile.deviceId = RemoteTokens.deviceId(profile.serverOrigin, profile.deviceToken);
        profile.updatedAt = System.currentTimeMillis();
        save(store);
        return profile;
    }

    public static synchronized RemoteProfile firstProfile() {
        RemoteStoreFile store = get();
        return store.profiles.isEmpty() ? null : store.profiles.get(0);
    }

    public static synchronized RemoteProfile getProfileByOrigin(String serverOrigin) {
        return findProfile(get(), serverOrigin);
    }

    public static synchronized void upsertProfile(RemoteProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.serverOrigin)) return;
        RemoteStoreFile store = get();
        RemoteProfile current = findProfile(store, profile.serverOrigin);
        profile.updatedAt = System.currentTimeMillis();
        ensureProfile(profile);
        if (current == null) store.profiles.add(profile);
        else store.profiles.set(store.profiles.indexOf(current), profile);
        save(store);
    }

    public static synchronized void addBindGrant(String serverOrigin, RemoteBindGrant grant) {
        RemoteProfile profile = getProfileByOrigin(serverOrigin);
        if (profile == null || grant == null) return;
        ensureProfile(profile);
        profile.pendingBindGrants.add(grant);
        profile.updatedAt = System.currentTimeMillis();
        upsertProfile(profile);
    }

    public static synchronized boolean consumeBindGrant(String serverOrigin, String grantId, String bindGrantToken) {
        RemoteProfile profile = getProfileByOrigin(serverOrigin);
        if (profile == null) return false;
        ensureProfile(profile);
        boolean matched = false;
        long now = System.currentTimeMillis();
        for (RemoteBindGrant grant : profile.pendingBindGrants) {
            if (grant == null || grant.consumedAt > 0) continue;
            if (!TextUtils.equals(grant.grantId, grantId)) continue;
            if (!TextUtils.equals(grant.bindGrantToken, bindGrantToken)) continue;
            grant.consumedAt = now;
            matched = true;
            break;
        }
        if (matched) upsertProfile(profile);
        return matched;
    }

    public static synchronized void upsertGroup(String serverOrigin, RemoteGroup group) {
        RemoteProfile profile = getProfileByOrigin(serverOrigin);
        if (profile == null || group == null || TextUtils.isEmpty(group.groupId)) return;
        ensureProfile(profile);
        if (profile.groups == null) profile.groups = new ArrayList<>();
        group.updatedAt = System.currentTimeMillis();
        for (int i = 0; i < profile.groups.size(); i++) {
            RemoteGroup current = profile.groups.get(i);
            if (current != null && TextUtils.equals(current.groupId, group.groupId)) {
                profile.groups.set(i, group);
                upsertProfile(profile);
                return;
            }
        }
        profile.groups.add(group);
        upsertProfile(profile);
    }

    public static synchronized boolean hasGroupTokenHash(String serverOrigin, String groupTokenHash) {
        if (TextUtils.isEmpty(groupTokenHash)) return false;
        RemoteProfile profile = getProfileByOrigin(serverOrigin);
        if (profile == null) return false;
        ensureProfile(profile);
        for (RemoteGroup group : profile.groups) {
            if (group != null && TextUtils.equals(group.groupTokenHash, groupTokenHash)) return true;
        }
        return false;
    }

    public static synchronized void removeProfile(String serverOrigin) {
        RemoteStoreFile store = get();
        for (Iterator<RemoteProfile> it = store.profiles.iterator(); it.hasNext(); ) {
            RemoteProfile profile = it.next();
            if (profile != null && TextUtils.equals(profile.serverOrigin, serverOrigin)) it.remove();
        }
        save(store);
    }

    public static synchronized void clear() {
        cache = new RemoteStoreFile();
        loaded = true;
        Prefers.remove(KEY_STORE);
        if (Setting.hasFileAccess()) {
            Path.clear(file());
            Path.clear(new File(file().getParentFile(), file().getName() + ".tmp"));
        }
    }

    public static synchronized String summary(Context context) {
        RemoteStoreFile store = get();
        int profiles = 0;
        int groups = 0;
        boolean keepOnline = false;
        for (RemoteProfile profile : store.profiles) {
            if (profile == null) continue;
            profiles++;
            ensureProfile(profile);
            groups += profile.groups.size();
            keepOnline |= profile.keepOnline;
        }
        if (profiles == 0) return context.getString(R.string.remote_trust_status_unbound);
        String status = keepOnline ? context.getString(R.string.remote_trust_status_online) : context.getString(R.string.remote_trust_status_enabled);
        return context.getString(R.string.remote_trust_status_summary, status, profiles, groups);
    }

    static boolean shouldStart(RemoteProfile profile) {
        if (profile == null || TextUtils.isEmpty(profile.serverOrigin)) return false;
        ensureProfile(profile);
        return profile.enabled || !profile.groups.isEmpty() || hasPendingGrant(profile);
    }

    static boolean hasPendingGrant(RemoteProfile profile) {
        ensureProfile(profile);
        for (RemoteBindGrant grant : profile.pendingBindGrants) if (grant != null && grant.consumedAt <= 0) return true;
        return false;
    }

    private static RemoteProfile findProfile(RemoteStoreFile store, String serverOrigin) {
        if (store == null || TextUtils.isEmpty(serverOrigin)) return null;
        ensure(store);
        for (RemoteProfile profile : store.profiles) {
            if (profile != null && TextUtils.equals(profile.serverOrigin, serverOrigin)) return profile;
        }
        return null;
    }

    private static RemoteStoreFile parse(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            return ensure(App.gson().fromJson(json, RemoteStoreFile.class));
        } catch (Throwable e) {
            return null;
        }
    }

    private static RemoteStoreFile ensure(RemoteStoreFile store) {
        if (store == null) store = new RemoteStoreFile();
        if (store.profiles == null) store.profiles = new ArrayList<>();
        for (RemoteProfile profile : store.profiles) ensureProfile(profile);
        return store;
    }

    private static void ensureProfile(RemoteProfile profile) {
        if (profile == null) return;
        if (profile.pendingBindGrants == null) profile.pendingBindGrants = new ArrayList<>();
        if (profile.groups == null) profile.groups = new ArrayList<>();
        for (RemoteGroup group : profile.groups) {
            if (group != null && group.devices == null) group.devices = new ArrayList<>();
        }
    }

    private static File file() {
        return Path.root(FILE_STORE);
    }
}
