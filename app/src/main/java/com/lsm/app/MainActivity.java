package com.lsm.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(67, 160, 71);
    private static final int BG = Color.rgb(244, 247, 242);
    private final List<String> queue = new ArrayList<>();
    private LinearLayout messages;
    private TextView networkState;
    private TextView queueState;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreQueue();
        buildUi();
        refreshNetwork();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("● LSM   Low Signal Messenger");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(30, 80, 34));
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.HORIZONTAL);
        diagnostics.setGravity(Gravity.CENTER_VERTICAL);

        networkState = new TextView(this);
        networkState.setTextSize(13);
        diagnostics.addView(networkState, new LinearLayout.LayoutParams(0, dp(36), 1));

        queueState = new TextView(this);
        queueState.setTextSize(13);
        queueState.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        diagnostics.addView(queueState, new LinearLayout.LayoutParams(0, dp(36), 1));
        root.addView(diagnostics);

        TextView contact = new TextView(this);
        contact.setText("●  Тестовый контакт\n     защищённый чат");
        contact.setTextSize(16);
        contact.setTextColor(Color.DKGRAY);
        contact.setPadding(dp(12), dp(10), dp(12), dp(10));
        contact.setBackgroundColor(Color.WHITE);
        root.addView(contact);

        ScrollView scroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, dp(10), 0, dp(10));
        scroll.addView(messages);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(scroll, scrollParams);

        renderHistory();

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(this);
        input.setHint("Сообщение…");
        input.setSingleLine(false);
        input.setMaxLines(4);
        composer.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button send = new Button(this);
        send.setText("➤");
        send.setTextSize(18);
        send.setOnClickListener(v -> sendMessage());
        composer.addView(send, new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(composer);

        TextView footer = new TextView(this);
        footer.setText("Текст отправляется первым • очередь переживает обрыв связи");
        footer.setTextSize(11);
        footer.setTextColor(Color.GRAY);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(6), 0, 0);
        root.addView(footer);

        setContentView(root);
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        String id = UUID.randomUUID().toString();
        String item = id + "|" + System.currentTimeMillis() + "|" + text.replace("\n", " ");
        queue.add(item);
        saveQueue();
        addBubble(text, isOnline() ? "в очереди → доставка" : "нет сети → сохранено локально");
        input.setText("");
        if (isOnline()) flushQueue();
        refreshNetwork();
    }

    private void flushQueue() {
        if (!isOnline() || queue.isEmpty()) return;
        // MVP: transport relay is plugged in here. For now messages are acknowledged locally
        // so the APK can exercise queue persistence and weak-network behavior without a server.
        queue.clear();
        saveQueue();
        queueState.setText("Очередь: 0");
    }

    private void addBubble(String text, String status) {
        TextView bubble = new TextView(this);
        bubble.setText(text + "\n" + status);
        bubble.setTextSize(16);
        bubble.setTextColor(Color.rgb(25, 55, 25));
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackgroundColor(Color.rgb(224, 242, 224));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.END;
        p.setMargins(dp(40), dp(4), 0, dp(4));
        messages.addView(bubble, p);
    }

    private void renderHistory() {
        TextView hint = new TextView(this);
        hint.setText("LSM готов. Отключите интернет и отправьте сообщение — оно останется в локальной очереди.");
        hint.setTextColor(Color.GRAY);
        hint.setPadding(dp(8), dp(8), dp(8), dp(8));
        messages.addView(hint);
        for (String item : queue) {
            String[] parts = item.split("\\|", 3);
            if (parts.length == 3) addBubble(parts[2], "ожидает сети");
        }
    }

    private void refreshNetwork() {
        boolean online = isOnline();
        networkState.setText(online ? "● сеть доступна" : "○ слабая/нет сети");
        networkState.setTextColor(online ? GREEN : Color.rgb(180, 80, 40));
        queueState.setText("Очередь: " + queue.size());
        if (online) flushQueue();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void saveQueue() {
        String joined = String.join("\n", queue);
        getSharedPreferences("lsm", MODE_PRIVATE).edit().putString("outbox", joined).apply();
    }

    private void restoreQueue() {
        String stored = getSharedPreferences("lsm", MODE_PRIVATE).getString("outbox", "");
        if (!stored.isEmpty()) {
            for (String line : stored.split("\n")) if (!line.isBlank()) queue.add(line);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
