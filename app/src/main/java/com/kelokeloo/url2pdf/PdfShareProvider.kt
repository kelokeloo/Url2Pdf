package com.kelokeloo.url2pdf

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class PdfShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/pdf"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveSharedPdf(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        val row = cursor.newRow()

        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
                else -> row.add(null)
            }
        }

        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.startsWith("r")) {
            throw FileNotFoundException("Only read access is supported.")
        }

        return ParcelFileDescriptor.open(
            resolveSharedPdf(uri),
            ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun resolveSharedPdf(uri: Uri): File {
        val context = context ?: throw FileNotFoundException("Provider context is unavailable.")
        val fileName = uri.lastPathSegment ?: throw FileNotFoundException("Missing file name.")
        val shareDir = File(context.cacheDir, PDF_SHARE_DIR_NAME)
        val file = File(shareDir, fileName)

        val canonicalShareDir = shareDir.canonicalFile
        val canonicalFile = file.canonicalFile
        if (!canonicalFile.path.startsWith(canonicalShareDir.path + File.separator) ||
            canonicalFile.extension.lowercase() != "pdf" ||
            !canonicalFile.exists()
        ) {
            throw FileNotFoundException("PDF file is not available.")
        }

        return canonicalFile
    }

    companion object {
        private const val PDF_SHARE_DIR_NAME = "shared_pdfs"
    }
}
