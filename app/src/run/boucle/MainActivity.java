package run.boucle;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Coquille native : une WebView qui sert l'interface (assets/www) depuis une
 * origine https virtuelle, plus un pont "Android" pour la position GPS et
 * l'enregistrement / partage des fichiers GPX.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_LOC = 1;

    private WebView web;
    private LocationManager lm;
    private LocationListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setUserAgentString(s.getUserAgentString() + " BoucleApp/1.0");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String p = u.getPath();
                if (p == null || p.equals("/") || p.isEmpty()) p = "/index.html";
                try {
                    InputStream in = getAssets().open("www" + p);
                    return new WebResourceResponse(mime(p), "utf-8", in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        web.loadUrl("https://" + HOST + "/index.html");
    }

    private static String mime(String p) {
        String l = p.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private void js(final String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    // ---------- Position ----------

    private boolean hasLocPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (!hasLocPerm()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        stopLocation();
        try {
            Location best = null;
            for (String prov : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 10 * 60 * 1000) send(best, false);

            listener = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    boolean done = l.hasAccuracy() && l.getAccuracy() <= 25;
                    send(l, done);
                    if (done) stopLocation();
                }
                @Override public void onStatusChanged(String p, int st, Bundle b) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            boolean any = false;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
                any = true;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
                any = true;
            }
            if (!any) {
                js("window.__onLocationError && window.__onLocationError('La localisation du téléphone est désactivée.')");
                return;
            }
            main.postDelayed(this::stopLocation, 30000);
        } catch (SecurityException e) {
            js("window.__onLocationError && window.__onLocationError('Accès à la position refusé.')");
        }
    }

    private void send(Location l, boolean done) {
        js(String.format(Locale.ROOT, "window.__onLocation && window.__onLocation(%.7f,%.7f,%.1f,%b)",
            l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 9999f, done));
    }

    private void stopLocation() {
        if (listener != null) {
            try { lm.removeUpdates(listener); } catch (Exception ignored) { }
            listener = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_LOC) return;
        if (hasLocPerm()) startLocation();
        else js("window.__onLocationError && window.__onLocationError('Accès à la position refusé. Touche la carte pour choisir le départ.')");
    }

    // ---------- GPX ----------

    private void saveAndShare(String name, String content) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".gpx")) safe += ".gpx";
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/gpx+xml");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("insert");
                try (OutputStream os = cr.openOutputStream(uri)) { os.write(data); }
            } else {
                File dir = getExternalFilesDir(null);
                File f = new File(dir, safe);
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(data); }
                uri = null;
                toast("GPX enregistré : " + f.getAbsolutePath());
            }
            if (uri != null) {
                toast("GPX enregistré dans Téléchargements");
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("application/gpx+xml");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, "Envoyer le parcours"));
            }
        } catch (Exception e) {
            toast("Impossible d'enregistrer le GPX : " + e.getMessage());
        }
    }

    private void toast(String m) {
        main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    class Bridge {
        @JavascriptInterface public void requestLocation() { main.post(MainActivity.this::startLocation); }
        @JavascriptInterface public void saveGpx(String name, String content) {
            main.post(() -> saveAndShare(name, content));
        }
        @JavascriptInterface public void toast(String m) { MainActivity.this.toast(m); }
    }

    // ---------- Retour ----------

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.__onBack && window.__onBack()) ? 1 : 0", v -> {
            if (!"1".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        stopLocation();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
