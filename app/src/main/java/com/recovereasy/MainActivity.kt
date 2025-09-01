package com.recovereasy

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.recovereasy.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Math
import kotlin.math.log10

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var srcTreeUri: Uri? = null
    private val found = mutableListOf<FoundFile>()
    private lateinit var adapter: FilesListAdapter

    private enum class PendingAction { NONE, COPY, FIX }
    private var pendingAction: PendingAction = PendingAction.NONE
    private var pendingFiles: List<FoundFile> = emptyList()

    // Picker สำหรับเลือก OTG
    private val srcPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            srcTreeUri = uri
            prefs.edit().putString("treeUri", uri.toString()).apply()
            toast("เลือกโฟลเดอร์ต้นทางเรียบร้อย")
            updateSummary()
        }
    }

    // Picker สำหรับปลายทาง (คัดลอก/ซ่อม)
    private val destPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingAction = PendingAction.NONE
            return@registerForActivityResult
        }
        val dest = DocumentFile.fromTreeUri(this, uri) ?: run {
            toast("ไม่สามารถเปิดโฟลเดอร์ปลายทางได้")
            pendingAction = PendingAction.NONE
            return@registerForActivityResult
        }
        when (pendingAction) {
            PendingAction.COPY -> doCopySafely(dest, pendingFiles)
            PendingAction.FIX  -> doFixVideos(dest, pendingFiles)
            else -> {}
        }
        pendingAction = PendingAction.NONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("recover", MODE_PRIVATE)
        srcTreeUri = prefs.getString("treeUri", null)?.let { Uri.parse(it) }

        adapter = FilesListAdapter(this, found)
        binding.listFiles.adapter = adapter

        binding.btnPickOtg.setOnClickListener { srcPicker.launch(srcTreeUri) }
        binding.btnScan.setOnClickListener { startSafeScan() }
        binding.btnCopy.setOnClickListener {
            val selected = getCheckedFiles()
            if (selected.isEmpty()) { toast("ยังไม่ได้เลือกไฟล์"); return@setOnClickListener }
            pendingFiles = selected
            pendingAction = PendingAction.COPY
            destPicker.launch(null)
        }
        binding.btnFixVideos.setOnClickListener {
            val selected = getCheckedFiles().filter { it.type == MediaType.VIDEO }
            if (selected.isEmpty()) { toast("โปรดเลือกไฟล์วิดีโอ"); return@setOnClickListener }
            pendingFiles = selected
            pendingAction = PendingAction.FIX
            destPicker.launch(null)
        }

        // ช่วยผู้ใช้: ถ้ายังไม่เลือก OTG ให้เด้ง picker เลย
        if (srcTreeUri == null) {
            toast("โปรดเลือกโฟลเดอร์ OTG/การ์ดก่อนใช้งาน")
            srcPicker.launch(null)
        }

        updateSummary()
    }

    // ---------- Scan ----------
    private fun startSafeScan() {
        val base = srcTreeUri ?: run { toast("กรุณาเลือกโฟลเดอร์ OTG ก่อน"); return }
        found.clear(); adapter.notifyDataSetChanged()
        binding.progress.progress = 0
        binding.txtSummary.text = "กำลังสแกน..."

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val out = mutableListOf<FoundFile>()
                val root = DocumentFile.fromTreeUri(this@MainActivity, base)
                if (root != null && root.isDirectory) {
                    val total = countFiles(root)
                    var scanned = 0
                    fun tick() {
                        scanned++
                        val p = if (total == 0) 0 else (scanned * 100 / total)
                        runOnUiThread { binding.progress.progress = p }
                    }
                    scanRecursive(root) { df ->
                        classifyFile(df)?.let { out += it }
                        tick()
                    }
                }
                out
            }
            found.addAll(results.sortedBy { it.name.lowercase() })
            adapter.notifyDataSetChanged()
            updateSummary()
            binding.progress.progress = 100
            toast("สแกนเสร็จ: พบ ${found.size} ไฟล์")
        }
    }

    private suspend fun scanRecursive(dir: DocumentFile, onFile: suspend (DocumentFile) -> Unit) {
        dir.listFiles().forEach { f ->
            if (f.isDirectory) scanRecursive(f, onFile) else onFile(f)
        }
    }
    private fun countFiles(dir: DocumentFile): Int =
        dir.listFiles().sumOf { if (it.isDirectory) countFiles(it) else 1 }

    private fun classifyFile(df: DocumentFile): FoundFile? {
        if (!df.isFile || !df.canRead()) return null
        val name = df.name ?: "(unknown)"
        val size = df.length()
        val uri = df.uri
        val mime = df.type ?: guessMimeByExt(name)
        val type = when {
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            else -> MediaType.OTHER
        }
        val status = when (type) {
            MediaType.IMAGE -> SafeCheck.isImageHealthy(contentResolver, uri)
            MediaType.VIDEO -> SafeCheck.isVideoHealthy(this, uri)
            else -> FileStatus.OK
        }
        return FoundFile(uri, name, size, mime, type, status)
    }

    // ---------- Copy & Fix ----------
    private fun doCopySafely(dest: DocumentFile, files: List<FoundFile>) {
        binding.progress.progress = 0
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                var done = 0
                for (ff in files) {
                    SafeCopy.copySafely(contentResolver, ff, dest)
                    done++
                    val p = done * 100 / files.size
                    runOnUiThread { binding.progress.progress = p }
                }
            }
            toast("คัดลอกเสร็จ")
        }
    }

    private fun doFixVideos(dest: DocumentFile, files: List<FoundFile>) {
        binding.progress.progress = 0
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                var done = 0
                for (ff in files) {
                    SafeVideo.tryRemux(this@MainActivity, ff, dest)
                    done++
                    val p = done * 100 / files.size
                    runOnUiThread { binding.progress.progress = p }
                }
            }
            toast("พยายามซ่อมวิดีโอเสร็จ")
        }
    }

    // ---------- Helpers ----------
    private fun updateSummary() {
        val t = srcTreeUri?.toString() ?: "(ยังไม่เลือก)"
        binding.txtSummary.text = "OTG: $t\nพบไฟล์: ${found.size} (ติ๊กเพื่อเลือกหลายไฟล์ได้)"
    }

    private fun getCheckedFiles(): List<FoundFile> {
        val out = mutableListOf<FoundFile>()
        for (i in 0 until binding.listFiles.count) {
            if (binding.listFiles.isItemChecked(i)) out += found[i]
        }
        return out
    }

    private fun guessMimeByExt(name: String): String {
        val low = name.lowercase()
        return when {
            low.endsWith(".jpg") || low.endsWith(".jpeg") -> "image/jpeg"
            low.endsWith(".png") -> "image/png"
            low.endsWith(".gif") -> "image/gif"
            low.endsWith(".mp4") || low.endsWith(".m4v") -> "video/mp4"
            low.endsWith(".mov") -> "video/quicktime"
            low.endsWith(".mkv") -> "video/x-matroska"
            else -> "application/octet-stream"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

// ===== Models & Adapter (คงเดิมจากเวอร์ชันก่อน) =====
import android.net.Uri
import android.app.Activity
import android.view.View
import android.view.ViewGroup

data class FoundFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val type: MediaType,
    var status: FileStatus
)

enum class MediaType { IMAGE, VIDEO, OTHER }
enum class FileStatus { OK, SUSPECT, CORRUPT }

class FilesListAdapter(
    private val ctx: Activity,
    private val items: List<FoundFile>
) : ArrayAdapter<FoundFile>(ctx, android.R.layout.simple_list_item_multiple_choice, items) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent) as CheckedTextView
        val f = items[position]
        val tag = when (f.status) {
            FileStatus.OK -> "OK"
            FileStatus.SUSPECT -> "สงสัยเสีย"
            FileStatus.CORRUPT -> "เสีย"
        }
        v.text = "${f.name}  •  ${human(f.size)}  •  ${f.type}  •  $tag"
        return v
    }
    private fun human(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B","KB","MB","GB","TB")
        val i = (log10(bytes.toDouble())/log10(1024.0)).toInt()
        return String.format("%.1f %s", bytes / Math.pow(1024.0, i.toDouble()), units[i])
    }
}
