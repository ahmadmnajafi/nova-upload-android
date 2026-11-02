package com.nova.upload

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.nova.upload.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val app get() = application.nova()
    private lateinit var adapter: CategoryAdapter
    private val pendingUris = mutableListOf<Uri>()
    private var uploading = false

    private val pickMedia = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // بعضی URIهای Share قابل ماندگار شدن نیستند؛ همان نشست کافی است
            }
        }
        pendingUris.clear()
        pendingUris.addAll(uris)
        refreshHint()
        updateUploadEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.session.isLoggedIn()) {
            goLogin(collectShareUris(intent))
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = CategoryAdapter { cat ->
            adapter.selectedId = cat.id
            app.session.lastCategoryId = cat.id
            updateUploadEnabled()
        }
        binding.listCats.layoutManager = LinearLayoutManager(this)
        binding.listCats.adapter = adapter

        binding.txtUser.text = app.session.displayName.orEmpty()
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnPick.setOnClickListener {
            pickMedia.launch(arrayOf("image/*", "video/*"))
        }
        binding.btnUpload.setOnClickListener { startUpload() }

        ingestIntent(intent)
        loadCats()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ingestIntent(intent)
    }

    private fun ingestIntent(intent: Intent?) {
        val shared = collectShareUris(intent)
        if (shared.isNotEmpty()) {
            pendingUris.clear()
            pendingUris.addAll(shared)
        }
        intent?.getParcelableArrayListExtra<Uri>(EXTRA_PENDING_URIS)?.let {
            if (it.isNotEmpty()) {
                pendingUris.clear()
                pendingUris.addAll(it)
            }
        }
        refreshHint()
        updateUploadEnabled()
    }

    private fun collectShareUris(intent: Intent?): ArrayList<Uri> {
        val out = arrayListOf<Uri>()
        if (intent == null) return out
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            }
        }
        return out
    }

    private fun goLogin(pending: ArrayList<Uri>) {
        val i = Intent(this, LoginActivity::class.java)
        if (pending.isNotEmpty()) i.putParcelableArrayListExtra(EXTRA_PENDING_URIS, pending)
        startActivity(i)
        finish()
    }

    private fun loadCats() {
        val token = app.session.appToken ?: return goLogin(arrayListOf())
        binding.txtProgress.text = "در حال بارگذاری دسته‌ها…"
        lifecycleScope.launch {
            try {
                val (name, cats) = withContext(Dispatchers.IO) { app.api.fetchCategories(token) }
                if (name.isNotBlank()) {
                    app.session.displayName = name
                    binding.txtUser.text = name
                }
                adapter.submit(cats)
                val last = app.session.lastCategoryId
                if (last > 0 && cats.any { it.id == last }) adapter.selectedId = last
                binding.txtProgress.text = if (cats.isEmpty()) getString(R.string.no_cats) else ""
                updateUploadEnabled()
            } catch (e: Exception) {
                if (e.message?.contains("توکن") == true || e.message?.contains("نشست") == true) {
                    app.session.clear()
                    goLogin(ArrayList(pendingUris))
                } else {
                    binding.txtProgress.text = e.message ?: "خطا"
                    toast(e.message ?: "خطا")
                }
            }
        }
    }

    private fun refreshHint() {
        binding.txtHint.text = when {
            pendingUris.isNotEmpty() -> getString(R.string.share_ready) + " (${pendingUris.size})"
            else -> getString(R.string.pick_category)
        }
    }

    private fun updateUploadEnabled() {
        binding.btnUpload.isEnabled = !uploading && adapter.selectedId > 0 && pendingUris.isNotEmpty()
    }

    private fun startUpload() {
        val token = app.session.appToken ?: return
        val catId = adapter.selectedId
        if (catId <= 0 || pendingUris.isEmpty() || uploading) return
        uploading = true
        updateUploadEnabled()
        val uris = pendingUris.toList()
        lifecycleScope.launch {
            var ok = 0
            var bad = 0
            uris.forEachIndexed { index, uri ->
                binding.txtProgress.text = "آپلود ${index + 1} از ${uris.size}…"
                try {
                    withContext(Dispatchers.IO) {
                        val (file, mime, name) = copyUriToCache(uri)
                        try {
                            app.api.uploadFile(token, catId, file, mime, name)
                        } finally {
                            file.delete()
                        }
                    }
                    ok++
                } catch (e: Exception) {
                    bad++
                    toast(e.message ?: "خطا در یک فایل")
                }
            }
            uploading = false
            pendingUris.clear()
            refreshHint()
            updateUploadEnabled()
            binding.txtProgress.text = "تمام شد: $ok موفق" + if (bad > 0) "، $bad ناموفق" else ""
            toast(binding.txtProgress.text.toString())
        }
    }

    private fun copyUriToCache(uri: Uri): Triple<File, String, String> {
        val name = queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}"
        val mime = contentResolver.getType(uri) ?: guessMime(name)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val safeName = if (name.contains('.')) name else "$name.${ext ?: "bin"}"
        val out = File(cacheDir, "up_${System.currentTimeMillis()}_$safeName")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "خواندن فایل ممکن نیست" }
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return Triple(out, mime, safeName)
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun logout() {
        val token = app.session.appToken
        lifecycleScope.launch {
            if (!token.isNullOrBlank()) withContext(Dispatchers.IO) { app.api.logout(token) }
            app.session.clear()
            goLogin(arrayListOf())
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PENDING_URIS = "pending_uris"
    }
}
