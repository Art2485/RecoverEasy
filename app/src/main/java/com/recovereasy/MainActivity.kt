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
    private var tree: Uri? = null
    private val items = mutableListOf<String>()
    private lateinit var binding: ActivityMainBinding

    private val pick = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            tree = uri
            Toast.makeText(this, "เลือกโฟลเดอร์แล้ว", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        binding.list.adapter = adapter

        binding.btnPick.setOnClickListener { pick.launch(tree) }
        binding.btnScan.setOnClickListener {
            val t = tree ?: return@setOnClickListener Toast.makeText(this, "กรุณาเลือกโฟลเดอร์ OTG ก่อน", Toast.LENGTH_SHORT).show()
            items.clear()
            val root = DocumentFile.fromTreeUri(this, t)
            if (root != null && root.isDirectory) {
                scan(root)
                adapter.notifyDataSetChanged()
                binding.txt.text = "พบไฟล์: ${items.size}"
            } else {
                Toast.makeText(this, "โฟลเดอร์ไม่ถูกต้อง", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scan(dir: DocumentFile) {
        dir.listFiles().forEach { f ->
            if (f.isDirectory) scan(f) else items += (f.name ?: "(unknown)")
        }
    }
}
