package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.databinding.DialogEpisodeListBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeGroupAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class EpisodeListDialog extends BaseSideSheetDialog implements FlagAdapter.OnClickListener, EpisodeGroupAdapter.OnClickListener, EpisodeAdapter.OnClickListener {

    private DialogEpisodeListBinding binding;
    private EpisodeGroupAdapter groupAdapter;
    private EpisodeAdapter episodeAdapter;
    private FlagAdapter flagAdapter;
    private List<Flag> flags;
    private boolean reverse;

    public static EpisodeListDialog create() {
        return new EpisodeListDialog();
    }

    public EpisodeListDialog flags(List<Flag> flags) {
        this.flags = flags;
        return this;
    }

    public EpisodeListDialog reverse(boolean reverse) {
        this.reverse = reverse;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeListDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogEpisodeListBinding.inflate(inflater, container, false);
    }

    @Override
    protected int getWidth() {
        int screen = ResUtil.getScreenWidth(requireContext());
        return Math.max(ResUtil.dp2px(360), Math.min(ResUtil.dp2px(560), Math.round(screen * 0.44f)));
    }

    @Override
    protected void initView() {
        setRecyclerView();
        flagAdapter.addAll(flags == null ? new ArrayList<>() : flags);
        setGroups(getSelectedFlag());
        binding.flag.scrollToPosition(flagAdapter.getPosition());
    }

    private void setRecyclerView() {
        int spanCount = 4;
        binding.flag.setHasFixedSize(true);
        binding.flag.setItemAnimator(null);
        binding.flag.setAdapter(flagAdapter = new FlagAdapter(this));
        binding.group.setHasFixedSize(true);
        binding.group.setItemAnimator(null);
        binding.group.setAdapter(groupAdapter = new EpisodeGroupAdapter(this));
        binding.episode.setHasFixedSize(true);
        binding.episode.setItemAnimator(null);
        binding.episode.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        binding.episode.addItemDecoration(new SpaceItemDecoration(spanCount, 8));
        binding.episode.setAdapter(episodeAdapter = new EpisodeAdapter(this, ViewType.GRID));
    }

    private Flag getSelectedFlag() {
        if (flagAdapter.isEmpty()) return null;
        return flagAdapter.get(flagAdapter.getPosition());
    }

    private void setGroups(Flag flag) {
        if (flag == null) return;
        List<Episode> episodes = flag.getEpisodes();
        groupAdapter.addAll(EpisodeGroupAdapter.build(episodes.size(), getSelectedEpisodePosition(episodes), reverse));
        EpisodeGroupAdapter.Group group = groupAdapter.isEmpty() ? null : groupAdapter.getItems().get(groupAdapter.getPosition());
        setEpisodes(episodes, group);
        binding.group.scrollToPosition(groupAdapter.getPosition());
    }

    private void setEpisodes(List<Episode> episodes, EpisodeGroupAdapter.Group group) {
        if (group == null) {
            episodeAdapter.addAll(episodes);
            return;
        }
        int start = Math.max(0, Math.min(group.start, episodes.size()));
        int end = Math.max(start, Math.min(group.end, episodes.size()));
        episodeAdapter.addAll(new ArrayList<>(episodes.subList(start, end)));
        binding.episode.scrollToPosition(episodeAdapter.getPosition());
    }

    private int getSelectedEpisodePosition(List<Episode> episodes) {
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).isSelected()) return i;
        return 0;
    }

    @Override
    public void onItemClick(Flag item) {
        ((FlagAdapter.OnClickListener) requireActivity()).onItemClick(item);
        flagAdapter.notifyItemRangeChanged(0, flagAdapter.getItemCount());
        setGroups(item);
    }

    @Override
    public void onItemClick(EpisodeGroupAdapter.Group item) {
        groupAdapter.setSelected(item);
        Flag flag = getSelectedFlag();
        if (flag != null) setEpisodes(flag.getEpisodes(), item);
        binding.group.scrollToPosition(groupAdapter.getPosition());
    }

    @Override
    public void onItemClick(Episode item) {
        ((EpisodeAdapter.OnClickListener) requireActivity()).onItemClick(item);
        dismiss();
    }
}
