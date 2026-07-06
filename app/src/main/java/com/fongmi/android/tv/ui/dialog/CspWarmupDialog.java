package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.text.TextUtils;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CspWarmup;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CspWarmupDialog {

    private CspWarmupDialog() {
    }

    public static void show(Fragment fragment, Runnable callback) {
        if (fragment == null || !fragment.isAdded()) return;
        show(fragment.requireContext(), fragment.getChildFragmentManager(), callback);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        show(activity, activity.getSupportFragmentManager(), callback);
    }

    private static void show(Context context, FragmentManager manager, Runnable callback) {
        if (manager == null || manager.isStateSaved()) return;
        ChoiceDialog.showSingle(manager, context.getString(R.string.setting_csp_warmup), modeLabels(context), modeIndex(), which -> {
            if (which == 0) {
                Setting.putCspWarmupMode(Setting.CSP_WARMUP_DISABLED);
                run(callback);
            } else if (which == 1) {
                Setting.putCspWarmupMode(Setting.CSP_WARMUP_DEFAULT);
                run(callback);
            } else {
                App.post(() -> showSitePicker(context, manager, callback), 120);
            }
        });
    }

    private static CharSequence[] modeLabels(Context context) {
        return new CharSequence[]{
                context.getString(R.string.setting_disable),
                context.getString(R.string.setting_csp_warmup_default),
                context.getString(R.string.setting_csp_warmup_custom)
        };
    }

    private static int modeIndex() {
        int mode = Setting.getCspWarmupMode();
        if (mode == Setting.CSP_WARMUP_CUSTOM) return 2;
        return mode == Setting.CSP_WARMUP_DEFAULT ? 1 : 0;
    }

    private static void showSitePicker(Context context, FragmentManager manager, Runnable callback) {
        if (manager == null || manager.isStateSaved()) return;
        List<Site> sites = warmableSites();
        if (sites.isEmpty()) {
            Notify.show(R.string.setting_csp_warmup_no_site);
            return;
        }
        CharSequence[] labels = new CharSequence[sites.size()];
        boolean[] checked = checkedSites(sites);
        for (int i = 0; i < sites.size(); i++) labels[i] = siteLabel(sites.get(i));
        ChoiceDialog.showMulti(manager, context.getString(R.string.setting_csp_warmup_select_site), labels, checked, (which, state) -> itemEnabled(sites, which, state), result -> {
            List<String> keys = selectedKeys(sites, result);
            if (keys.isEmpty()) {
                Notify.show(R.string.setting_csp_warmup_select_required);
                return;
            }
            Setting.putCspWarmupSites(keys);
            Setting.putCspWarmupMode(Setting.CSP_WARMUP_CUSTOM);
            run(callback);
        });
    }

    private static List<Site> warmableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (CspWarmup.isWarmable(site)) sites.add(site);
        return sites;
    }

    private static boolean[] checkedSites(List<Site> sites) {
        Set<String> keys = new LinkedHashSet<>(Setting.getCspWarmupSites());
        Set<String> jars = new HashSet<>();
        boolean[] checked = new boolean[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            if (!keys.contains(site.getKey())) continue;
            if (!jars.add(CspWarmup.jarKey(site))) continue;
            checked[i] = true;
        }
        return checked;
    }

    private static boolean itemEnabled(List<Site> sites, int which, boolean[] checked) {
        if (which < 0 || which >= sites.size()) return false;
        if (checked != null && which < checked.length && checked[which]) return true;
        String jar = CspWarmup.jarKey(sites.get(which));
        for (int i = 0; checked != null && i < checked.length && i < sites.size(); i++) {
            if (checked[i] && TextUtils.equals(jar, CspWarmup.jarKey(sites.get(i)))) return false;
        }
        return true;
    }

    private static List<String> selectedKeys(List<Site> sites, boolean[] checked) {
        List<String> keys = new ArrayList<>();
        Set<String> jars = new HashSet<>();
        for (int i = 0; checked != null && i < checked.length && i < sites.size(); i++) {
            Site site = sites.get(i);
            if (!checked[i] || !jars.add(CspWarmup.jarKey(site))) continue;
            keys.add(site.getKey());
        }
        return keys;
    }

    private static CharSequence siteLabel(Site site) {
        String name = TextUtils.isEmpty(site.getName()) ? site.getKey() : site.getName();
        return TextUtils.isEmpty(site.getApi()) ? name : name + " · " + site.getApi();
    }

    private static void run(Runnable callback) {
        if (callback != null) callback.run();
    }
}
