/*
 * YueWu - dpsk account login / register.
 * This is the app's launcher screen. There is no phone-number step:
 * users sign in with a dpsk account + password and go straight into
 * the chat. Register mode creates the account and logs in.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ai.DpskClient;
import org.telegram.messenger.ai.DpskSession;
import org.telegram.ui.StarChatAIActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DpskLoginActivity extends Activity {

    // Gradient sampled from the official YueWu launcher icon:
    // deep indigo (top) -> indigo -> blue (bottom).
    private static final int C_TOP = Color.rgb(0x0F, 0x18, 0x41);
    private static final int C_MID = Color.rgb(0x3A, 0x4F, 0xA8);
    private static final int C_BOTTOM = Color.rgb(0x3A, 0x97, 0xC2);
    private static final int ACCENT = Color.rgb(0x4B, 0x5F, 0xC0);
    private static final int BG = Color.rgb(0xF4, 0xF6, 0xFA);
    private static final int FIELD_BG = Color.rgb(0xFF, 0xFF, 0xFF);
    private static final int TEXT = Color.rgb(0x16, 0x1D, 0x33);
    private static final int HINT = Color.rgb(0x8A, 0x92, 0xA6);
    private static final int ERR = Color.rgb(0xC0, 0x39, 0x2B);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private EditText userField;
    private EditText passField;
    private TextView actionButton;
    private TextView toggleButton;
    private TextView errorText;
    private ProgressBar progress;
    private boolean registerMode;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DpskSession.restore(this);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            window.setStatusBarColor(C_TOP);
        }

        // Already signed in -> straight into the chat, no login UI.
        if (DpskSession.isLoggedIn(this)) {
            openChat(false);
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(buildForm(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_TOP, C_MID, C_BOTTOM});
        header.setBackground(grad);
        int pad = AndroidUtilities.dp(28);
        header.setPadding(pad, AndroidUtilities.dp(46), pad, AndroidUtilities.dp(34));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        int logoSize = AndroidUtilities.dp(84);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(logo, llp);

        TextView name = new TextView(this);
        name.setText("YueWu");
        name.setTextColor(Color.WHITE);
        name.setTextSize(26);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = AndroidUtilities.dp(14);
        header.addView(name, nlp);

        TextView sub = new TextView(this);
        sub.setText("安全 · 极速 · AI 智能聊天");
        sub.setTextColor(Color.argb(220, 255, 255, 255));
        sub.setTextSize(13);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = AndroidUtilities.dp(6);
        header.addView(sub, slp);
        return header;
    }

    private View buildForm() {
        FrameLayout container = new FrameLayout(this);
        container.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(26),
                AndroidUtilities.dp(22), AndroidUtilities.dp(20));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        userField = makeField("账号", false);
        passField = makeField("密码", true);
        form.addView(fieldBox(userField));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = AndroidUtilities.dp(14);
        form.addView(fieldBox(passField), plp);

        errorText = new TextView(this);
        errorText.setTextColor(ERR);
        errorText.setTextSize(13);
        errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = AndroidUtilities.dp(10);
        form.addView(errorText, elp);

        actionButton = new TextView(this);
        actionButton.setText("登 录");
        actionButton.setTextColor(Color.WHITE);
        actionButton.setTextSize(16);
        actionButton.setTypeface(Typeface.DEFAULT_BOLD);
        actionButton.setGravity(Gravity.CENTER);
        GradientDrawable btn = rounded(ACCENT, AndroidUtilities.dp(24));
        actionButton.setBackground(btn);
        actionButton.setPadding(0, AndroidUtilities.dp(13), 0, AndroidUtilities.dp(13));
        actionButton.setOnClickListener(v -> submit());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = AndroidUtilities.dp(22);
        form.addView(actionButton, blp);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(28), AndroidUtilities.dp(28));
        prlp.gravity = Gravity.CENTER_HORIZONTAL;
        prlp.topMargin = AndroidUtilities.dp(14);
        form.addView(progress, prlp);

        toggleButton = new TextView(this);
        toggleButton.setText("还没有账号？点此注册");
        toggleButton.setTextColor(ACCENT);
        toggleButton.setTextSize(14);
        toggleButton.setGravity(Gravity.CENTER);
        toggleButton.setPadding(0, AndroidUtilities.dp(20), 0, 0);
        toggleButton.setOnClickListener(v -> toggleMode());
        form.addView(toggleButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView serverNote = new TextView(this);
        serverNote.setText("登录即连接 YueWu 服务器，账号数据仅保存在本机");
        serverNote.setTextColor(HINT);
        serverNote.setTextSize(12);
        serverNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = AndroidUtilities.dp(26);
        form.addView(serverNote, nlp);

        container.addView(form, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        return container;
    }

    private EditText makeField(String hint, boolean password) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setTextColor(TEXT);
        f.setHintTextColor(HINT);
        f.setTextSize(15);
        f.setSingleLine(true);
        f.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12),
                AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        if (password) {
            f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        }
        return f;
    }

    private View fieldBox(EditText f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(FIELD_BG, AndroidUtilities.dp(12));
        bg.setStroke(AndroidUtilities.dp(1), Color.rgb(0xE2, 0xE6, 0xEE));
        box.setBackground(bg);
        box.addView(f, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void toggleMode() {
        registerMode = !registerMode;
        if (registerMode) {
            actionButton.setText("注 册");
            toggleButton.setText("已有账号？点此登录");
            userField.setHint("设置账号（3-32 位）");
            passField.setHint("设置密码（至少 6 位）");
        } else {
            actionButton.setText("登 录");
            toggleButton.setText("还没有账号？点此注册");
            userField.setHint("账号");
            passField.setHint("密码");
        }
        hideError();
    }

    private void submit() {
        if (busy) {
            return;
        }
        final String user = userField.getText().toString().trim();
        final String pass = passField.getText().toString();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            showError("请输入账号和密码");
            return;
        }
        if (registerMode) {
            if (user.length() < 3 || user.length() > 32) {
                showError("账号长度需 3-32 位");
                return;
            }
            if (pass.length() < 6) {
                showError("密码至少 6 位");
                return;
            }
        }
        setBusy(true);
        hideError();
        pool.execute(() -> {
            try {
                if (registerMode) {
                    DpskClient.registerBlocking(user, pass);
                } else {
                    DpskClient.loginBlocking(user, pass);
                }
                DpskSession.save(DpskLoginActivity.this, user, pass);
                ui.post(() -> {
                    setBusy(false);
                    Toast.makeText(DpskLoginActivity.this,
                            registerMode ? "注册成功，正在进入…" : "登录成功，正在进入…",
                            Toast.LENGTH_SHORT).show();
                    openChat(true);
                    finish();
                });
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> {
                    setBusy(false);
                    showError(friendly(m));
                });
            }
        });
    }

    private String friendly(String m) {
        if (m == null) {
            return "网络错误，请检查网络后重试";
        }
        if (m.contains("401")) {
            return "账号或密码错误";
        }
        if (m.contains("已被注册")) {
            return "该账号已被注册，请直接登录";
        }
        if (m.contains("429")) {
            return "尝试过于频繁，请稍后再试";
        }
        if (m.contains("ECONNREFUSED") || m.contains("failed to connect")
                || m.contains("Unable to resolve") || m.contains("timeout")) {
            return "无法连接 YueWu 服务器，请检查网络";
        }
        // Server may embed a JSON detail like "register HTTP 400: {"detail":"..."}"
        int i = m.indexOf("\"detail\"");
        if (i >= 0) {
            int s = m.indexOf('"', i + 9);
            int e = m.indexOf('"', s + 1);
            if (s >= 0 && e > s) {
                return m.substring(s + 1, e);
            }
        }
        return m;
    }

    private void openChat(boolean animate) {
        Intent intent = new Intent(this, StarChatAIActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        if (!animate) {
            overridePendingTransition(0, 0);
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        actionButton.setEnabled(!b);
        actionButton.setAlpha(b ? 0.6f : 1f);
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        userField.setEnabled(!b);
        passField.setEnabled(!b);
    }

    private void showError(String m) {
        errorText.setText(m);
        errorText.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorText.setVisibility(View.GONE);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
