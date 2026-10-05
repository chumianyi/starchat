/*
 * StarChat - built-in AI assistant conversation.
 * Streaming chat bubbles; DeepSeek-R1 reasoning is shown folded.
 */
package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ai.DpskClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StarChatAIActivity extends Activity {

    private static final int BRAND = Color.rgb(0x2A, 0xAB, 0xEE);
    private static final int BRAND_DARK = Color.rgb(0x22, 0x9E, 0xD9);
    private static final int LIST_BG = Color.rgb(0xE7, 0xEB, 0xF0);
    private static final int AI_BUBBLE = Color.WHITE;
    private static final int TEXT_DARK = Color.rgb(0x17, 0x21, 0x2B);
    private static final int TEXT_GREY = Color.rgb(0x7A, 0x86, 0x91);
    private static final int REASON_TINT = Color.rgb(0x5B, 0x7E, 0x9E);

    private final List<Bubble> bubbles = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private BubbleAdapter adapter;
    private ListView listView;
    private EditText input;
    private TextView sendButton;
    private boolean generating;

    private static final class Bubble {
        final boolean user;
        String content = "";
        String reasoning = "";
        boolean reasoningExpanded;
        boolean streaming;
        boolean error;

        Bubble(boolean user) {
            this.user = user;
        }
    }

    private static final class Holder {
        LinearLayout bubble;
        TextView reasonHeader;
        TextView reasonText;
        TextView contentText;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            window.setStatusBarColor(BRAND_DARK);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LIST_BG);

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(52)));

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setCacheColorHint(0);
        listView.setSelector(android.R.color.transparent);
        listView.setStackFromBottom(true);
        listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        listView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6),
                AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        listView.setClipToPadding(false);
        adapter = new BubbleAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildInputBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        if (bubbles.isEmpty()) {
            Bubble greet = new Bubble(false);
            greet.content = "你好，我是星聊 AI 助手（DeepSeek-R1）。\n有什么可以帮你的吗？";
            bubbles.add(greet);
            adapter.notifyDataSetChanged();
        }
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BRAND);

        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextColor(Color.WHITE);
        back.setTextSize(30);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(AndroidUtilities.dp(48),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("星聊 AI 助手");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView settings = new TextView(this);
        settings.setText("设置");
        settings.setTextColor(Color.WHITE);
        settings.setTextSize(14);
        settings.setGravity(Gravity.CENTER);
        settings.setOnClickListener(v -> showCredentialsDialog());
        bar.addView(settings, new LinearLayout.LayoutParams(AndroidUtilities.dp(64),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return bar;
    }

    private View buildInputBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8),
                AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(0xE0, 0xE3, 0xE8));
        // top hairline via padding-less overlay is unnecessary; keep simple

        input = new EditText(this);
        input.setHint("发消息给 AI…");
        input.setTextColor(TEXT_DARK);
        input.setHintTextColor(TEXT_GREY);
        input.setTextSize(16);
        input.setBackground(null);
        input.setMaxLines(5);
        input.setMinLines(1);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(input, ilp);

        sendButton = new TextView(this);
        sendButton.setText("发送");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(15);
        sendButton.setTypeface(Typeface.DEFAULT_BOLD);
        sendButton.setGravity(Gravity.CENTER);
        GradientDrawable sb = rounded(BRAND, AndroidUtilities.dp(18), 0, 0);
        sendButton.setBackground(sb);
        sendButton.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(9),
                AndroidUtilities.dp(18), AndroidUtilities.dp(9));
        sendButton.setOnClickListener(v -> onSend());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(AndroidUtilities.dp(8));
        bar.addView(sendButton, slp);
        return bar;
    }

    private void onSend() {
        if (generating) {
            return;
        }
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        Bubble userBubble = new Bubble(true);
        userBubble.content = text;
        bubbles.add(userBubble);

        Bubble ai = new Bubble(false);
        ai.streaming = true;
        ai.content = "…";
        bubbles.add(ai);

        input.setText("");
        setSending(true);
        adapter.notifyDataSetChanged();
        scrollToEnd();

        final List<DpskClient.Msg> history = new ArrayList<>();
        for (Bubble b : bubbles) {
            if (b == ai || TextUtils.isEmpty(b.content) || "…".equals(b.content)) {
                continue;
            }
            history.add(new DpskClient.Msg(b.user ? "user" : "assistant", b.content));
        }

        pool.execute(() -> runTurn(ai, history, 0));
    }

    /** Runs one streaming turn; retries once after re-login on AUTH failure. */
    private void runTurn(final Bubble ai, final List<DpskClient.Msg> history, final int attempt) {
        if (TextUtils.isEmpty(DpskClient.getCookie())) {
            try {
                DpskClient.loginBlocking(getUser(), getPass());
            } catch (Exception e) {
                postError(ai, "登录失败：" + msg(e));
                return;
            }
        }
        DpskClient.chatStreamBlocking(history, new DpskClient.ChatCallback() {
            @Override
            public void onReasoning(final String delta) {
                ui.post(() -> {
                    if (TextUtils.isEmpty(ai.content) || "…".equals(ai.content)) {
                        ai.content = "";
                    }
                    ai.reasoning += delta;
                    ai.reasoningExpanded = true;
                    refresh(ai);
                });
            }

            @Override
            public void onContent(final String delta) {
                ui.post(() -> {
                    if ("…".equals(ai.content)) {
                        ai.content = "";
                    }
                    if (!TextUtils.isEmpty(ai.reasoning)) {
                        ai.reasoningExpanded = false; // fold reasoning once answer starts
                    }
                    ai.content += delta;
                    refresh(ai);
                });
            }

            @Override
            public void onDone() {
                ui.post(() -> {
                    ai.streaming = false;
                    if (TextUtils.isEmpty(ai.content)) {
                        ai.content = "（无回复）";
                    }
                    setSending(false);
                    refresh(ai);
                });
            }

            @Override
            public void onError(final String message) {
                if (message != null && message.startsWith("AUTH:") && attempt == 0) {
                    DpskClient.setCookie(null);
                    try {
                        DpskClient.loginBlocking(getUser(), getPass());
                    } catch (Exception e) {
                        postError(ai, "登录失败：" + msg(e));
                        return;
                    }
                    runTurn(ai, history, 1);
                    return;
                }
                postError(ai, message);
            }
        });
    }

    private void postError(final Bubble ai, final String message) {
        ui.post(() -> {
            ai.streaming = false;
            ai.error = true;
            String base = "…".equals(ai.content) ? "" : ai.content;
            ai.content = (TextUtils.isEmpty(base) ? "" : base + "\n") + "⚠ " + message;
            setSending(false);
            refresh(ai);
        });
    }

    private void refresh(Bubble ai) {
        adapter.notifyDataSetChanged();
        scrollToEnd();
    }

    private void scrollToEnd() {
        listView.setSelection(adapter.getCount() - 1);
    }

    private void setSending(boolean busy) {
        generating = busy;
        sendButton.setEnabled(!busy);
        sendButton.setAlpha(busy ? 0.5f : 1f);
    }

    private void showCredentialsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        box.setPadding(pad, pad, pad, 0);
        final EditText u = new EditText(this);
        u.setHint("用户名");
        u.setText(getUser());
        final EditText p = new EditText(this);
        p.setHint("密码");
        p.setText(getPass());
        box.addView(u);
        box.addView(p);
        new AlertDialog.Builder(this)
                .setTitle("AI 服务账号")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    getPrefs().edit()
                            .putString("user", u.getText().toString().trim())
                            .putString("pass", p.getText().toString().trim())
                            .apply();
                    DpskClient.setCookie(null);
                    Toast.makeText(this, "已保存，将重新登录", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.content.SharedPreferences getPrefs() {
        return getSharedPreferences("dpsk", MODE_PRIVATE);
    }

    private String getUser() {
        return getPrefs().getString("user", "starchat");
    }

    private String getPass() {
        return getPrefs().getString("pass", "starchat");
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private GradientDrawable rounded(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) {
            d.setStroke(strokeWidth, strokeColor);
        }
        return d;
    }

    private final class BubbleAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return bubbles.size();
        }

        @Override
        public Object getItem(int position) {
            return bubbles.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Bubble b = bubbles.get(position);
            Holder holder;
            FrameLayout row;
            if (convertView == null) {
                row = new FrameLayout(StarChatAIActivity.this);
                LinearLayout bubbleBox = new LinearLayout(StarChatAIActivity.this);
                bubbleBox.setOrientation(LinearLayout.VERTICAL);
                int pad = AndroidUtilities.dp(12);
                bubbleBox.setPadding(pad, AndroidUtilities.dp(9), pad, AndroidUtilities.dp(9));

                holder = new Holder();
                holder.bubble = bubbleBox;

                holder.reasonHeader = new TextView(StarChatAIActivity.this);
                holder.reasonHeader.setTextSize(13);
                holder.reasonHeader.setTypeface(Typeface.DEFAULT_BOLD);
                holder.reasonHeader.setPadding(0, 0, 0, AndroidUtilities.dp(4));
                bubbleBox.addView(holder.reasonHeader);

                holder.reasonText = new TextView(StarChatAIActivity.this);
                holder.reasonText.setTextSize(13);
                holder.reasonText.setTypeface(Typeface.MONOSPACE);
                holder.reasonText.setTextColor(TEXT_GREY);
                holder.reasonText.setPadding(0, 0, 0, AndroidUtilities.dp(6));
                bubbleBox.addView(holder.reasonText);

                holder.contentText = new TextView(StarChatAIActivity.this);
                holder.contentText.setTextSize(16);
                bubbleBox.addView(holder.contentText);

                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                row.addView(bubbleBox, blp);
                row.setTag(holder);
            } else {
                row = (FrameLayout) convertView;
                holder = (Holder) row.getTag();
            }

            int maxW = (int) (parent.getWidth() * 0.84f);
            FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) holder.bubble.getLayoutParams();
            blp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.bubble.setMinimumWidth(0);

            if (b.user) {
                holder.bubble.setBackground(rounded(BRAND, AndroidUtilities.dp(16), 0, 0));
                holder.contentText.setTextColor(Color.WHITE);
                holder.reasonHeader.setVisibility(View.GONE);
                holder.reasonText.setVisibility(View.GONE);
                blp.gravity = Gravity.END;
                blp.setMargins(AndroidUtilities.dp(40), AndroidUtilities.dp(3),
                        AndroidUtilities.dp(2), AndroidUtilities.dp(3));
            } else {
                holder.bubble.setBackground(rounded(AI_BUBBLE, AndroidUtilities.dp(16),
                        AndroidUtilities.dp(1), Color.rgb(0xE2, 0xE6, 0xEA)));
                holder.contentText.setTextColor(b.error ? Color.rgb(0xC0, 0x39, 0x2B) : TEXT_DARK);
                blp.gravity = Gravity.START;
                blp.setMargins(AndroidUtilities.dp(2), AndroidUtilities.dp(3),
                        AndroidUtilities.dp(40), AndroidUtilities.dp(3));

                if (!TextUtils.isEmpty(b.reasoning)) {
                    holder.reasonHeader.setVisibility(View.VISIBLE);
                    holder.reasonHeader.setTextColor(REASON_TINT);
                    holder.reasonHeader.setText((b.reasoningExpanded ? "▾ " : "▸ ")
                            + "思考过程（DeepSeek-R1）");
                    holder.reasonHeader.setTag(b);
                    holder.reasonHeader.setOnClickListener(v -> {
                        Bubble t = (Bubble) v.getTag();
                        t.reasoningExpanded = !t.reasoningExpanded;
                        notifyDataSetChanged();
                    });
                    holder.reasonText.setVisibility(b.reasoningExpanded ? View.VISIBLE : View.GONE);
                    holder.reasonText.setText(b.reasoning);
                } else {
                    holder.reasonHeader.setVisibility(View.GONE);
                    holder.reasonText.setVisibility(View.GONE);
                }
            }

            holder.contentText.setMaxWidth(maxW);
            holder.contentText.setText(b.content + (b.streaming ? " ▍" : ""));
            return row;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
