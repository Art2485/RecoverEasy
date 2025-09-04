package com.recovereasy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.recovereasy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private var pickedTree: Uri? = null
    private lateinit var binding: ActivityMainBinding
    private val found = mutableListOf<String>()

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            pickedTree = uri
            Toast.makeText(this, "เลือกโฟลเดอร์แล้ว", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "ยังไม่ได้เลือกโฟลเดอร์", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, found)
        binding.list.adapter = adapter

        binding.btnPick.setOnClickListener { openTree.launch(null) }
        binding.btnScan.setOnClickListener {
            val uri = pickedTree
            if (uri == null) {
                Toast.makeText(this, "กรุณาเลือกโฟลเดอร์ OTG/SD ก่อน", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root == null || !root.isDirectory) {
                Toast.makeText(this, "โฟลเดอร์ไม่ถูกต้อง", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            found.clear()
            val exts = setOf(
                // รูป/วิดีโอ/เอกสาร—เพิ่มได้ตามต้องการ
                "jpg","jpeg","png","gif","webp","heif","heic",
                "mp4","mkv","mov","avi","3gp","m4v",
                "mp3","wav","m4a","aac","flac",
                "pdf","doc","docx","xls","xlsx","ppt","pptx"
            )
            scanRecursive(root, exts)
            adapter.notifyDataSetChanged()
            binding.txt.text = "พบไฟล์: ${found.size}"
            if (found.isEmpty()) {
                Toast.makeText(this, "ไม่พบไฟล์ในโฟลเดอร์นี้", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scanRecursive(dir: DocumentFile, exts: Set<String>) {
        dir.listFiles().forEach { f ->
            if (f.isDirectory) {
                scanRecursive(f, exts)
            } else {
                val name = (f.name ?: "").lowercase()
                val ok = exts.any { name.endsWith(".$it") }
                if (ok) {
                    found += (f.name ?: "(ไม่ทราบชื่อ)")
                }
            }
        }
    }
}
