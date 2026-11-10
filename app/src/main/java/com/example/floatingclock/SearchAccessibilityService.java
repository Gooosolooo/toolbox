package com.example.floatingclock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.InputMethod;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Locale;

/** 只在用户发起的一次搜索期间查看指定 App 的当前窗口。 */
public class SearchAccessibilityService extends AccessibilityService {
    private static final String LOG_TAG = "ToolboxSearch";
    private static final String PERMISSION_LOG_TAG = "ToolboxSearchPermission";

    private static final long TIMEOUT_MILLIS = 12000;
    private static final long POLL_INTERVAL_MILLIS = 250;
    private static final int MAX_NODES = 450;
    private static final String SEARCH = "搜索";
    private static final String TAOBAO = "com.taobao.taobao";
    private static final String JD = "com.jingdong.app.mall";
    private static final String DOUYIN_MALL = "com.ss.android.ugc.livelite";
    private static final String XIAOHONGSHU = "com.xingin.xhs";
    private static final String MALL_SEARCH_INPUT = "com.ss.android.ugc.livelite:id/nmu";
    private static final String JD_HOME_SEARCH = "com.jingdong.app.mall:id/home_search_content_id";

    private static SearchAccessibilityService connectedService;
    private static int lastLoggedPermissionState = -1;

    private enum Step {
        FIND_ENTRY, FIND_INPUT, SUBMIT
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollTarget;
    private String targetPackage;
    private String keyword;
    private long deadline;
    private boolean targetSeen;
    private Step step;
    private String lastCheck;

    static boolean isReady() {
        return connectedService != null;
    }

    static boolean isEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null) {
            return false;
        }
        ComponentName service = new ComponentName(context, SearchAccessibilityService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (service.equals(ComponentName.unflattenFromString(info.getId()))) {
                return true;
            }
        }
        return false;
    }

    static void logPermissionState(Context context, String event, boolean enabled, boolean connected) {
        if (!isDebuggable(context)) {
            return;
        }
        int state = (enabled ? 2 : 0) | (connected ? 1 : 0);
        if (state == lastLoggedPermissionState) {
            return;
        }
        lastLoggedPermissionState = state;
        Log.d(PERMISSION_LOG_TAG, event + ": enabled=" + enabled + ", connected=" + connected);
    }

    private void logServiceConnection(String event) {
        if (isDebuggable(this)) {
            logPermissionState(this, event, isEnabled(this), isReady());
        }
    }

    private static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    static boolean requestSearch(String targetPackage, String keyword) {
        if (connectedService == null) {
            return false;
        }
        connectedService.beginSearch(targetPackage, keyword);
        return true;
    }

    static void cancelSearch() {
        if (connectedService != null) {
            connectedService.clearSearch();
        }
    }

    @Override
    protected void onServiceConnected() {
        connectedService = this;
        logServiceConnection("service_connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 按需轮询，不订阅其他应用的界面事件。
    }

    @Override
    public void onInterrupt() {
        clearSearch();
    }

    @Override
    public void onDestroy() {
        clearSearch();
        if (connectedService == this) {
            connectedService = null;
            logServiceConnection("service_disconnected");
        }
        super.onDestroy();
    }

    private void beginSearch(String packageName, String query) {
        clearSearch();
        targetPackage = packageName;
        keyword = query;
        deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS;
        step = Step.FIND_ENTRY;
        lastCheck = "awaiting target window";
        trace("started");
        handler.postDelayed(poll, POLL_INTERVAL_MILLIS);
    }

    private void clearSearch() {
        handler.removeCallbacks(poll);
        targetPackage = null;
        keyword = null;
        targetSeen = false;
    }

    private void pollTarget() {
        if (targetPackage == null) {
            return;
        }
        if (SystemClock.uptimeMillis() >= deadline) {
            trace("timed out at " + step + ": " + lastCheck);
            clearSearch();
            Toast.makeText(this, R.string.search_assist_failed, Toast.LENGTH_LONG).show();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (targetPackage.contentEquals(valueOf(root.getPackageName()))) {
                targetSeen = true;
                advance(root);
            } else if (targetSeen) {
                trace("active window switched while " + step);
                clearSearch();
            }
        }
        if (targetPackage != null) {
            handler.postDelayed(poll, POLL_INTERVAL_MILLIS);
        }
    }

    private void advance(AccessibilityNodeInfo root) {
        SearchControls controls = findControls(root);
        if (step == Step.SUBMIT) {
            AccessibilityNodeInfo input = controls.input.unique();
            if (input == null) {
                lastCheck = "no unique search field";
            } else if (!matchesKeyword(valueOf(input.getText()))) {
                lastCheck = "field text not confirmed";
            } else if (submitWithKeyboard(input)) {
                trace("keyboard action sent");
                clearSearch();
            } else {
                lastCheck = input.isFocused() ? "no keyboard action" : "search field not focused";
            }
            return;
        }

        AccessibilityNodeInfo input = controls.input.unique();
        AccessibilityNodeInfo entry = controls.entry.unique();
        lastCheck = input == null ? "no unique search field" : "checking search field";
        boolean preferEntry = step == Step.FIND_ENTRY && entry != null
                && (TAOBAO.equals(targetPackage) || JD.equals(targetPackage));
        if (input != null && !preferEntry) {
            if (!input.isFocused()) {
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                return;
            }
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, keyword);
            if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                trace("text action accepted");
                step = Step.SUBMIT;
            }
        } else if (step == Step.FIND_ENTRY) {
            if (entry != null && openEntry(entry)) {
                trace("search entry opened");
                step = Step.FIND_INPUT;
            }
        }
    }

    private SearchControls findControls(AccessibilityNodeInfo root) {
        SearchControls controls = new SearchControls();
        Rect rootBounds = new Rect();
        Rect bounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int headerBottom = rootBounds.top + rootBounds.height() / 3;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int inspected = 0;
        while (!pending.isEmpty() && inspected++ < MAX_NODES) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (!targetPackage.contentEquals(valueOf(node.getPackageName()))) {
                continue;
            }
            node.getBoundsInScreen(bounds);
            if (node.isVisibleToUser() && node.isEnabled() && bounds.centerY() <= headerBottom) {
                inspect(node, controls);
            }
            for (int index = 0; index < node.getChildCount() && pending.size() < MAX_NODES; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        return controls;
    }

    private void inspect(AccessibilityNodeInfo node, SearchControls controls) {
        String text = valueOf(node.getText()).trim();
        String description = valueOf(node.getContentDescription()).trim();
        String hint = valueOf(node.getHintText()).trim();
        String id = valueOf(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        boolean searchLabel = isSearchLabel(text) || isSearchLabel(description) || isSearchLabel(hint);
        if (node.isEditable() && !node.isPassword()) {
            boolean namedSearchField = searchLabel || id.contains("search") || MALL_SEARCH_INPUT.equals(id);
            if (namedSearchField || (node.isFocused()
                    && (step != Step.FIND_ENTRY || isFocusedXiaohongshuSearch()))) {
                boolean verifiedQuery = step == Step.SUBMIT && matchesKeyword(valueOf(node.getText()));
                controls.input.add(node, verifiedQuery ? 3 : namedSearchField ? 2 : 1);
            } else if (step == Step.SUBMIT && matchesKeyword(valueOf(node.getText()))) {
                controls.input.add(node, 3);
            }
            return;
        }

        if (step == Step.FIND_ENTRY) {
            addEntry(node, controls.entry, text, description, id, searchLabel);
        }
    }

    private boolean matchesKeyword(String fieldText) {
        return keyword.equals(fieldText) || (XIAOHONGSHU.equals(targetPackage)
                && ("搜索, " + keyword).equals(fieldText));
    }

    private void addEntry(AccessibilityNodeInfo node, Candidate entry, String text,
            String description, String id, boolean searchLabel) {
        if (TAOBAO.equals(targetPackage)) {
            if ("搜索栏".equals(description) && node.isClickable()) {
                entry.add(node, 1);
            }
        } else if (JD.equals(targetPackage)) {
            if (JD_HOME_SEARCH.equals(id) && "搜索栏".equals(description)) {
                entry.add(node, 1);
            }
        } else if (DOUYIN_MALL.equals(targetPackage)) {
            if (SEARCH.equals(description) && "android.view.ViewGroup".contentEquals(
                    valueOf(node.getClassName()))) {
                entry.add(node, 1);
            }
        } else if (XIAOHONGSHU.equals(targetPackage) && SEARCH.equals(text)
                && "android.widget.TextView".equals(valueOf(node.getClassName()))) {
            addXiaohongshuResultField(node, entry);
        } else if (searchLabel || "搜索视频、番剧或up主".equals(text)) {
            AccessibilityNodeInfo clickable = findClickable(node);
            if (clickable != null) {
                entry.add(clickable, 1);
            }
        }
    }

    private boolean isFocusedXiaohongshuSearch() {
        if (!XIAOHONGSHU.equals(targetPackage)
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        InputMethod inputMethod = getInputMethod();
        EditorInfo editor = inputMethod == null ? null : inputMethod.getCurrentInputEditorInfo();
        return editor != null && targetPackage.equals(editor.packageName)
                && (editor.imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH;
    }

    /** 旧结果页的关键词是可点击文本，点击它可返回编辑态。 */
    private void addXiaohongshuResultField(AccessibilityNodeInfo button, Candidate entry) {
        AccessibilityNodeInfo parent = button.getParent();
        if (parent == null) {
            return;
        }
        Rect buttonBounds = new Rect();
        Rect fieldBounds = new Rect();
        button.getBoundsInScreen(buttonBounds);
        for (int index = 0; index < parent.getChildCount(); index++) {
            AccessibilityNodeInfo child = parent.getChild(index);
            if (child == null || !child.isClickable() || !child.isEnabled() || !child.isVisibleToUser()
                    || !targetPackage.equals(valueOf(child.getPackageName()))
                    || !"android.widget.TextView".equals(valueOf(child.getClassName()))
                    || valueOf(child.getText()).isEmpty()) {
                continue;
            }
            child.getBoundsInScreen(fieldBounds);
            if (fieldBounds.right <= buttonBounds.left && fieldBounds.width() > buttonBounds.width()
                    && fieldBounds.top == buttonBounds.top && fieldBounds.bottom == buttonBounds.bottom) {
                entry.add(child, 2);
            }
        }
    }

    private boolean openEntry(AccessibilityNodeInfo entry) {
        if (entry.isClickable() && entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        // 只有真机验证过的入口允许用当前控件边界执行手势。
        if (!JD.equals(targetPackage) && !DOUYIN_MALL.equals(targetPackage)) {
            return false;
        }
        return tapNode(entry);
    }

    private boolean tapNode(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 45))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean submitWithKeyboard(AccessibilityNodeInfo input) {
        if (!input.isFocused()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && submitEditorAction()) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        AccessibilityNodeInfo.AccessibilityAction imeAction =
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER;
        return input.getActionList().contains(imeAction) && input.performAction(imeAction.getId());
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.TIRAMISU)
    private boolean submitEditorAction() {
        InputMethod inputMethod = getInputMethod();
        if (inputMethod == null || !inputMethod.getCurrentInputStarted()) {
            return false;
        }
        EditorInfo editor = inputMethod.getCurrentInputEditorInfo();
        InputMethod.AccessibilityInputConnection connection = inputMethod.getCurrentInputConnection();
        if (editor == null || connection == null || !targetPackage.equals(editor.packageName)) {
            return false;
        }
        int action = editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        trace("editor action " + action + ", custom action: " + (editor.actionLabel != null));
        if (editor.actionLabel != null && editor.actionLabel.length() > 0) {
            connection.performEditorAction(editor.actionId);
        } else if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_GO
                || action == EditorInfo.IME_ACTION_DONE) {
            connection.performEditorAction(action);
        } else if ((action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE)
                && (editor.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        } else {
            return false;
        }
        return true;
    }

    private void trace(String stage) {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.d(LOG_TAG, targetPackage + ": " + stage);
        }
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo candidate = node;
        for (int depth = 0; depth < 3 && candidate != null; depth++) {
            if (candidate.isEditable() || !targetPackage.contentEquals(valueOf(candidate.getPackageName()))) {
                return null;
            }
            if (candidate.isClickable() && candidate.isEnabled() && candidate.isVisibleToUser()) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static boolean isSearchLabel(String value) {
        return SEARCH.equals(value) || "搜一搜".equals(value) || "搜索框".equals(value)
                || "搜索按钮".equals(value) || "搜索商品".equals(value)
                || "搜索你感兴趣的内容".equals(value) || "输入关键词搜索".equals(value)
                || "请输入搜索关键词".equals(value) || "search".equalsIgnoreCase(value);
    }

    private static String valueOf(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static final class SearchControls {
        private final Candidate input = new Candidate();
        private final Candidate entry = new Candidate();
    }

    /** 同一优先级有多个控件时，不猜测用户意图。 */
    private static final class Candidate {
        private AccessibilityNodeInfo node;
        private int priority;
        private boolean ambiguous;

        private void add(AccessibilityNodeInfo candidate, int candidatePriority) {
            if (candidatePriority > priority) {
                node = candidate;
                priority = candidatePriority;
                ambiguous = false;
            } else if (candidatePriority == priority && !candidate.equals(node)) {
                ambiguous = true;
            }
        }

        private AccessibilityNodeInfo unique() {
            return ambiguous ? null : node;
        }
    }
}
