package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogWebHomeExtensionDebugBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.fongmi.android.tv.web.ext.WebHomeExtensionSourceStore;
import com.github.catvod.crawler.DebugLogStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class WebHomeExtensionDebugDialog extends BaseAlertDialog implements HomeWebController.Listener {

    private DialogWebHomeExtensionDebugBinding binding;
    private HomeWebController controller;
    private Runnable callback;
    private WebHomeExtensionSourceStore.Entry source;

    public static void show(FragmentActivity activity, Runnable callback) {
        WebHomeExtensionDebugDialog dialog = new WebHomeExtensionDebugDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogWebHomeExtensionDebugBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        Window window = getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (ResUtil.isLand(requireContext()) ? 0.96f : 0.98f));
        params.height = (int) (screenHeight * 0.96f);
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        ViewGroup.LayoutParams rootParams = binding.root.getLayoutParams();
        rootParams.height = params.height;
        binding.root.setLayoutParams(rootParams);
    }

    @Override
    protected void initView() {
        if (!Setting.isDebugLog()) Setting.putDebugLog(true);
        source = firstCodeSource();
        binding.codeText.setText(source == null ? "GM_log('ready');\n" : WebHomeExtensionSourceStore.code(source));
        setupScrollableText(binding.codeText);
        setupScrollableText(binding.consoleText);
        binding.tabGroup.check(R.id.tabWeb);
        controller = new HomeWebController(requireActivity(), binding.web, this);
        Site site = VodConfig.get().getHome();
        if (site != null && site.hasHomePage()) controller.load(site, true);
        refreshConsole();
    }

    @Override
    protected void initEvent() {
        binding.tabGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            showTab(checkedId);
            if (checkedId == R.id.tabConsole) refreshConsole();
        });
        binding.reload.setOnClickListener(view -> reload());
        binding.inspect.setOnClickListener(view -> inspectElement());
        binding.refreshConsole.setOnClickListener(view -> refreshConsole());
        binding.save.setOnClickListener(view -> saveAndPreview());
        binding.close.setOnClickListener(view -> dismiss());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (controller != null) controller.onResume();
    }

    @Override
    public void onPause() {
        if (controller != null) controller.onPause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (controller != null) controller.destroy();
        controller = null;
        if (callback != null) callback.run();
        super.onDestroyView();
    }

    private void showTab(int tab) {
        binding.web.setVisibility(tab == R.id.tabWeb ? View.VISIBLE : View.GONE);
        binding.consoleLayout.setVisibility(tab == R.id.tabConsole ? View.VISIBLE : View.GONE);
        binding.codeLayout.setVisibility(tab == R.id.tabCode ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        if (controller != null) controller.reloadExtensions();
        Notify.show(R.string.web_home_extension_preview_reloaded);
    }

    private void inspectElement() {
        binding.tabGroup.check(R.id.tabWeb);
        if (controller == null) return;
        controller.evaluate("""
                (function(){
                  if(window.__fmInspectCleanup)window.__fmInspectCleanup();
                  function path(el){
                    const arr=[];
                    for(let n=el;n&&n.nodeType===1&&arr.length<10;n=n.parentElement){
                      let s=n.tagName.toLowerCase();
                      if(n.id)s+='#'+n.id;
                      if(n.className&&typeof n.className==='string')s+='.'+n.className.trim().split(/\\s+/).slice(0,3).join('.');
                      arr.unshift(s);
                    }
                    return arr.join(' > ');
                  }
                  function info(el){
                    const rect=el.getBoundingClientRect();
                    return {
                      path:path(el),
                      tag:el.tagName.toLowerCase(),
                      id:el.id||'',
                      className:typeof el.className==='string'?el.className:'',
                      text:(el.innerText||el.textContent||'').trim().replace(/\\s+/g,' ').slice(0,500),
                      html:(el.outerHTML||'').replace(/\\s+/g,' ').slice(0,1200),
                      x:Math.round(rect.left),y:Math.round(rect.top),w:Math.round(rect.width),h:Math.round(rect.height)
                    };
                  }
                  let style=document.getElementById('__fmInspectStyle');
                  if(!style){
                    style=document.createElement('style');
                    style.id='__fmInspectStyle';
                    style.textContent='.__fm-inspect-hover{outline:2px solid #137333!important;outline-offset:2px!important}.__fm-inspect-selected{outline:3px solid #b3261e!important;outline-offset:2px!important}';
                    document.documentElement.appendChild(style);
                  }
                  let hover=null;
                  function clearHover(){if(hover)hover.classList.remove('__fm-inspect-hover');hover=null;}
                  function cleanup(){clearHover();document.removeEventListener('mousemove',move,true);document.removeEventListener('click',click,true);window.__fmInspectCleanup=null;}
                  function move(e){
                    clearHover();
                    hover=e.target;
                    if(hover&&hover.classList)hover.classList.add('__fm-inspect-hover');
                  }
                  function click(e){
                    e.preventDefault();
                    e.stopPropagation();
                    clearHover();
                    const old=document.querySelector('.__fm-inspect-selected');
                    if(old)old.classList.remove('__fm-inspect-selected');
                    if(e.target&&e.target.classList)e.target.classList.add('__fm-inspect-selected');
                    window.__fmInspectLast=info(e.target);
                    console.log('[fm-inspect]',JSON.stringify(window.__fmInspectLast));
                    cleanup();
                  }
                  window.__fmInspectCleanup=cleanup;
                  document.addEventListener('mousemove',move,true);
                  document.addEventListener('click',click,true);
                  return 'installed';
                })();
                """, value -> Notify.show(R.string.web_home_extension_inspect_element));
    }

    private void saveAndPreview() {
        String code = inputText(binding.codeText);
        if (TextUtils.isEmpty(code)) {
            Notify.show(R.string.web_home_extension_source_empty);
            return;
        }
        String id = source == null ? "" : source.getId();
        WebHomeExtensionSourceStore.saveCode(id, source == null ? getString(R.string.web_home_extension_local_code_default, WebHomeExtensionSourceStore.list().size() + 1) : source.getName(), code, source == null || source.isEnabled(), VodConfig.get().getHome().getKey());
        source = firstCodeSource();
        WebHomeExtensionRegistry.get().clear();
        if (controller != null) controller.reloadExtensions();
        Notify.show(R.string.web_home_extension_source_saved);
    }

    private void refreshConsole() {
        StringBuilder builder = new StringBuilder();
        builder.append("Runtime report:\n");
        builder.append(WebHomeExtensionRegistry.get().debugReport()).append('\n');
        builder.append("Recent WebView / extension logs:\n");
        for (String line : DebugLogStore.snapshot()) {
            if (line.contains("webhome") || line.contains("webview-console") || line.contains("webhome-ext")) builder.append(line).append('\n');
        }
        if (controller == null) {
            binding.consoleText.setText(builder.toString());
            return;
        }
        controller.evaluate("""
                (function(){
                  const active=document.activeElement;
                  const path=function(el){
                    const arr=[];
                    for(let n=el;n&&n.nodeType===1&&arr.length<8;n=n.parentElement){
                      let s=n.tagName.toLowerCase();
                      if(n.id)s+='#'+n.id;
                      if(n.className&&typeof n.className==='string')s+='.'+n.className.trim().split(/\\s+/).slice(0,3).join('.');
                      arr.unshift(s);
                    }
                    return arr.join(' > ');
                  };
                  const nodes=Array.prototype.slice.call(document.querySelectorAll('body *'),0,80).map(function(n){
                    const rect=n.getBoundingClientRect();
                    return {
                      tag:n.tagName.toLowerCase(),
                      id:n.id||'',
                      className:typeof n.className==='string'?n.className:'',
                      text:(n.innerText||n.textContent||'').trim().replace(/\\s+/g,' ').slice(0,120),
                      x:Math.round(rect.left),y:Math.round(rect.top),w:Math.round(rect.width),h:Math.round(rect.height)
                    };
                  }).filter(function(n){return n.w>0&&n.h>0;}).slice(0,40);
                  return JSON.stringify({
                    title:document.title,
                    url:location.href,
                    readyState:document.readyState,
                    active:active?path(active):'',
                    selected:window.__fmInspectLast||null,
                    bodyText:(document.body&&document.body.innerText||'').trim().replace(/\\s+/g,' ').slice(0,1200),
                    elements:nodes
                  },null,2);
                })();
                """, value -> binding.consoleText.setText(builder.append("\nPage elements snapshot:\n").append(value == null ? "" : value).toString()));
    }

    private WebHomeExtensionSourceStore.Entry firstCodeSource() {
        for (WebHomeExtensionSourceStore.Entry entry : WebHomeExtensionSourceStore.list()) if (WebHomeExtensionSourceStore.isCodeSource(entry)) return entry;
        return null;
    }

    private void setupScrollableText(EditText input) {
        input.setSelectAllOnFocus(false);
        input.setHorizontallyScrolling(true);
        input.setHorizontalScrollBarEnabled(true);
        input.setVerticalScrollBarEnabled(true);
        input.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) view.post(() -> disallowParentIntercept(view, false));
            else disallowParentIntercept(view, true);
            return false;
        });
    }

    private void disallowParentIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private String inputText(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    @Override
    public void onWebLoading() {
    }

    @Override
    public void onWebReady() {
        refreshConsole();
    }

    @Override
    public void onWebError() {
        refreshConsole();
    }
}
