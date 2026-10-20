package es.dondeloechan.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

/**
 * La app es una sola pantalla: la guía (assets/index.html) dentro de un WebView.
 * Los archivos empaquetados se sirven desde https://appassets.androidplatform.net/assets/,
 * así la página puede descargar el JSON actualizado de GitHub como cualquier web.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int VERDE = Color.parseColor("#1F5E3B");

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(VERDE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Deja libres la barra de estado y la de navegación.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // caché de la guía para usarla sin conexión
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Cualquier enlace externo se abre en el navegador.
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        // "Compartir partido": la página llama a AndroidApp.compartir(texto) y se abre el menú de compartir de Android.
        web.addJavascriptInterface(new Puente(), "AndroidApp");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Al volver a la app, la página comprueba si hay guía nueva.
        web.evaluateJavascript("window.alVolver && window.alVolver()", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Si la ficha de un partido está abierta, el botón atrás la cierra.
        web.evaluateJavascript("window.atras ? window.atras() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    /** Lo que la página puede pedir a Android: compartir un texto y consultar marcadores. */
    private class Puente {
        @JavascriptInterface
        public void compartir(final String texto) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, texto);
                try { startActivity(Intent.createChooser(i, "Compartir partido")); } catch (Exception ignored) { }
            });
        }

        /** Consulta de marcadores (solo a api.football-data.org); responde a window.__resp[id](estado, cuerpo). */
        @JavascriptInterface
        public void pedir(final String id, final String url, final String cabecera, final String valor) {
            new Thread(() -> {
                int estado = 0;
                String cuerpo = "";
                try {
                    URL u = new URL(url);
                    if ("https".equals(u.getProtocol()) && "api.football-data.org".equals(u.getHost())) {
                        HttpURLConnection c = (HttpURLConnection) u.openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        c.setRequestProperty(cabecera, valor);
                        estado = c.getResponseCode();
                        InputStream in = estado < 400 ? c.getInputStream() : c.getErrorStream();
                        if (in != null) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            cuerpo = out.toString("UTF-8");
                        }
                        c.disconnect();
                    }
                } catch (Exception ignored) { }
                final String js = "window.__resp&&window.__resp[" + JSONObject.quote(id) + "]&&window.__resp["
                        + JSONObject.quote(id) + "](" + estado + "," + JSONObject.quote(cuerpo) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }
}
