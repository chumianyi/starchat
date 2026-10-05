/*
 * YueWu - dpsk account session store.
 * Persists the session cookie + account across launches and keeps
 * DpskClient in sync. A logged-out install has no cookie and must
 * show the account login screen.
 */
package org.telegram.messenger.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public class DpskSession {

    private static final String PREFS = "dpsk";
    private static final String K_COOKIE = "cookie";
    private static final String K_USER = "user";
    private static final String K_PASS = "pass";

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Loads the persisted cookie (if any) into DpskClient. Call once at startup. */
    public static synchronized void restore(Context c) {
        String ck = prefs(c).getString(K_COOKIE, null);
        if (!TextUtils.isEmpty(ck)) {
            DpskClient.setCookie(ck);
        }
    }

    public static synchronized boolean isLoggedIn(Context c) {
        return !TextUtils.isEmpty(prefs(c).getString(K_COOKIE, null));
    }

    public static synchronized String getUser(Context c) {
        return prefs(c).getString(K_USER, "");
    }

    public static synchronized String getPassword(Context c) {
        return prefs(c).getString(K_PASS, "");
    }

    /** Persists the active cookie and account after a successful login/register. */
    public static synchronized void save(Context c, String username, String password) {
        String ck = DpskClient.getCookie();
        prefs(c).edit()
                .putString(K_COOKIE, ck == null ? "" : ck)
                .putString(K_USER, username == null ? "" : username)
                .putString(K_PASS, password == null ? "" : password)
                .apply();
    }

    /** Drops the session; the next launch must show the login screen. */
    public static synchronized void clear(Context c) {
        DpskClient.setCookie(null);
        prefs(c).edit().clear().apply();
    }
}
