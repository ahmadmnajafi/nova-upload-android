package com.nova.upload

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var busy = false
    private var pendingApk: File? = null

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
        if (!busy) binding.uploadPanel.visibility = View.GONE
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
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        adapter = CategoryAdapter { cat ->
            adapter.selectedId = cat.id
            app.session.lastCategoryId = cat.id
            updateUploadEnabled()
        }
        binding.listCats.layoutManager = LinearLayoutManager(this)
        binding.listCats.adapter = adapter

        binding.txtUser.text = app.session.displayName.orEmpty()
        binding.txtVersion.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        binding.btnMenu.setOnClickListener { binding.drawer.openDrawer(GravityCompat.START) }
        binding.btnRefresh.setOnClickListener { loadCats(fromUser = true) }
        binding.btnUpdate.setOnClickListener { checkUpdate() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnPick.setOnClickListener {
            pickMedia.launch(arrayOf("image/*", "video/*"))
        }
        binding.btnUpload.setOnClickListener { startUpload() }

        ingestIntent(intent)
        loadCats()
    }

    override fun onResume() {
        super.onResume()
        val file = pendingApk ?: return
        if (!file.exists()) {
            pendingApk = null
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingApk = null
            installApk(file)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::binding.isInitialized) ingestIntent(intent)
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

    private fun loadCats(fromUser: Boolean = false) {
        val token = app.session.appToken ?: return goLogin(arrayListOf())
        if (::binding.isInitialized && binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawer(GravityCompat.START)
        }
        binding.txtStatus.text = if (fromUser) "در حال بروزرسانی…" else "در حال بارگذاری دسته‌ها…"
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
                binding.txtStatus.text = if (cats.isEmpty()) getString(R.string.no_cats) else ""
                updateUploadEnabled()
                if (fromUser) toast("بروزرسانی شد")
            } catch (e: Exception) {
                if (e.message?.contains("توکن") == true || e.message?.contains("نشست") == true) {
                    app.session.clear()
                    goLogin(ArrayList(pendingUris))
                } else {
                    binding.txtStatus.text = e.message ?: "خطا"
                    toast(e.message ?: "خطا")
                }
            }
        }
    }

    private fun checkUpdate() {
        if (busy) {
            toast("اول ارسال تمام شود")
            return
        }
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawer(GravityCompat.START)
        }
        binding.txtStatus.text = "در حال بررسی آپدیت…"
        lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { app.api.fetchRelease() }
                binding.txtStatus.text = ""
                when {
                    release.versionCode > BuildConfig.VERSION_CODE && release.apkUrl.startsWith("https://") -> {
                        showUpdateDialog(release)
                    }
                    release.versionCode > BuildConfig.VERSION_CODE -> {
                        toast("نسخه جدید هست، ولی لینک دانلود هنوز روی سرور تنظیم نشده")
                    }
                    else -> toast("همین نسخه نصب است")
                }
            } catch (e: Exception) {
                binding.txtStatus.text = ""
                toast(friendly(e))
            }
        }
    }

    private fun showUpdateDialog(release: AppRelease) {
        val notes = release.notes.trim()
        val message = buildString {
            append("نسخه ")
            append(release.versionName.ifBlank { fa(release.versionCode) })
            append(" آماده است.")
            if (notes.isNotEmpty()) {
                append("\n")
                append(notes)
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_update))
            .setMessage(message)
            .setPositiveButton("دریافت و نصب") { _, _ -> downloadUpdate(release) }
            .setNegativeButton("بعداً", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        dialog.show()
    }

    private fun downloadUpdate(release: AppRelease) {
        busy = true
        updateUploadEnabled()
        val dest = File(cacheDir, "nova-uploader-update.apk")
        if (dest.exists()) dest.delete()
        showSend("Nova Uploader", "در حال دریافت آپدیت…", indeterminate = true, percent = 0)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.api.download(release.apkUrl, dest) { sent, total ->
                        val percent = if (total > 0) ((sent * 100) / total).toInt() else 0
                        runOnUiThread {
                            showSend(
                                "Nova Uploader",
                                if (total > 0) "دریافت آپدیت · ${fa(percent)}٪" else "در حال دریافت آپدیت…",
                                indeterminate = total <= 0,
                                percent = percent
                            )
                        }
                    }
                }
                showSend("Nova Uploader", "آماده نصب", indeterminate = false, percent = 100)
                installApk(dest)
            } catch (e: Exception) {
                dest.delete()
                binding.uploadPanel.visibility = View.GONE
                toast(e.message ?: "دانلود آپدیت ناموفق بود")
            } finally {
                busy = false
                updateUploadEnabled()
            }
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingApk = file
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            toast("اجازه نصب را بدهید، بعد آپدیت ادامه پیدا می‌کند")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(view)
    }

    private fun refreshHint() {
        binding.txtHint.text = when {
            pendingUris.isNotEmpty() -> getString(R.string.files_ready, fa(pendingUris.size))
            else -> getString(R.string.pick_category)
        }
    }

    private fun updateUploadEnabled() {
        if (!::binding.isInitialized || !::adapter.isInitialized) return
        binding.btnPick.isEnabled = !busy
        binding.btnUpload.isEnabled = !busy && adapter.selectedId > 0 && pendingUris.isNotEmpty()
    }

    private fun startUpload() {
        val token = app.session.appToken ?: return
        val catId = adapter.selectedId
        if (catId <= 0 || pendingUris.isEmpty() || busy) return
        busy = true
        updateUploadEnabled()
        val uris = pendingUris.toList()
        lifecycleScope.launch {
            var ok = 0
            var bad = 0
            uris.forEachIndexed { index, uri ->
                val label = "فایل ${fa(index + 1)} از ${fa(uris.size)}"
                showSend(label, "آماده‌سازی…", indeterminate = true, percent = 0)
                try {
                    val (file, mime, name) = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                    try {
                        showSend(name, "ارسال ${fa(index + 1)} از ${fa(uris.size)}", indeterminate = false, percent = 0)
                        withContext(Dispatchers.IO) {
                            app.api.uploadFile(token, catId, file, mime, name) { sent, total ->
                                val fraction = if (total > 0) sent.toFloat() / total else 0f
                                val overall = (((index + fraction) / uris.size) * 100).toInt().coerceIn(0, 100)
                                val filePercent = (fraction * 100).toInt().coerceIn(0, 100)
                                runOnUiThread {
                                    showSend(
                                        name,
                                        "ارسال ${fa(index + 1)} از ${fa(uris.size)} · ${fa(filePercent)}٪",
                                        indeterminate = false,
                                        percent = overall
                                    )
                                }
                            }
                        }
                        ok++
                    } finally {
                        file.delete()
                    }
                } catch (e: Exception) {
                    bad++
                    toast(e.message ?: "خطا در یک فایل")
                }
            }
            busy = false
            pendingUris.clear()
            refreshHint()
            updateUploadEnabled()
            val summary = "تمام شد: ${fa(ok)} موفق" + if (bad > 0) "، ${fa(bad)} ناموفق" else ""
            showSend("ارسال تمام شد", summary, indeterminate = false, percent = 100)
            toast(summary)
        }
    }

    private fun showSend(fileName: String, detail: String, indeterminate: Boolean, percent: Int) {
        binding.uploadPanel.visibility = View.VISIBLE
        binding.txtFile.text = fileName
        binding.txtProgress.text = detail
        if (indeterminate) {
            binding.progress.isIndeterminate = true
        } else {
            binding.progress.isIndeterminate = false
            binding.progress.setProgressCompat(percent, true)
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
        if (busy) {
            toast("اول ارسال تمام شود")
            return
        }
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawer(GravityCompat.START)
        }
        val token = app.session.appToken
        lifecycleScope.launch {
            if (!token.isNullOrBlank()) withContext(Dispatchers.IO) { app.api.logout(token) }
            app.session.clear()
            goLogin(arrayListOf())
        }
    }

    private fun friendly(e: Exception): String {
        val msg = e.message?.trim().orEmpty()
        return if (msg.isBlank() || msg == "0" || msg == "خطا در ارتباط با سرور") {
            "بررسی آپدیت الان ممکن نیست"
        } else {
            msg
        }
    }

    private fun fa(n: Int): String = n.toString().map { ch ->
        if (ch in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[ch - '0'] else ch
    }.joinToString("")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PENDING_URIS = "pending_uris"
    }
}
