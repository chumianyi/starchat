/*
 * Yuewu - built-in AI assistant network client.
 * Talks to the dpsk DeepSeek-R1 proxy over plain HTTP.
 */
package org.telegram.messenger.ai;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

public class DpskClient {

    public static final String BASE = "http://103.236.99.177:24512/api/dpsk";

    public static final class Msg {
        public final String role;
        public final String content;

        public Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public interface ChatCallback {
        void onReasoning(String delta);

        void onContent(String delta);

        void onDone();

        /** message may start with "AUTH:" when the session is unauthorized. */
        void onError(String message);
    }

    private static String cookie;

    public static synchronized String getCookie() {
        return cookie;
    }

    public static synchronized void setCookie(String c) {
        cookie = c;
    }

    /** Performs POST /login and stores the session cookie. */
    public static void loginBlocking(String user, String pass) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", user);
        body.put("password", pass);

        HttpURLConnection conn = open(BASE + "/login", "POST", false, null);
        try {
            writeJson(conn, body);
            int code = conn.getResponseCode();
            String setc = combineCookies(conn);
            String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code >= 400) {
                throw new Exception("login HTTP " + code + ": " + safe(resp));
            }
            if (!TextUtils.isEmpty(setc)) {
                setCookie(setc);
            } else {
                try {
                    JSONObject j = new JSONObject(resp);
                    String t = j.optString("token", j.optString("session", null));
                    if (!TextUtils.isEmpty(t)) {
                        setCookie("session=" + t);
                    }
                } catch (Throwable ignore) {
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Performs GET /register?username=&password= and stores the session cookie. */
    public static void registerBlocking(String user, String pass) throws Exception {
        String q = "username=" + URLEncoder.encode(user, "UTF-8")
                + "&password=" + URLEncoder.encode(pass, "UTF-8");
        HttpURLConnection conn = open(BASE + "/register?" + q, "GET", false, null);
        try {
            int code = conn.getResponseCode();
            String setc = combineCookies(conn);
            String resp = readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code >= 400) {
                throw new Exception("register HTTP " + code + ": " + safe(resp));
            }
            if (!TextUtils.isEmpty(setc)) {
                setCookie(setc);
            }
        } finally {
            conn.disconnect();
        }
    }

    public static void chatStreamBlocking(List<Msg> messages, ChatCallback cb) {
        try {
            if (TextUtils.isEmpty(getCookie())) {
                cb.onError("AUTH:no session");
                return;
            }

            JSONObject body = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Msg m : messages) {
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                o.put("content", m.content);
                arr.put(o);
            }
            body.put("messages", arr);
            body.put("stream", true);
            body.put("mode", "thinking");

            HttpURLConnection conn = open(BASE + "/chat", "POST", true, getCookie());
            try {
                writeJson(conn, body);
                int code = conn.getResponseCode();
                if (code == 401 || code == 403) {
                    cb.onError("AUTH:" + code);
                    return;
                }
                if (code >= 400) {
                    cb.onError("chat HTTP " + code + ": " + safe(readAll(conn.getErrorStream())));
                    return;
                }
                parseStream(conn.getInputStream(), cb);
            } finally {
                conn.disconnect();
            }
            cb.onDone();
        } catch (Exception e) {
            cb.onError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void parseStream(InputStream is, ChatCallback cb) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith(":")) {
                continue;
            }
            if (t.startsWith("event:") || t.startsWith("id:")) {
                continue;
            }
            String payload = t;
            if (t.startsWith("data:")) {
                payload = t.substring(5).trim();
            }
            if (payload.equals("[DONE]")) {
                break;
            }
            if (!(payload.startsWith("{") || payload.startsWith("["))) {
                cb.onContent(payload);
                continue;
            }
            try {
                JSONObject j = new JSONObject(payload);
                JSONObject delta = extractDelta(j);
                if (delta != null) {
                    String reasoning = delta.optString("reasoning_content", null);
                    if (reasoning == null) {
                        reasoning = delta.optString("reasoning", null);
                    }
                    if (reasoning != null) {
                        cb.onReasoning(reasoning);
                    }
                    String content = delta.optString("content", null);
                    if (content != null) {
                        cb.onContent(content);
                    }
                } else {
                    String c = j.optString("content", null);
                    if (c != null) {
                        cb.onContent(c);
                    }
                }
            } catch (Throwable parseErr) {
                cb.onContent(payload);
            }
        }
    }

    private static JSONObject extractDelta(JSONObject j) {
        JSONArray choices = j.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 != null) {
                JSONObject d = c0.optJSONObject("delta");
                if (d != null) {
                    return d;
                }
                JSONObject m = c0.optJSONObject("message");
                if (m != null) {
                    return m;
                }
            }
        }
        return j.optJSONObject("delta");
    }

    private static HttpURLConnection open(String url, String method, boolean acceptStream, String ck) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(300000);
        conn.setDoInput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", acceptStream ? "text/event-stream" : "application/json");
        if (!TextUtils.isEmpty(ck)) {
            conn.setRequestProperty("Cookie", ck);
        }
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static void writeJson(HttpURLConnection conn, JSONObject body) throws Exception {
        conn.setDoOutput(true);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.flush();
        os.close();
    }

    private static String combineCookies(HttpURLConnection conn) {
        Map<String, List<String>> h = conn.getHeaderFields();
        if (h == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                for (String v : e.getValue()) {
                    String part = v.split(";", 2)[0];
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(part);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String readAll(InputStream is) {
        if (is == null) {
            return "";
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
