package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelLink
import io.github.p1neapplexpress.openflux.util.QrGenerator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Exports a Tunnel as a scannable QR code, in the same kotlinx.serialization JSON
 * shape that TunnelsFragment's QR *import* (ScanQRCode -> Tunnel) already expects,
 * so export/import round-trip without a separate link/URI scheme to maintain.
 */
object QrShareDialog {

    private val json = Json { ignoreUnknownKeys = true }

    fun show(context: Context, tunnel: Tunnel) {
        val payload = json.encodeToString(tunnel)
        val bitmap = runCatching { QrGenerator.generate(payload) }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(context, R.string.qr_generate_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_qr_share, null)
        view.findViewById<ImageView>(R.id.qrImage).setImageBitmap(bitmap)

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .create()

        view.findViewById<android.view.View>(R.id.btnCopy).setOnClickListener {
            // The link (not the raw JSON) - it's what's actually useful pasted into a
            // chat app: tapping it on another OpenFlux install opens straight to import.
            val link = TunnelLink.encode(tunnel)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(tunnel.name, link))
            Toast.makeText(context, R.string.qr_share_copied, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<android.view.View>(R.id.btnSend).setOnClickListener {
            runCatching { shareBitmap(context, bitmap, tunnel.name) }
                .onFailure { Toast.makeText(context, R.string.qr_generate_failed, Toast.LENGTH_SHORT).show() }
        }

        dialog.show()
    }

    private fun shareBitmap(context: Context, bitmap: Bitmap, tunnelName: String) {
        val dir = File(context.cacheDir, "qr_share").apply { mkdirs() }
        val file = File(dir, "openflux-${tunnelName.filter { it.isLetterOrDigit() }}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, tunnelName))
    }
}
