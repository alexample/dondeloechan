package es.dondeloechan.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
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
            Insets teclado = insets.getInsets(WindowInsetsCompat.Type.ime());
            // Con el teclado abierto, la web se encoge para que la caja de texto quede a la vista.
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, teclado.bottom));
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

        AvisosWorker.crearCanal(this);
        AvisosWorker.programar(this);

        String partido = getIntent() != null ? getIntent().getStringExtra("partido") : null;
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(partido != null ? START + "#p-" + partido : START);
    }

    /** Al tocar una notificación con la app ya abierta: ir a ese partido. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String partido = intent.getStringExtra("partido");
        if (partido != null) {
            web.evaluateJavascript("window.abrirPartido && window.abrirPartido(" + JSONObject.quote(partido) + ")", null);
        }
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
        AvisosWorker.revisarYa(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Si la ficha de un partido está abierta, el botón atrás la cierra.
        web.evaluateJavascript("window.atras ? window.atras() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    /** Lo que la página puede pedir a Android: compartir, consultar marcadores y los avisos de canal. */
    private class Puente {
        /** Lista de equipos favoritos (ids) para los avisos de canal confirmado. */
        @JavascriptInterface
        public void guardarFavs(String json) {
            getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE).edit().putString("favs", json).apply();
            AvisosWorker.revisarYa(MainActivity.this);
        }

        /** Ajustes del menú: {"canal":bool,"cambio":bool,"antes":bool,"min":int}. */
        @JavascriptInterface
        public void guardarAjustes(String json) {
            String antes = getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE).getString("ajustes", "");
            if (json.equals(antes)) return;
            getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE).edit().putString("ajustes", json).apply();
            try { if (!new JSONObject(json).optBoolean("antes", true)) AvisosWorker.cancelarTodos(MainActivity.this); } catch (Exception ignored) { }
            AvisosWorker.revisarYa(MainActivity.this);
        }

        /** Catálogo de canales (nombre y dial) para escribir el aviso. */
        @JavascriptInterface
        public void guardarCanales(String json) {
            getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE).edit().putString("canales", json).apply();
        }

        /** Pide permiso de notificaciones (Android 13 o superior). */
        @JavascriptInterface
        public void permisoAvisos() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7));
            }
        }

        @JavascriptInterface
        public boolean avisosActivos() {
            return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }

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
