package com.lsm.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(67,160,71);
    private static final int BG = Color.rgb(244,247,242);
    private final List<String> queue = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout messages;
    private TextView networkState, queueState, profileState;
    private EditText input, relayInput, secretInput;
    private ConnectivityManager connectivityManager;
    private String profile = "A";

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (isOnline()) syncNow();
            handler.postDelayed(this, 5000);
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { runOnUiThread(() -> { refreshNetwork(); syncNow(); }); }
        @Override public void onLost(Network network) { runOnUiThread(() -> refreshNetwork()); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { runOnUiThread(() -> refreshNetwork()); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        profile = getSharedPreferences("lsm", MODE_PRIVATE).getString("profile", "A");
        restoreQueue();
        buildUi();
        refreshNetwork();
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        handler.post(poller);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poller);
        try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(12),dp(14),dp(10));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("● LSM   Low Signal Messenger");
        title.setTextSize(20); title.setTextColor(Color.rgb(30,80,34));
        root.addView(title);

        LinearLayout setup = new LinearLayout(this); setup.setOrientation(LinearLayout.VERTICAL);
        profileState = new TextView(this); profileState.setTextSize(14); setup.addView(profileState);
        Button toggle = new Button(this); toggle.setText("Сменить профиль A/B");
        toggle.setOnClickListener(v -> { profile = profile.equals("A") ? "B" : "A"; getSharedPreferences("lsm",MODE_PRIVATE).edit().putString("profile",profile).apply(); updateProfile(); syncNow(); });
        setup.addView(toggle);
        relayInput = new EditText(this); relayInput.setHint("https://ваш-relay.example.com"); relayInput.setSingleLine(); relayInput.setText(getSharedPreferences("lsm",MODE_PRIVATE).getString("relay_url","")); setup.addView(relayInput);
        secretInput = new EditText(this); secretInput.setHint("Общий тестовый ключ (8+ символов)"); secretInput.setSingleLine(); secretInput.setText(getSharedPreferences("lsm",MODE_PRIVATE).getString("shared_secret","")); setup.addView(secretInput);
        Button save = new Button(this); save.setText("Сохранить настройки"); save.setOnClickListener(v -> saveSettings()); setup.addView(save);
        root.addView(setup);
        updateProfile();

        LinearLayout diagnostics = new LinearLayout(this); diagnostics.setOrientation(LinearLayout.HORIZONTAL);
        networkState = new TextView(this); networkState.setTextSize(13); diagnostics.addView(networkState,new LinearLayout.LayoutParams(0,dp(36),1));
        queueState = new TextView(this); queueState.setTextSize(13); queueState.setGravity(Gravity.END|Gravity.CENTER_VERTICAL); diagnostics.addView(queueState,new LinearLayout.LayoutParams(0,dp(36),1));
        root.addView(diagnostics);

        ScrollView scroll = new ScrollView(this);
        messages = new LinearLayout(this); messages.setOrientation(LinearLayout.VERTICAL); scroll.addView(messages);
        root.addView(scroll,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1));
        renderHistory();

        LinearLayout composer = new LinearLayout(this); composer.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this); input.setHint("Сообщение…"); input.setMaxLines(4); composer.addView(input,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
        Button send = new Button(this); send.setText("➤"); send.setOnClickListener(v -> sendMessage()); composer.addView(send,new LinearLayout.LayoutParams(dp(64),LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(composer);

        TextView footer = new TextView(this); footer.setText("A ↔ B • relay видит только ciphertext • удаление локальной копии после ACK"); footer.setTextSize(11); footer.setTextColor(Color.GRAY); footer.setGravity(Gravity.CENTER); root.addView(footer);
        setContentView(root);
    }

    private void updateProfile() { if (profileState != null) profileState.setText("Этот телефон: " + profile + "   Собеседник: " + peer()); }
    private String peer() { return profile.equals("A") ? "B" : "A"; }

    private void saveSettings() {
        String url = relayInput.getText().toString().trim().replaceAll("/+$", "");
        String secret = secretInput.getText().toString();
        getSharedPreferences("lsm",MODE_PRIVATE).edit().putString("relay_url",url).putString("shared_secret",secret).apply();
        addSystem("Настройки сохранены");
        syncNow();
    }

    private void sendMessage() {
        String text = input.getText().toString().trim(); if (text.isEmpty()) return;
        String item = UUID.randomUUID()+"|"+peer()+"|"+System.currentTimeMillis()+"|"+text.replace("\n"," ");
        queue.add(item); saveQueue(); addBubble(text,"ожидает ACK"); input.setText(""); refreshNetwork(); syncNow();
    }

    private void syncNow() {
        final String base = getSharedPreferences("lsm",MODE_PRIVATE).getString("relay_url","").replaceAll("/+$", "");
        final String secret = getSharedPreferences("lsm",MODE_PRIVATE).getString("shared_secret","");
        if (base.isEmpty() || secret.length() < 8 || !isOnline()) return;
        io.execute(() -> {
            try {
                for (String item : snapshotQueue()) {
                    String[] p = item.split("\\|",4); if (p.length != 4) continue;
                    JSONObject body = new JSONObject();
                    body.put("id",p[0]); body.put("from",profile); body.put("to",p[1]); body.put("ciphertext",MessageCrypto.encrypt(p[3],secret)); body.put("ttl_seconds",604800);
                    request("POST",base+"/v1/messages",body);
                }
                JSONObject inbox = request("GET",base+"/v1/messages?recipient="+URLEncoder.encode(profile,"UTF-8"),null);
                JSONArray arr = inbox.optJSONArray("messages");
                if (arr != null) for (int i=0;i<arr.length();i++) {
                    JSONObject m = arr.getJSONObject(i); String id=m.getString("id");
                    try {
                        String plain = MessageCrypto.decrypt(m.getString("ciphertext"),secret);
                        runOnUiThread(() -> addIncoming(plain,m.optString("sender","?")));
                        JSONObject ack = new JSONObject(); ack.put("recipient",profile); ack.put("id",id); request("POST",base+"/v1/ack",ack);
                    } catch (Exception ignored) {}
                }
                JSONObject receipts = request("GET",base+"/v1/receipts?sender="+URLEncoder.encode(profile,"UTF-8"),null);
                JSONArray rs = receipts.optJSONArray("receipts");
                if (rs != null) for (int i=0;i<rs.length();i++) {
                    String id=rs.getJSONObject(i).getString("id");
                    removeQueued(id);
                    JSONObject ra=new JSONObject(); ra.put("sender",profile); ra.put("id",id); request("POST",base+"/v1/receipt-ack",ra);
                }
                runOnUiThread(() -> refreshNetwork());
            } catch (Exception e) { runOnUiThread(() -> networkState.setText("○ relay недоступен")); }
        });
    }

    private synchronized List<String> snapshotQueue() { return new ArrayList<>(queue); }
    private synchronized void removeQueued(String id) {
        Iterator<String> it=queue.iterator(); while(it.hasNext()) if(it.next().startsWith(id+"|")) { it.remove(); break; }
        saveQueue();
        runOnUiThread(() -> addSystem("✓ Доставлено: "+id.substring(0,Math.min(8,id.length()))));
    }

    private JSONObject request(String method,String url,JSONObject body) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(4000); c.setReadTimeout(5000); c.setRequestProperty("Accept","application/json");
        if (body!=null) { c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json"); byte[] raw=body.toString().getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(raw.length); try(OutputStream os=c.getOutputStream()){os.write(raw);} }
        int code=c.getResponseCode(); BufferedReader br=new BufferedReader(new InputStreamReader(code>=400?c.getErrorStream():c.getInputStream(),StandardCharsets.UTF_8));
        StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null) sb.append(line); br.close(); c.disconnect();
        if(code>=400) throw new IllegalStateException("HTTP "+code); return new JSONObject(sb.toString());
    }

    private void addIncoming(String text,String from) { addBubble("← "+from+": "+text,"получено и ACK отправлен"); }
    private void addSystem(String text) { TextView v=new TextView(this); v.setText(text); v.setTextColor(Color.GRAY); v.setPadding(dp(6),dp(5),dp(6),dp(5)); messages.addView(v); }
    private void addBubble(String text,String status) { TextView b=new TextView(this); b.setText(text+"\n"+status); b.setTextSize(16); b.setTextColor(Color.rgb(25,55,25)); b.setPadding(dp(12),dp(9),dp(12),dp(9)); b.setBackgroundColor(Color.rgb(224,242,224)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT); p.gravity=Gravity.END; p.setMargins(dp(40),dp(4),0,dp(4)); messages.addView(b,p); }

    private void renderHistory() { addSystem("Для теста поставьте APK на два телефона. На одном выберите A, на другом B. URL relay и общий ключ должны совпадать."); for(String item:queue){String[] p=item.split("\\|",4); if(p.length==4)addBubble(p[3],"ожидает ACK");} }
    private void refreshNetwork() { boolean online=isOnline(); networkState.setText(online?"● сеть доступна":"○ слабая/нет сети"); networkState.setTextColor(online?GREEN:Color.rgb(180,80,40)); queueState.setText("Очередь: "+queue.size()); }
    private boolean isOnline() { Network n=connectivityManager.getActiveNetwork(); if(n==null)return false; NetworkCapabilities c=connectivityManager.getNetworkCapabilities(n); return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET); }

    private synchronized void saveQueue() { try { String encrypted=LocalVault.encrypt(String.join("\n",queue)); getSharedPreferences("lsm",MODE_PRIVATE).edit().putString("outbox_encrypted",encrypted).remove("outbox").apply(); } catch(Exception ignored){} }
    private void restoreQueue() { String encrypted=getSharedPreferences("lsm",MODE_PRIVATE).getString("outbox_encrypted",""); if(encrypted.isEmpty())return; try { String stored=LocalVault.decrypt(encrypted); if(!stored.isEmpty()) for(String line:stored.split("\n")) if(!line.trim().isEmpty()) queue.add(line); } catch(Exception ignored){} }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);} 
}
