package com.kelokeloo.url2pdf

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var urlEditText: EditText
    private lateinit var openButton: Button
    private lateinit var exportPdfButton: Button
    private lateinit var webView: WebView
    private var isPageReadyForPrint = false
    private var printReadyRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlEditText = findViewById(R.id.urlEditText)
        openButton = findViewById(R.id.openButton)
        exportPdfButton = findViewById(R.id.exportPdfButton)
        webView = findViewById(R.id.webView)

        setupWebView()
        setPrintReady(false)

        openButton.setOnClickListener {
            openUrl()
        }

        urlEditText.setOnEditorActionListener { _, actionId, event ->
            val isGoAction = actionId == EditorInfo.IME_ACTION_GO
            val isEnterUp = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP

            if (isGoAction || isEnterUp) {
                openUrl()
                true
            } else {
                false
            }
        }

        exportPdfButton.setOnClickListener {
            exportCurrentPageToPdf()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                cancelPrintReadyCallback()
                setPrintReady(false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectDesktopViewport(view)
                schedulePrintReady(view, url)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // 伪装成桌面版 Chrome，并配合宽屏视口让网页尽量按电脑布局渲染。
            userAgentString = DESKTOP_CHROME_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true

            builtInZoomControls = true
            displayZoomControls = false

            // 常见坑：部分 HTTPS 页面会引用 HTTP 资源，兼容模式能减少混合内容导致的资源缺失。
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
    }

    private fun openUrl() {
        val input = urlEditText.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "请先输入网址", Toast.LENGTH_SHORT).show()
            return
        }

        val url = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else {
            "https://$input"
        }

        urlEditText.setText(url)
        setPrintReady(false)
        webView.loadUrl(url)
    }

    private fun setPrintReady(isReady: Boolean) {
        isPageReadyForPrint = isReady
        exportPdfButton.isEnabled = isReady
        exportPdfButton.text = if (isReady) {
            "导出为 PDF"
        } else {
            "网页加载完成后可导出"
        }
    }

    private fun schedulePrintReady(view: WebView, url: String) {
        cancelPrintReadyCallback()

        // 等 JS 注入和页面重排完成一点点再允许打印，避免系统预览一直停在 preparing。
        printReadyRunnable = Runnable {
            if (view.url == url) {
                setPrintReady(true)
            }
        }
        view.postDelayed(printReadyRunnable, PRINT_READY_DELAY_MS)
    }

    private fun cancelPrintReadyCallback() {
        printReadyRunnable?.let(webView::removeCallbacks)
        printReadyRunnable = null
    }

    private fun injectDesktopViewport(view: WebView) {
        val script = """
            (function() {
                var viewport = document.querySelector('meta[name="viewport"]');
                if (!viewport) {
                    viewport = document.createElement('meta');
                    viewport.name = 'viewport';
                    document.head.appendChild(viewport);
                }
                viewport.setAttribute('content', 'width=1280, initial-scale=1');
            })();
        """.trimIndent()

        view.evaluateJavascript(script, null)
    }

    private fun exportCurrentPageToPdf() {
        if (webView.url.isNullOrBlank()) {
            Toast.makeText(this, "请先打开一个网页", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isPageReadyForPrint) {
            Toast.makeText(this, "网页还在加载，请稍后再导出", Toast.LENGTH_SHORT).show()
            return
        }

        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val printAdapter = webView.createPrintDocumentAdapter(getPdfDocumentName())

        printManager.print(
            "网页转PDF",
            printAdapter,
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
        )
    }

    private fun getPdfDocumentName(): String {
        val title = webView.title
            ?.trim()
            ?.replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), "_")
            ?.replace(Regex("""\s+"""), " ")
            ?.take(MAX_DOCUMENT_NAME_LENGTH)

        return if (title.isNullOrBlank()) {
            "网页转PDF"
        } else {
            title
        }
    }

    @Deprecated("Android framework deprecated this callback, but it keeps this no-AndroidX sample app lightweight.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        cancelPrintReadyCallback()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PRINT_READY_DELAY_MS = 800L
        private const val MAX_DOCUMENT_NAME_LENGTH = 80
        private const val DESKTOP_CHROME_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }
}
