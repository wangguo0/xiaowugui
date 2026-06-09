package com.fongmi.android.tv.ui.dialog;

import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterSyncPathDirBinding;
import com.fongmi.android.tv.databinding.DialogSyncPathBinding;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.LoginStateSync;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LoginStatePathDialog extends BaseAlertDialog {

    private final Set<String> selected = new LinkedHashSet<>();
    private final List<LoginStateSync.TreeItem> items = new ArrayList<>();

    private DialogSyncPathBinding binding;
    private ItemAdapter adapter;
    private Runnable callback;
    private String current = "";
    private AlertDialog editor;

    public static void show(Fragment fragment, Runnable callback) {
        show(fragment.requireActivity(), callback);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        LoginStatePathDialog dialog = new LoginStatePathDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSyncPathBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.Theme_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        selected.addAll(LoginStateSync.learnedPaths());
        binding.title.setText(R.string.login_state_paths_title);
        binding.selectSafe.setVisibility(View.VISIBLE);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 6));
        binding.recycler.setAdapter(adapter = new ItemAdapter());
        load("");
    }

    @Override
    protected void initEvent() {
        binding.up.setOnClickListener(v -> up());
        binding.refresh.setOnClickListener(v -> load(current));
        binding.reset.setOnClickListener(v -> reset());
        binding.selectSafe.setOnClickListener(v -> confirmPending());
        binding.negative.setOnClickListener(v -> dismiss());
        binding.positive.setOnClickListener(v -> onPositive());
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(ResUtil.isLand(requireContext()) ? 0.58f : 0.94f);
    }

    @Override
    public void onDestroyView() {
        if (editor != null && editor.isShowing()) editor.dismiss();
        editor = null;
        super.onDestroyView();
    }

    private void load(String path) {
        LoginStateSync.Tree tree = LoginStateSync.tree(path);
        current = tree.getPath();
        items.clear();
        items.addAll(tree.getItems());
        adapter.notifyDataSetChanged();
        updateState();
    }

    private void up() {
        if (current.isEmpty()) return;
        LoginStateSync.Tree tree = LoginStateSync.tree(current);
        load(".".equals(tree.getParent()) ? "" : tree.getParent());
    }

    private void reset() {
        selected.clear();
        adapter.notifyDataSetChanged();
        updateState();
    }

    private void confirmPending() {
        List<String> pending = pendingToConfirm();
        if (pending.isEmpty()) {
            Notify.show(R.string.login_state_confirm_empty);
            return;
        }
        selected.addAll(pending);
        adapter.notifyDataSetChanged();
        updateState();
        Notify.show(getString(R.string.login_state_confirm_done, pending.size()));
    }

    private void toggle(String path) {
        if (TextUtils.isEmpty(path)) return;
        if (stateOf(path) == MaterialCheckBox.STATE_CHECKED) removePath(path);
        else addPath(path);
        adapter.notifyDataSetChanged();
        updateState();
    }

    private void addPath(String path) {
        path = normalize(path);
        if (path.isEmpty()) return;
        String target = path;
        selected.removeIf(item -> covers(target, item) || covers(item, target));
        selected.add(target);
    }

    private void removePath(String path) {
        path = normalize(path);
        if (path.isEmpty()) return;
        String target = path;
        selected.removeIf(item -> covers(item, target));
    }

    private void onPositive() {
        LoginStateSync.savePaths(new ArrayList<>(selected));
        if (callback != null) callback.run();
        dismiss();
    }

    private void updateState() {
        binding.current.setText(current.isEmpty() ? "/" : LoginStateSync.displayPath(current));
        binding.up.setEnabled(!current.isEmpty());
        binding.recycler.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        binding.empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        int ready = 0;
        int missing = 0;
        for (LoginStateSync.PathState state : LoginStateSync.pathStates(new ArrayList<>(selected))) {
            if (state.isExists()) ready++;
            else missing++;
        }
        binding.summary.setText(selected.isEmpty() ? getString(R.string.login_state_paths_empty_selected) : getString(R.string.login_state_paths_selected_count, selected.size(), ready, missing));
        int pending = pendingToConfirm().size();
        binding.selectSafe.setEnabled(pending > 0);
        binding.selectSafe.setText(pending > 0 ? getString(R.string.login_state_confirm_pending_count, pending) : getString(R.string.login_state_confirm_pending));
    }

    private List<String> pendingToConfirm() {
        List<String> result = new ArrayList<>();
        for (String path : LoginStateSync.pendingPaths()) {
            if (stateOf(path) != MaterialCheckBox.STATE_CHECKED) result.add(path);
        }
        return result;
    }

    private int stateOf(String path) {
        path = normalize(path);
        for (String item : selected) if (covers(item, path)) return MaterialCheckBox.STATE_CHECKED;
        for (String item : selected) if (covers(path, item)) return MaterialCheckBox.STATE_INDETERMINATE;
        return MaterialCheckBox.STATE_UNCHECKED;
    }

    private boolean covers(String parent, String child) {
        parent = normalize(parent);
        child = normalize(child);
        return !parent.isEmpty() && (parent.equals(child) || child.startsWith(parent + "/"));
    }

    private String normalize(String path) {
        return LoginStateSync.normalizePath(path);
    }

    private void edit(String path) {
        if (TextUtils.isEmpty(path)) return;
        Task.execute(() -> {
            try {
                String content = LoginStateSync.read(path);
                App.post(() -> showEditor(path, content));
            } catch (Exception e) {
                App.post(() -> Notify.show(e.getMessage()));
            }
        });
    }

    private void showEditor(String path, String content) {
        if (editor != null && editor.isShowing()) editor.dismiss();
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setText(content);
        input.setSelectAllOnFocus(false);
        input.setSingleLine(false);
        input.setMinLines(8);
        input.setMaxLines(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.START | Gravity.TOP);
        input.setHorizontalScrollBarEnabled(true);
        input.setVerticalScrollBarEnabled(true);
        input.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) view.getParent().requestDisallowInterceptTouchEvent(false);
            else view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        LinearLayoutCompat container = new LinearLayoutCompat(requireContext());
        container.setOrientation(LinearLayoutCompat.VERTICAL);
        container.setPadding(ResUtil.dp2px(20), ResUtil.dp2px(8), ResUtil.dp2px(20), 0);
        TextInputLayout layout = new TextInputLayout(requireContext());
        layout.setHint(path);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(layout, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_WebHTV_LightDialog)
                .setTitle(R.string.login_state_edit)
                .setView(container)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save(editor, path, input.getText() == null ? "" : input.getText().toString()));
    }

    private void save(AlertDialog dialog, String path, String content) {
        Task.execute(() -> {
            try {
                LoginStateSync.write(path, content);
                addPath(path);
                App.post(() -> {
                    dialog.dismiss();
                    Notify.show(R.string.login_state_saved);
                    load(current);
                });
            } catch (Exception e) {
                App.post(() -> Notify.show(e.getMessage()));
            }
        });
    }

    private class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterSyncPathDirBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private class ViewHolder extends RecyclerView.ViewHolder {

            private final AdapterSyncPathDirBinding binding;

            private ViewHolder(@NonNull AdapterSyncPathDirBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            private void bind(LoginStateSync.TreeItem item) {
                String detail = item.isDir() ? item.getPath() : FileUtil.byteCountToDisplaySize(item.getSize()) + " · " + Formatters.LOCAL_DATETIME.format(Instant.ofEpochMilli(item.getModified()).atZone(ZoneId.systemDefault()));
                binding.name.setText(item.getName());
                binding.path.setText(detail);
                binding.icon.setImageResource(item.isDir() ? R.drawable.ic_folder : R.drawable.ic_file);
                binding.check.setCheckedState(stateOf(item.getPath()));
                binding.check.setVisibility(item.isSelectable() ? View.VISIBLE : View.INVISIBLE);
                binding.enter.setVisibility(View.VISIBLE);
                binding.enter.setContentDescription(getString(item.isDir() ? R.string.sync_paths_enter : R.string.login_state_edit));
                binding.getRoot().setOnClickListener(v -> {
                    if (!item.isSelectable() && item.isDir()) load(item.getPath());
                    else toggle(item.getPath());
                });
                binding.getRoot().setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_RIGHT || !item.isDir()) return false;
                    load(item.getPath());
                    return true;
                });
                binding.enter.setOnClickListener(v -> {
                    if (item.isDir()) load(item.getPath());
                    else edit(item.getPath());
                });
            }
        }
    }
}
