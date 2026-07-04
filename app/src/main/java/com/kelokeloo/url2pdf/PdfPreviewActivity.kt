package com.kelokeloo.url2pdf

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException

class PdfPreviewActivity : Activity() {

    private lateinit var pdfFile: File
    private val renderedBitmaps = mutableListOf<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            pdfFile = resolvePdfFile()
            setContentView(createContentView())
        } catch (error: Exception) {
            Toast.makeText(this, "PDF 预览失败：${error.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun createContentView(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 250))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "预览 PDF，确认后分享"
            textSize = 16f
            setTextColor(Color.rgb(17, 24, 39))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        )

        val pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(this).apply {
            addView(
                pageContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        renderPdfPages(pageContainer)

        val shareButton = Button(this).apply {
            text = "分享 PDF"
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 99, 235))
            setOnClickListener {
                sharePdf()
            }
        }
        root.addView(
            shareButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(12)
            }
        )

        return root
    }

    private fun renderPdfPages(pageContainer: LinearLayout) {
        val descriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            for (pageIndex in 0 until renderer.pageCount) {
                renderer.openPage(pageIndex).use { page ->
                    val targetWidth = resources.displayMetrics.widthPixels - dp(24)
                    val targetHeight = (targetWidth * page.height.toFloat() / page.width).toInt()
                    val bitmap = Bitmap.createBitmap(
                        targetWidth,
                        targetHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    renderedBitmaps += bitmap

                    val imageView = ImageView(this).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        setBackgroundColor(Color.WHITE)
                    }
                    pageContainer.addView(
                        imageView,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            targetHeight
                        ).apply {
                            bottomMargin = dp(12)
                        }
                    )
                }
            }
        }
        descriptor.close()
    }

    private fun sharePdf() {
        val pdfUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(PDF_SHARE_AUTHORITY)
            .appendPath(pdfFile.name)
            .build()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "分享 PDF"))
    }

    private fun resolvePdfFile(): File {
        val fileName = intent.getStringExtra(EXTRA_PDF_FILE_NAME)
            ?: throw FileNotFoundException("缺少 PDF 文件名")
        val shareDir = File(cacheDir, PDF_SHARE_DIR_NAME)
        val file = File(shareDir, fileName)
        val canonicalShareDir = shareDir.canonicalFile
        val canonicalFile = file.canonicalFile

        if (!canonicalFile.path.startsWith(canonicalShareDir.path + File.separator) ||
            canonicalFile.extension.lowercase() != "pdf" ||
            !canonicalFile.exists()
        ) {
            throw FileNotFoundException("PDF 文件不存在")
        }

        return canonicalFile
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        renderedBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        renderedBitmaps.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PDF_FILE_NAME = "pdf_file_name"
        private const val PDF_SHARE_AUTHORITY = "com.kelokeloo.url2pdf.fileprovider"
        private const val PDF_SHARE_DIR_NAME = "shared_pdfs"
    }
}
