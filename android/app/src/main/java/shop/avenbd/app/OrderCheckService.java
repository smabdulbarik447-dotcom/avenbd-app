package shop.avenbd.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service that periodically opens the admin panel in a hidden
 * WebView (reusing the user's logged-in session cookies), reads how many
 * orders are pending and what the newest order id is, and raises a
 * notification for new orders / pending orders. No backend changes required.
 */
public class OrderCheckService extends Service {

    public static final String CH_RUNNING = "running";
    public static final String CH_ORDERS = "orders";

    private static final int FG_ID = 1001;
    private static final int NOTE_NEW = 2001;
    private static final int NOTE_PENDING = 2002;

    private static final long FIRST_DELAY = 15 * 1000L;       // 15s after start
    private static final long INTERVAL = 10 * 60 * 1000L;     // every 10 minutes
    private static final String ADMIN_URL = "https://www.avenbd.shop/admin";
    private static final String PREFS = "order_check";
    private static final String KEY_LAST_ID = "last_order_id";

    private Handler handler;
    private Runnable ticker;
    private boolean checking = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        handler = new Handler(Looper.getMainLooper());
        startForeground(FG_ID, buildRunningNotification());

        ticker = new Runnable() {
            @Override
            public void run() {
                try { checkOrders(); } catch (Exception ignored) {}
                handler.postDelayed(this, INTERVAL);
            }
        };
        handler.postDelayed(ticker, FIRST_DELAY);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // restart if the system kills it
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- channels

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel running = new NotificationChannel(
                    CH_RUNNING, "অ্যাপ চালু", NotificationManager.IMPORTANCE_MIN);
            running.setShowBadge(false);
            nm.createNotificationChannel(running);

            NotificationChannel orders = new NotificationChannel(
                    CH_ORDERS, "অর্ডার অ্যালার্ট", NotificationManager.IMPORTANCE_HIGH);
            orders.enableVibration(true);
            orders.setShowBadge(true);
            nm.createNotificationChannel(orders);
        }
    }

    private Notification buildRunningNotification() {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH_RUNNING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Aven BD Admin")
                .setContentText("অর্ডার মনিটরিং চালু আছে")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }

    private void alert(int id, String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(
                this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CH_ORDERS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, n);
    }

    // ------------------------------------------------------------ order checking

    private void checkOrders() {
        if (checking) return;
        checking = true;

        final WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pollDom(view, 0);
            }
        });
        wv.loadUrl(ADMIN_URL);
    }

    /** Poll the rendered page until the order data shows up (SPA loads async). */
    private void pollDom(final WebView view, final int attempt) {
        if (attempt > 12) { // ~24s max
            destroy(view);
            return;
        }
        // Normalise Bengali digits to ASCII, then read the pending count and the
        // newest order id straight from the visible text.
        String js =
            "(function(){try{" +
            "var t=document.body?document.body.innerText:'';" +
            "t=t.replace(/[০-৯]/g,function(d){return '০১২৩৪৫৬৭৮৯'.indexOf(d);});" +
            "var pend='';var m=t.match(/পেন্ডিং[^0-9]{0,12}(\\d+)/);if(m)pend=m[1];" +
            "var ids=t.match(/AV\\d{3,}/g)||[];" +
            "var newest=ids.length?ids[0]:'';" +
            "return pend+'|'+newest;" +
            "}catch(e){return '|';}})();";

        view.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String s = value == null ? "" : value;
                // strip the surrounding quotes evaluateJavascript adds
                if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                    s = s.substring(1, s.length() - 1);
                }
                s = s.replace("\\\"", "\"");
                String[] parts = s.split("\\|", -1);
                String pend = parts.length > 0 ? parts[0] : "";
                String newest = parts.length > 1 ? parts[1] : "";

                if (newest == null || newest.isEmpty()) {
                    handler.postDelayed(new Runnable() {
                        @Override public void run() { pollDom(view, attempt + 1); }
                    }, 2000);
                } else {
                    handleResult(pend, newest);
                    destroy(view);
                }
            }
        });
    }

    private void handleResult(String pend, String newest) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        // New-order detection: compare newest order id to the last one we saw.
        long newestNum = digits(newest);
        long lastNum = digits(sp.getString(KEY_LAST_ID, ""));
        if (newestNum > 0 && newestNum > lastNum) {
            if (lastNum > 0) { // don't fire on the very first run
                alert(NOTE_NEW, "🛒 নতুন অর্ডার এসেছে!",
                        "নতুন অর্ডার: " + newest + " — অ্যাপ খুলে দেখুন");
            }
            sp.edit().putString(KEY_LAST_ID, newest).apply();
        }

        // Pending reminder: fire every cycle while pending > 0.
        long pendNum = digits(pend);
        if (pendNum > 0) {
            alert(NOTE_PENDING, "⏳ অর্ডার পেন্ডিং",
                    pendNum + " টি অর্ডার পেন্ডিং আছে — দেখে নিন");
        } else {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTE_PENDING);
        }
    }

    private long digits(String s) {
        if (s == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        if (sb.length() == 0) return 0;
        try { return Long.parseLong(sb.toString()); } catch (Exception e) { return 0; }
    }

    private void destroy(WebView view) {
        try {
            view.stopLoading();
            view.setWebViewClient(new WebViewClient());
            view.destroy();
        } catch (Exception ignored) {}
        checking = false;
    }
}
