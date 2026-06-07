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
import com.github.catvod.utils.Json;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WebHomeExtensionDebugDialog extends BaseAlertDialog implements HomeWebController.Listener {

    private DialogWebHomeExtensionDebugBinding binding;
    private HomeWebController controller;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private final List<String> consoleLines = new ArrayList<>();
    private final List<String> networkLines = new ArrayList<>();
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
        params.height = (int) (screenHeight * 0.90f);
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
        setupScrollableText(binding.elementsText);
        setupScrollableText(binding.networkText);
        binding.tabGroup.check(R.id.tabWeb);
        controller = new HomeWebController(requireActivity(), binding.web, this, true);
        Site site = VodConfig.get().getHome();
        if (site != null && site.hasHomePage()) controller.load(site, true);
        refreshPanel();
    }

    @Override
    protected void initEvent() {
        binding.tabGroup.addOnButtonCheckedListener((group, checkedId, checked) -> {
            if (!checked) return;
            showTab(checkedId);
            refreshPanel();
        });
        binding.reload.setOnClickListener(view -> reload());
        binding.inspect.setOnClickListener(view -> inspectElement());
        binding.refreshConsole.setOnClickListener(view -> refreshPanel());
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
        binding.elementsLayout.setVisibility(tab == R.id.tabElements ? View.VISIBLE : View.GONE);
        binding.networkLayout.setVisibility(tab == R.id.tabNetwork ? View.VISIBLE : View.GONE);
        binding.codeLayout.setVisibility(tab == R.id.tabCode ? View.VISIBLE : View.GONE);
    }

    private void reload() {
        if (controller != null) controller.reloadExtensions();
        Notify.show(R.string.web_home_extension_preview_reloaded);
    }

    private void inspectElement() {
        binding.tabGroup.check(R.id.tabWeb);
        if (controller == null) return;
        appendConsole("INSPECT click an element in the Web tab");
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
                """, value -> Notify.show(R.string.web_home_extension_inspect_hint));
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
        consoleLines.clear();
        WebHomeExtensionRegistry.get().clear();
        if (controller != null) controller.reloadExtensions();
        Notify.show(R.string.web_home_extension_source_saved);
    }

    private void refreshPanel() {
        if (binding.tabConsole.isChecked()) refreshConsole();
        else if (binding.tabElements.isChecked()) refreshElements();
        else if (binding.tabNetwork.isChecked()) refreshNetwork();
    }

    private void refreshConsole() {
        StringBuilder builder = new StringBuilder();
        builder.append("Console\n");
        builder.append("time          level/source message\n\n");
        if (consoleLines.isEmpty()) builder.append("No console messages yet. Save code and preview, then use console.log(...) or GM_log(...).\n");
        else for (String line : consoleLines) builder.append(line).append('\n');
        binding.consoleText.setText(builder.toString());
    }

    private void refreshNetwork() {
        StringBuilder builder = new StringBuilder();
        builder.append("Network\n");
        builder.append("time          type         method status cost  detail url\n\n");
        if (networkLines.isEmpty()) builder.append("No requests captured yet.\n");
        else for (String line : networkLines) builder.append(line).append('\n');
        binding.networkText.setText(builder.toString());
    }

    private void refreshElements() {
        if (controller == null) return;
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
                    const depth=(function(el){let d=0;for(let p=el.parentElement;p&&d<20;p=p.parentElement)d++;return d;})(n);
                    const attrs=[];
                    for(let i=0;i<n.attributes.length&&i<8;i++){
                      const a=n.attributes[i];
                      if(a.name==='class'||a.name==='id')continue;
                      attrs.push(a.name+'="'+String(a.value).slice(0,80)+'"');
                    }
                    return {
                      depth:depth,
                      tag:n.tagName.toLowerCase(),
                      id:n.id||'',
                      className:typeof n.className==='string'?n.className:'',
                      attrs:attrs.join(' '),
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
                """, value -> {
                    if (binding != null) binding.elementsText.setText(formatElements(value));
                });
    }

    private String formatElements(String value) {
        StringBuilder builder = new StringBuilder();
        builder.append("Elements\n\n");
        try {
            JsonObject object = Json.parse(unquote(value)).getAsJsonObject();
            builder.append("Title: ").append(safe(object, "title")).append('\n');
            builder.append("URL: ").append(safe(object, "url")).append('\n');
            builder.append("Ready: ").append(safe(object, "readyState")).append('\n');
            builder.append("Active: ").append(safe(object, "active")).append("\n\n");
            if (object.has("selected") && object.get("selected").isJsonObject()) {
                JsonObject selected = object.getAsJsonObject("selected");
                builder.append("Selected\n");
                builder.append(safe(selected, "path")).append('\n');
                builder.append("box: ").append(safe(selected, "x")).append(',').append(safe(selected, "y")).append(' ')
                        .append(safe(selected, "w")).append('x').append(safe(selected, "h")).append('\n');
                builder.append("text: ").append(safe(selected, "text")).append("\n\n");
                builder.append("html: ").append(safe(selected, "html")).append("\n\n");
            }
            builder.append("DOM\n");
            JsonArray elements = object.getAsJsonArray("elements");
            if (elements != null) for (JsonElement element : elements) appendElement(builder, element.getAsJsonObject());
            return builder.toString();
        } catch (Throwable e) {
            return builder.append(unquote(value)).toString();
        }
    }

    private void appendElement(StringBuilder builder, JsonObject object) {
        int depth = parseInt(safe(object, "depth"));
        for (int i = 0; i < Math.max(0, depth - 1); i++) builder.append("  ");
        builder.append('<').append(safe(object, "tag"));
        if (!TextUtils.isEmpty(safe(object, "id"))) builder.append(" id=\"").append(safe(object, "id")).append('"');
        if (!TextUtils.isEmpty(safe(object, "className"))) builder.append(" class=\"").append(safe(object, "className")).append('"');
        if (!TextUtils.isEmpty(safe(object, "attrs"))) builder.append(' ').append(safe(object, "attrs"));
        builder.append('>');
        if (!TextUtils.isEmpty(safe(object, "text"))) builder.append("  ").append(safe(object, "text"));
        builder.append("  [").append(safe(object, "x")).append(',').append(safe(object, "y")).append(' ')
                .append(safe(object, "w")).append('x').append(safe(object, "h")).append("]\n");
    }

    private String safe(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            return element == null || element.isJsonNull() ? "" : element.getAsString();
        } catch (Throwable e) {
            return "";
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable e) {
            return 0;
        }
    }

    private String unquote(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            JsonElement element = Json.parse(value);
            return element.isJsonPrimitive() ? element.getAsString() : value;
        } catch (Throwable e) {
            return value;
        }
    }

    private void appendConsole(String line) {
        runOnUi(() -> {
            consoleLines.add(now() + " " + line);
            trim(consoleLines);
            if (line.contains("[fm-inspect]")) {
                binding.tabGroup.check(R.id.tabElements);
                refreshElements();
            } else if (binding != null && binding.tabConsole.isChecked()) {
                refreshConsole();
            }
        });
    }

    private void appendNetwork(String line) {
        runOnUi(() -> {
            networkLines.add(now() + " " + line);
            trim(networkLines);
            if (binding != null && binding.tabNetwork.isChecked()) refreshNetwork();
        });
    }

    private void runOnUi(Runnable action) {
        if (binding == null) return;
        binding.root.post(action);
    }

    private void trim(List<String> lines) {
        while (lines.size() > 300) lines.remove(0);
    }

    private String now() {
        return timeFormat.format(new Date());
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
        appendConsole("PAGE loading");
    }

    @Override
    public void onWebReady() {
        appendConsole("PAGE ready");
        refreshPanel();
    }

    @Override
    public void onWebError() {
        appendConsole("PAGE error");
        refreshPanel();
    }

    @Override
    public void onWebConsole(String line) {
        appendConsole(line);
    }

    @Override
    public void onWebRequest(String method, String url, boolean mainFrame) {
        appendNetwork(formatNetwork("WEBVIEW", method, url, 0, 0, mainFrame ? "main" : "sub"));
    }

    @Override
    public void onWebRequest(String method, String url, boolean mainFrame, Map<String, String> headers) {
        appendNetwork(formatNetwork("WEBVIEW", method, url, 0, 0, mainFrame ? "main" : "sub") + headerText(headers));
    }

    @Override
    public void onWebNetwork(String type, String method, String url, int status, long durationMs, String detail) {
        appendNetwork(formatNetwork(type, method, url, status, durationMs, detail));
    }

    private String formatNetwork(String type, String method, String url, int status, long durationMs, String detail) {
        return String.format(Locale.ROOT, "%-11s %-6s %-6s %-5s %-6s %s",
                empty(type),
                empty(method),
                status <= 0 ? "-" : String.valueOf(status),
                durationMs <= 0 ? "-" : durationMs + "ms",
                TextUtils.isEmpty(detail) ? "-" : detail,
                url == null ? "" : url);
    }

    private String headerText(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("  headers=");
        int count = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (count++ > 0) builder.append("; ");
            builder.append(entry.getKey()).append(':').append(entry.getValue());
            if (count >= 4) break;
        }
        return builder.toString();
    }

    private String empty(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }
}
