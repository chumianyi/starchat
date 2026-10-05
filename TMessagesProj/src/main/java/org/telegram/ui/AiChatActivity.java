/*
 * This is the source code StarChat for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * StarChat AI Assistant chat — streams replies from the dpsk service
 * (DeepSeek-R1), with collapsible reasoning_content.
 */

package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

public class AiChatActivity extends BaseFragment {

    private static final String TAG = "AiChat";

    // dpsk service endpoint
    private static final String BASE_URL = "http://103.236.99.177:24512/api/dpsk";
    // Built-in shared AI channel credentials (change here if the server requires others)
    private static final String BUILTIN_USERNAME = "starchat";
    private static final String BUILTIN_PASSWORD = "starchat";

    private static final int ROLE_USER = 1;
    private static final int ROLE_AI = 2;

    private static final int MENU_CLEAR = 1;

    private LinearLayout contentLayout;
    private RecyclerListView listView;
    private ChatAdapter adapter;
    private EditText messageEditText;
    private ImageView sendButton;
    private View inputBar;
    private boolean inFlight = false;

    private final ArrayList<AiMessage> messages = new ArrayList<>();
    private File historyFile;
    private SharedPreferences dpskPrefs;

    private static class AiMessage {
        int role;
        String content = "";
        String reasoning = "";
        boolean reasoningExpanded = false;
        boolean streaming = false;
        boolean error = false;
        boolean reasoningTouched = false; // user manually toggled

        AiMessage(int role) {
            this.role = role;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        dpskPrefs = ApplicationLoader.applicationContext.getSharedPreferences("dpsk", Context.MODE_PRIVATE);
        historyFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "ai_chat_history.json");
        loadHistory();
        if (messages.isEmpty()) {
            AiMessage welcome = new AiMessage(ROLE_AI);
            welcome.content = LocaleController.getString(R.string.AiWelcome);
            messages.add(welcome);
            saveHistory();
        }
        return super.onFragmentCreate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.AiAssistant));
        actionBar.setSubtitle(LocaleController.getString(R.string.AiAssistantStatus));
        actionBar.setAllowOverlayTitle(true);
        actionBar.createMenu().addItem(MENU_CLEAR, R.drawable.ic_ab_other);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR) {
                    showClearConfirm();
                }
            }
        });

        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setItemAnimator(null);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(true);
        listView.setClipToPadding(false);
        adapter = new ChatAdapter();
        listView.setAdapter(adapter);
        listView.setLayoutFrozen(false);
        listView.setOnItemClickListener((view, position) -> {
            AiMessage msg = messages.get(position);
            if (msg.error) {
                retryAfterError(msg);
            }
        });
        contentLayout.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        inputBar = createInputBar(context);
        contentLayout.addView(inputBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fragmentView = contentLayout;
        updateSendButton();

        AndroidUtilities.runOnUIThread(() -> listView.scrollToPosition(messages.size() - 1), 100);
        return fragmentView;
    }

    private View createInputBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        int padV = AndroidUtilities.dp(7);
        bar.setPadding(AndroidUtilities.dp(10), padV, AndroidUtilities.dp(8), padV + AndroidUtilities.navigationBarHeight);
        View divider = new View(context);
        divider.setBackgroundColor(getThemedColor(Theme.key_divider));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        // divider is added below via wrapper; simpler: draw top border using background only — skip.

        messageEditText = new EditText(context);
        messageEditText.setBackground(null);
        messageEditText.setMaxLines(4);
        messageEditText.setHint(LocaleController.getString(R.string.AiInputHint));
        messageEditText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        messageEditText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        messageEditText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        messageEditText.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        messageEditText.setSingleLine(false);
        messageEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateSendButton(); }
        });
        messageEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        bar.addView(messageEditText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.send_plane_24);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        int btnSize = AndroidUtilities.dp(44);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        btnLp.gravity = Gravity.BOTTOM;
        sendButton.setLayoutParams(btnLp);
        sendButton.setOnClickListener(v -> sendMessage());
        bar.addView(sendButton);

        // top divider
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        wrapper.addView(divider, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.TOP));
        wrapper.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private void updateSendButton() {
        if (sendButton == null || messageEditText == null) {
            return;
        }
        boolean hasText = messageEditText.getText().length() > 0;
        boolean enabled = hasText && !inFlight;
        sendButton.setEnabled(enabled);
        sendButton.setColorFilter(enabled ? getThemedColor(Theme.key_chat_outBubble) : getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        sendButton.setAlpha(enabled ? 1f : 0.6f);
    }

    private void sendMessage() {
        if (inFlight || messageEditText == null) {
            return;
        }
        String text = messageEditText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        AiMessage userMsg = new AiMessage(ROLE_USER);
        userMsg.content = text;
        messages.add(userMsg);

        AiMessage pending = new AiMessage(ROLE_AI);
        pending.streaming = true;
        messages.add(pending);

        adapter.notifyItemRangeInserted(messages.size() - 2, 2);
        messageEditText.setText("");
        startStream(pending);
    }

    private void retryAfterError(AiMessage errorMsg) {
        int idx = messages.indexOf(errorMsg);
        if (idx <= 0) {
            return;
        }
        messages.remove(idx);
        AiMessage pending = new AiMessage(ROLE_AI);
        pending.streaming = true;
        messages.add(pending);
        adapter.notifyDataSetChanged();
        startStream(pending);
    }

    private void showClearConfirm() {
        if (inFlight) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getString(R.string.AiClearChat));
        builder.setMessage(LocaleController.getString(R.string.AiClearChat));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
            messages.clear();
            AiMessage welcome = new AiMessage(ROLE_AI);
            welcome.content = LocaleController.getString(R.string.AiWelcome);
            messages.add(welcome);
            saveHistory();
            adapter.notifyDataSetChanged();
        });
        showDialog(builder.create());
    }

    // ===================== streaming =====================

    private void startStream(AiMessage pending) {
        inFlight = true;
        updateSendButton();
        final ArrayList<AiMessage> snapshot = new ArrayList<>(messages);
        new Thread(() -> {
            DpskClient client = new DpskClient();
            try {
                client.ensureLogin();
                client.streamChat(snapshot, new DpskClient.StreamCallback() {
                    long lastUiPush = 0;

                    @Override public void onReasoning(String full) {
                        pending.reasoning = full;
                        if (!pending.reasoningTouched && !pending.reasoningExpanded && pending.content.isEmpty()) {
                            pending.reasoningExpanded = true; // auto-expand while thinking
                        }
                        throttle();
                    }

                    @Override public void onContent(String full) {
                        pending.content = full;
                        if (!pending.reasoningTouched) {
                            pending.reasoningExpanded = false; // auto-collapse when answer starts
                        }
                        throttle();
                    }

                    private void throttle() {
                        long now = SystemClock.uptimeMillis();
                        if (now - lastUiPush > 60) {
                            lastUiPush = now;
                            pushUpdate(pending);
                        }
                    }

                    @Override public void onDone() {
                        finishStream(pending, false, null);
                    }

                    @Override public void onError(String err) {
                        finishStream(pending, true, err);
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
                finishStream(pending, true, e.getMessage());
            }
        }).start();
    }

    private void pushUpdate(AiMessage pending) {
        AndroidUtilities.runOnUIThread(() -> {
            int idx = messages.indexOf(pending);
            if (idx >= 0 && adapter != null) {
                adapter.notifyItemChanged(idx);
                scrollIfNearBottom();
            }
        });
    }

    private void finishStream(AiMessage pending, boolean error, String err) {
        AndroidUtilities.runOnUIThread(() -> {
            int idx = messages.indexOf(pending);
            pending.streaming = false;
            if (error) {
                pending.error = true;
                if (TextUtils.isEmpty(pending.content)) {
                    pending.content = LocaleController.getString(R.string.AiError) + (err != null ? "\n(" + err + ")" : "");
                }
            } else {
                pending.error = false;
            }
            inFlight = false;
            updateSendButton();
            if (idx >= 0 && adapter != null) {
                adapter.notifyItemChanged(idx);
                listView.scrollToPosition(messages.size() - 1);
            }
            saveHistory();
        });
    }

    private void scrollIfNearBottom() {
        if (listView == null) {
            return;
        }
        LinearLayoutManager lm = (LinearLayoutManager) listView.getLayoutManager();
        if (lm != null && lm.findLastVisibleItemPosition() >= messages.size() - 3) {
            listView.scrollToPosition(messages.size() - 1);
        }
    }

    // ===================== persistence =====================

    private void loadHistory() {
        try {
            if (!historyFile.exists()) {
                return;
            }
            String raw = new String(readAll(historyFile.toURL().openStream()), "UTF-8");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AiMessage msg = new AiMessage(o.optString("role", "ai").equals("user") ? ROLE_USER : ROLE_AI);
                msg.content = o.optString("content", "");
                msg.reasoning = o.optString("reasoning", "");
                messages.add(msg);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            for (AiMessage msg : messages) {
                JSONObject o = new JSONObject();
                o.put("role", msg.role == ROLE_USER ? "user" : "ai");
                o.put("content", msg.content);
                o.put("reasoning", msg.reasoning);
                arr.put(o);
            }
            OutputStream os = new java.io.FileOutputStream(historyFile);
            os.write(arr.toString().getBytes("UTF-8"));
            os.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toByteArray();
    }

    // ===================== adapter =====================

    private class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemCount() {
            return messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            return messages.get(position).role == ROLE_USER ? ROLE_USER : ROLE_AI;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context context = parent.getContext();
            BubbleCell cell = new BubbleCell(context, viewType);
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((BubbleCell) holder.itemView).bind(messages.get(position));
        }
    }

    @SuppressLint("ViewConstructor")
    private class BubbleCell extends LinearLayout {

        private final boolean isUser;
        private final TextView bodyText;
        private final TextView reasoningHeader;
        private final TextView reasoningText;
        private final LinearLayout bubble;
        private final LinearLayout reasoningBox;

        BubbleCell(Context context, int viewType) {
            super(context);
            isUser = viewType == ROLE_USER;
            setOrientation(HORIZONTAL);
            setGravity(isUser ? Gravity.RIGHT : Gravity.LEFT);
            int pad = AndroidUtilities.dp(4);
            setPadding(AndroidUtilities.dp(8), pad, AndroidUtilities.dp(8), pad);

            bubble = new LinearLayout(context);
            bubble.setOrientation(VERTICAL);
            int maxWidth = Math.min(AndroidUtilities.displaySize.x - AndroidUtilities.dp(40), AndroidUtilities.dp(480));
            LayoutParams blp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            blp.width = LayoutParams.WRAP_CONTENT;
            bubble.setMaxWidth(maxWidth);
            bubble.setMinimumWidth(AndroidUtilities.dp(36));

            int bubblePadH = AndroidUtilities.dp(11);
            int bubblePadV = AndroidUtilities.dp(8);
            bubble.setPadding(bubblePadH, bubblePadV, bubblePadH, bubblePadV);

            reasoningHeader = new TextView(context);
            reasoningHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            reasoningHeader.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            reasoningHeader.setPadding(0, 0, 0, AndroidUtilities.dp(2));
            reasoningHeader.setVisibility(GONE);

            reasoningText = new TextView(context);
            reasoningText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            reasoningText.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            reasoningText.setVisibility(GONE);
            reasoningText.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(6));

            bodyText = new TextView(context);
            bodyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            bodyText.setLineSpacing(AndroidUtilities.dp(1), 1.05f);
            bodyText.setLinkTextColor(0xFF4EA8F0);

            reasoningBox = new LinearLayout(context);
            reasoningBox.setOrientation(VERTICAL);
            reasoningBox.setVisibility(GONE);
            reasoningBox.addView(reasoningHeader);
            reasoningBox.addView(reasoningText);

            bubble.addView(reasoningBox);
            bubble.addView(bodyText);
            addView(bubble, blp);
        }

        void bind(AiMessage msg) {
            int bubbleColor = getThemedColor(isUser ? Theme.key_chat_outBubble : Theme.key_chat_inBubble);
            int textColor = getThemedColor(isUser ? Theme.key_chat_outBubbleText : Theme.key_chat_inBubbleText);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            float r = AndroidUtilities.dp(16);
            bg.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
            bg.setColor(bubbleColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                bubble.setBackground(bg);
            } else {
                bubble.setBackgroundDrawable(bg);
            }

            bodyText.setTextColor(msg.error ? getThemedColor(Theme.key_text_RedRegular) : textColor);
            String shown = msg.content;
            if (msg.streaming && TextUtils.isEmpty(shown) && TextUtils.isEmpty(msg.reasoning)) {
                shown = "…";
            }
            bodyText.setText(shown);
            bodyText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

            // reasoning block
            boolean hasReasoning = !TextUtils.isEmpty(msg.reasoning);
            if (hasReasoning && !isUser) {
                reasoningBox.setVisibility(VISIBLE);
                reasoningHeader.setVisibility(VISIBLE);
                boolean expanded = msg.reasoningExpanded;
                reasoningHeader.setText((expanded ? "▾ " : "▸ ") + LocaleController.getString(R.string.AiThinking));
                reasoningHeader.setTextColor(Theme.multAlpha(textColor, 0.62f));
                reasoningText.setVisibility(expanded ? VISIBLE : GONE);
                reasoningText.setText(msg.reasoning);
                reasoningText.setTextColor(Theme.multAlpha(textColor, 0.55f));
                reasoningHeader.setOnClickListener(v -> {
                    msg.reasoningTouched = true;
                    msg.reasoningExpanded = !msg.reasoningExpanded;
                    int idx = messages.indexOf(msg);
                    if (idx >= 0) {
                        adapter.notifyItemChanged(idx);
                    }
                });
            } else {
                reasoningBox.setVisibility(GONE);
                reasoningHeader.setVisibility(GONE);
                reasoningText.setVisibility(GONE);
            }

            bubble.setClickable(msg.error);
        }
    }

    // ===================== dpsk client =====================

    private class DpskClient {

        private String cookie;

        interface StreamCallback {
            void onReasoning(String full);
            void onContent(String full);
            void onDone();
            void onError(String err);
        }

        void ensureLogin() throws Exception {
            cookie = dpskPrefs.getString("cookie", null);
            if (cookie != null && !cookie.isEmpty()) {
                return;
            }
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("username", BUILTIN_USERNAME);
                body.put("password", BUILTIN_PASSWORD);
                conn = openConnection(BASE_URL + "/login", "POST", false);
                writeJson(conn, body);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String setCookie = extractCookies(conn);
                    if (setCookie != null && !setCookie.isEmpty()) {
                        cookie = setCookie;
                        dpskPrefs.edit().putString("cookie", cookie).apply();
                    }
                } else {
                    FileLog.d(TAG, "login returned " + code + " " + readError(conn));
                    // continue anyway — the chat endpoint may use a shared channel
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        void streamChat(ArrayList<AiMessage> snapshot, StreamCallback cb) {
            try {
                JSONArray msgs = new JSONArray();
                for (AiMessage m : snapshot) {
                    if (m.error || m.streaming) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("role", m.role == ROLE_USER ? "user" : "assistant");
                    o.put("content", m.content);
                    msgs.put(o);
                }
                JSONObject body = new JSONObject();
                body.put("messages", msgs);
                body.put("stream", true);
                body.put("mode", "thinking");

                HttpURLConnection conn = openConnection(BASE_URL + "/chat", "POST", true);
                if (cookie != null) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                writeJson(conn, body);
                int code = conn.getResponseCode();
                if (code == 401 || code == 403) {
                    // session may have expired — re-login once and retry
                    conn.disconnect();
                    dpskPrefs.edit().remove("cookie").apply();
                    cookie = null;
                    ensureLogin();
                    conn = openConnection(BASE_URL + "/chat", "POST", true);
                    if (cookie != null) {
                        conn.setRequestProperty("Cookie", cookie);
                    }
                    writeJson(conn, body);
                    code = conn.getResponseCode();
                }
                if (code < 200 || code >= 300) {
                    cb.onError("HTTP " + code + " " + readError(conn));
                    conn.disconnect();
                    return;
                }

                StringBuilder reasoning = new StringBuilder();
                StringBuilder content = new StringBuilder();

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    String payload = line.trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if (payload.toLowerCase().startsWith("data:")) {
                        payload = payload.substring(5).trim();
                    } else if (payload.startsWith(":")) {
                        continue; // SSE comment / heartbeat
                    }
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    JSONObject chunk;
                    try {
                        chunk = new JSONObject(payload);
                    } catch (Exception notJson) {
                        continue;
                    }
                    String[] delta = extractDelta(chunk);
                    if (delta == null) {
                        continue;
                    }
                    if (delta[0] != null) {
                        reasoning.append(delta[0]);
                        cb.onReasoning(reasoning.toString());
                    }
                    if (delta[1] != null) {
                        content.append(delta[1]);
                        cb.onContent(content.toString());
                    }
                }
                reader.close();
                conn.disconnect();
                cb.onDone();
            } catch (Exception e) {
                FileLog.e(e);
                cb.onError(e.getMessage());
            }
        }

        /** returns [reasoningDelta, contentDelta], either may be null */
        private String[] extractDelta(JSONObject obj) {
            String reasoning = null;
            String content = null;
            JSONObject holder = null;
            JSONArray choices = obj.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject first = choices.optJSONObject(0);
                if (first != null) {
                    holder = first.optJSONObject("delta");
                    if (holder == null) {
                        holder = first.optJSONObject("message");
                    }
                }
            }
            if (holder == null) {
                holder = obj.optJSONObject("delta");
            }
            if (holder == null) {
                holder = obj.optJSONObject("message");
            }
            if (holder != null) {
                content = holder.optString("content", null);
                reasoning = holder.optString("reasoning_content", holder.optString("reasoning", null));
            }
            if (content == null && reasoning == null) {
                content = obj.optString("content", null);
                reasoning = obj.optString("reasoning_content", obj.optString("reasoning", null));
            }
            if (content == null && reasoning == null) {
                content = obj.optString("answer", null);
            }
            if (content == null && reasoning == null) {
                JSONObject data = obj.optJSONObject("data");
                if (data != null) {
                    return extractDelta(data);
                }
            }
            if (content == null && reasoning == null) {
                return null;
            }
            return new String[]{reasoning, content};
        }

        private HttpURLConnection openConnection(String urlStr, String method, boolean stream) throws Exception {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(stream ? 300000 : 30000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            conn.setInstanceFollowRedirects(true);
            conn.setDoInput(true);
            return conn;
        }

        private void writeJson(HttpURLConnection conn, JSONObject body) throws Exception {
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
        }

        private String extractCookies(HttpURLConnection conn) {
            StringBuilder sb = new StringBuilder();
            java.util.Map<String, java.util.List<String>> headers = conn.getHeaderFields();
            if (headers == null) {
                return null;
            }
            for (java.util.Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String v : e.getValue()) {
                        // keep only the name=value pair
                        int cut = v.indexOf(';');
                        String pair = cut >= 0 ? v.substring(0, cut) : v;
                        if (sb.length() > 0) {
                            sb.append("; ");
                        }
                        sb.append(pair);
                    }
                }
            }
            return sb.toString();
        }

        private String readError(HttpURLConnection conn) {
            try {
                InputStream is = conn.getErrorStream();
                if (is == null) {
                    return "";
                }
                return new String(readAll(is), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }
}
