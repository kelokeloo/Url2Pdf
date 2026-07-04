package com.kelokeloo.url2pdf

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Picture
import android.os.Bundle
import android.graphics.pdf.PdfDocument
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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var urlEditText: EditText
    private lateinit var openButton: Button
    private lateinit var exportPdfButton: Button
    private lateinit var sharePdfButton: Button
    private lateinit var webView: WebView
    private var isPageReadyForPrint = false
    private var isSharingPdf = false
    private var printReadyRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.enableSlowWholeDocumentDraw()
        setContentView(R.layout.activity_main)

        urlEditText = findViewById(R.id.urlEditText)
        openButton = findViewById(R.id.openButton)
        exportPdfButton = findViewById(R.id.exportPdfButton)
        sharePdfButton = findViewById(R.id.sharePdfButton)
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

        sharePdfButton.setOnClickListener {
            shareCurrentPageAsPdf()
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
        exportPdfButton.isEnabled = isReady && !isSharingPdf
        sharePdfButton.isEnabled = isReady && !isSharingPdf

        exportPdfButton.text = "保存"
        sharePdfButton.text = if (isSharingPdf) {
            "正在生成..."
        } else if (!isReady) {
            "等待加载"
        } else {
            "分享 PDF"
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
        if (!ensurePageReady()) {
            return
        }

        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val printAdapter = webView.createPrintDocumentAdapter(getPdfDocumentName())

        printManager.print(
            "网页转PDF",
            printAdapter,
            createPdfPrintAttributes()
        )
    }

    private fun shareCurrentPageAsPdf() {
        if (!ensurePageReady() || isSharingPdf) {
            return
        }

        isSharingPdf = true
        setPrintReady(isPageReadyForPrint)

        webView.post {
            try {
                val pdfFile = createSharedPdfFile(getPdfDocumentName())
                generateCurrentPagePdf(pdfFile)
                openPdfPreview(pdfFile)
            } catch (error: Exception) {
                Toast.makeText(this, "PDF 生成失败：${error.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isSharingPdf = false
                setPrintReady(isPageReadyForPrint)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun generateCurrentPagePdf(pdfFile: File) {
        val pageSource = webView.capturePicture()
        if (pageSource.width <= 0 || pageSource.height <= 0) {
            generateCurrentPagePdfFromWebView(pdfFile)
            return
        }

        generatePdfFromPicture(pdfFile, pageSource)
    }

    private fun generatePdfFromPicture(pdfFile: File, picture: Picture) {
        val pageWidth = PDF_PAGE_WIDTH
        val pageHeight = PDF_PAGE_HEIGHT
        val pictureWidth = max(picture.width, 1)
        val pictureHeight = max(picture.height, 1)
        val scale = pageWidth.toFloat() / pictureWidth
        val pageHeightInWebPixels = pageHeight / scale
        val pageCount = max(1, ceil(pictureHeight / pageHeightInWebPixels).toInt())
        val pdfDocument = PdfDocument()

        try {
            for (pageIndex in 0 until pageCount) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageWidth,
                    pageHeight,
                    pageIndex + 1
                ).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                val yOffset = pageIndex * pageHeightInWebPixels

                canvas.drawColor(Color.WHITE)
                canvas.save()
                canvas.scale(scale, scale)
                canvas.translate(0f, -yOffset)
                picture.draw(canvas)
                canvas.restore()
                pdfDocument.finishPage(page)
            }

            FileOutputStream(pdfFile).use { output ->
                pdfDocument.writeTo(output)
            }
        } finally {
            pdfDocument.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun generateCurrentPagePdfFromWebView(pdfFile: File) {
        val pageWidth = PDF_PAGE_WIDTH
        val pageHeight = PDF_PAGE_HEIGHT
        val webViewWidth = max(webView.width, 1)
        val contentHeight = max((webView.contentHeight * webView.scale).toInt(), webView.height)
        val scale = pageWidth.toFloat() / webViewWidth
        val pageHeightInWebPixels = pageHeight / scale
        val pageCount = max(1, ceil(contentHeight / pageHeightInWebPixels).toInt())
        val pdfDocument = PdfDocument()

        try {
            for (pageIndex in 0 until pageCount) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    pageWidth,
                    pageHeight,
                    pageIndex + 1
                ).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                val yOffset = pageIndex * pageHeightInWebPixels

                canvas.drawColor(Color.WHITE)
                canvas.save()
                canvas.scale(scale, scale)
                canvas.translate(0f, -yOffset)
                webView.draw(canvas)
                canvas.restore()
                pdfDocument.finishPage(page)
            }

            FileOutputStream(pdfFile).use { output ->
                pdfDocument.writeTo(output)
            }
        } finally {
            pdfDocument.close()
        }
    }

    private fun openPdfPreview(pdfFile: File) {
        val previewIntent = Intent(this, PdfPreviewActivity::class.java).apply {
            putExtra(PdfPreviewActivity.EXTRA_PDF_FILE_NAME, pdfFile.name)
        }
        startActivity(previewIntent)
    }

    private fun ensurePageReady(): Boolean {
        if (webView.url.isNullOrBlank()) {
            Toast.makeText(this, "请先打开一个网页", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!isPageReadyForPrint) {
            Toast.makeText(this, "网页还在加载，请稍后再操作", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun createPdfPrintAttributes(): PrintAttributes {
        return PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .build()
    }

    private fun createSharedPdfFile(documentName: String): File {
        val shareDir = File(cacheDir, PDF_SHARE_DIR_NAME).apply {
            mkdirs()
        }
        return File(shareDir, "$documentName.pdf")
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
        private const val PDF_PAGE_WIDTH = 595
        private const val PDF_PAGE_HEIGHT = 842
        private const val PDF_SHARE_AUTHORITY = "com.kelokeloo.url2pdf.fileprovider"
        private const val PDF_SHARE_DIR_NAME = "shared_pdfs"
        private const val DESKTOP_CHROME_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }
}
