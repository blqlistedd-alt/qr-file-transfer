package com.example.qrfiletransfer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.integration.android.*
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    companion object {
        private const val CAMERA_REQ = 200
        private const val SCAN_REQ = 300
        private const val MAX_BYTES = 5 * 1024 * 1024
        private const val CHUNK_BYTES = 1350
        private const val FRAME_DELAY_MS = 220L
        private const val SCAN_NEXT_DELAY_MS = 140L
        private const val PROTOCOL = "QFT3"
    }

    private lateinit var status: TextView
    private lateinit var fileInfo: TextView
    private lateinit var qr: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var frameInfo: TextView
    private lateinit var send: Button
    private lateinit var receive: Button
    private lateinit var stop: Button

    @Volatile private var sending = false
    private var selectedName = "file"
    private var rx: RxTransfer? = null
    private var scanBusy = false
    private var saveInProgress = false
    private val handler = Handler(Looper.getMainLooper())

    private data class RxTransfer(
        val id: String,
        val fileName: String,
        val size: Int,
        val total: Int,
        val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    )

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readAndSend(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        fileInfo = findViewById(R.id.fileInfo)
        qr = findViewById(R.id.qr)
        progress = findViewById(R.id.progress)
        frameInfo = findViewById(R.id.frameInfo)
        send = findViewById(R.id.send)
        receive = findViewById(R.id.receive)
        stop = findViewById(R.id.stop)

        send.setOnClickListener { filePicker.launch(arrayOf("*/*")) }
        receive.setOnClickListener { startReceiving() }
        stop.setOnClickListener { reset() }
    }

    private fun readAndSend(uri: Uri) {
        try {
            val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > MAX_BYTES) error("File 5 MB se bada hai")
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("File read nahi ho payi")
            if (bytes.isEmpty()) error("Empty file transfer nahi ho sakti")
            if (bytes.size > MAX_BYTES) error("File 5 MB se bada hai")
            selectedName = getDisplayName(uri)?.take(120).orEmpty().ifBlank { "file" }
            startSending(bytes)
        } catch (e: Exception) {
            toast(e.message ?: "File read error")
        }
    }

    private fun getDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun startSending(bytes: ByteArray) {
        reset(true)
        sending = true
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val total = (bytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        send.isEnabled = false
        receive.isEnabled = false
        stop.visibility = View.VISIBLE
        qr.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        fileInfo.text = "$selectedName • ${formatBytes(bytes.size.toLong())}"

        Thread {
            for (index in 0 until total) {
                if (!sending || isFinishing) break
                val chunk = bytes.copyOfRange(index * CHUNK_BYTES, min(bytes.size, (index + 1) * CHUNK_BYTES))
                val encoded = Base64.encodeToString(chunk, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val name64 = Base64.encodeToString(selectedName.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val payload = "$PROTOCOL|$id|$name64|${bytes.size}|$index|$total|${crc32(chunk)}|$encoded"

                try {
                    val bitmap = makeQr(payload)
                    val pct = (index + 1) * 100 / total
                    runOnUiThread {
                        if (sending) {
                            qr.setImageBitmap(bitmap)
                            progress.progress = pct
                            frameInfo.text = "Frame ${index + 1} / $total • $pct%"
                            status.text = "QR ko receiver ke camera ke saamne rakhein"
                        }
                    }
                    Thread.sleep(FRAME_DELAY_MS)
                } catch (e: Exception) {
                    runOnUiThread { toast("QR generate error: ${e.message}") }
                    break
                }
            }
            runOnUiThread {
                if (sending) {
                    status.text = "Frames complete. Receiver verify kar raha hai."
                    frameInfo.text = "Finished"
                }
            }
        }.start()
    }

    private fun makeQr(text: String): Bitmap {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 2
        )
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 900, 900, hints)
        val bitmap = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        for (x in 0 until 900) {
            for (y in 0 until 900) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }

    private fun startReceiving() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
        } else {
            beginScanner()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQ && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            beginScanner()
        } else if (requestCode == CAMERA_REQ) {
            toast("Camera permission required hai")
        }
    }

    private fun beginScanner() {
        if (scanBusy || isTransferComplete()) return
        scanBusy = true
        send.isEnabled = false
        receive.isEnabled = false
        stop.visibility = View.VISIBLE
        status.text = "QR frame scan karein…"

        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Animated QR ko camera ke saamne rakhein")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setTimeout(0L)
            .setRequestCode(SCAN_REQ)
            .initiateScan()
    }

    @Deprecated("Use Activity Result APIs for new integrations")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCAN_REQ) return

        scanBusy = false
        IntentIntegrator.parseActivityResult(SCAN_REQ, resultCode, data)?.contents?.takeIf { it.isNotEmpty() }?.let(::processFrame)
        if (!isTransferComplete() && resultCode != Activity.RESULT_CANCELED) {
            handler.postDelayed({ beginScanner() }, SCAN_NEXT_DELAY_MS)
        }
    }

    private fun processFrame(raw: String) {
        try {
            val parts = raw.split('|', limit = 8)
            if (parts.size != 8 || parts[0] != PROTOCOL) return

            val id = parts[1]
            val name = String(Base64.decode(parts[2], Base64.URL_SAFE), StandardCharsets.UTF_8)
            val size = parts[3].toInt()
            val index = parts[4].toInt()
            val total = parts[5].toInt()
            val expected = parts[6].toLong()
            val chunk = Base64.decode(parts[7], Base64.URL_SAFE)

            if (size !in 1..MAX_BYTES || total <= 0 || index !in 0 until total || chunk.isEmpty()) return
            if (crc32(chunk) != expected) return

            if (rx == null || rx?.id != id) rx = RxTransfer(id, name, size, total)
            val transfer = rx ?: return
            if (transfer.size != size || transfer.total != total) return

            transfer.chunks.putIfAbsent(index, chunk)
            val count = transfer.chunks.size
            val pct = count * 100 / transfer.total

            status.text = "Receiving: ${transfer.fileName}"
            fileInfo.text = "${formatBytes(size.toLong())} • $count / $total frames"
            progress.visibility = View.VISIBLE
            progress.progress = pct
            frameInfo.text = "$pct% received"

            if (count == total) saveReceivedFile(transfer)
        } catch (_: Exception) {
            // ignore malformed/corrupt frames
        }
    }

    private fun isTransferComplete() = rx?.let { it.chunks.size == it.total } == true

    private fun saveReceivedFile(t: RxTransfer) {
        if (saveInProgress) return
        saveInProgress = true
        try {
            val safeName = t.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "received_file" }
            val output = File(getExternalFilesDir(null), safeName)
            FileOutputStream(output).use { out ->
                for (i in 0 until t.total) out.write(t.chunks[i] ?: error("Missing frame"))
            }
            status.text = "✅ File received successfully"
            fileInfo.text = "Saved: ${output.absolutePath} • ${formatBytes(output.length())}"
            frameInfo.text = "100% complete"
            progress.progress = 100
            receive.isEnabled = true
            send.isEnabled = true
            toast("File receive ho gayi")
        } catch (e: Exception) {
            saveInProgress = false
            toast("Save error: ${e.message}")
        }
    }

    private fun reset(keepStatus: Boolean = false) {
        sending = false
        handler.removeCallbacksAndMessages(null)
        rx = null
        scanBusy = false
        saveInProgress = false
        qr.setImageDrawable(null)
        qr.visibility = View.GONE
        progress.visibility = View.GONE
        stop.visibility = View.GONE
        send.isEnabled = true
        receive.isEnabled = true
        if (!keepStatus) {
            status.text = "Select Send or Receive"
            fileInfo.text = ""
            frameInfo.text = ""
        }
    }

    override fun onDestroy() {
        reset()
        super.onDestroy()
    }

    private fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
