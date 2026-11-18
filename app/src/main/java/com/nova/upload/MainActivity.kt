package com.nova.upload

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nova.upload.databinding.ActivityMainBinding
import com.nova.upload.databinding.DialogDupBinding
import com.nova.upload.databinding.SheetSendBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val app get() = application.nova()
    private lateinit var adapter: CategoryAdapter
    private val pendingUris = mutableListOf<Uri>()
    private var busy = false
    private var userCancelled = false
    private var transferJob: Job? = null
    private var pendingApk: File? = null
    private var pendingRelease: AppRelease? = null
    private var sheet: BottomSheetDialog? = null
    private var sheetBinding: SheetSendBinding? = null

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
        if (!busy) openSendSheet()
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
        binding.txtVersion.text = getString(R.string.version_label, faText(BuildConfig.VERSION_NAME))
        binding.swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.nova_primary))
        binding.swipe.setOnRefreshListener { loadCats(fromUser = true) }
        binding.btnMenu.setOnClickListener { binding.drawer.openDrawer(GravityCompat.START) }
        binding.btnVersionUpdate.setOnClickListener { pendingRelease?.let { downloadUpdate(it) } }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnPick.setOnClickListener {
            if (busy) return@setOnClickListener
            if (adapter.selectedId <= 0) {
                toast("اول یک دسته‌بندی انتخاب کنید")
                return@setOnClickListener
            }
            pickMedia.launch(arrayOf("image/*", "video/*"))
        }

        ingestIntent(intent)
        loadCats()
        lookForUpdate()
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
        if (pendingUris.isNotEmpty() && !busy) openSendSheet()
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
        if (fromUser) binding.swipe.isRefreshing = true
        else binding.txtStatus.text = "در حال بارگذاری دسته‌ها…"
        lifecycleScope.launch {
            try {
                val home = withContext(Dispatchers.IO) { app.api.fetchCategories(token) }
                if (home.displayName.isNotBlank()) {
                    app.session.displayName = home.displayName
                    binding.txtUser.text = home.displayName
                }
                binding.txtKioskName.text = home.kioskName.ifBlank { "—" }
                binding.txtKioskCode.text = faText(home.kioskCode.ifBlank { "—" })
                showStorage(home.usedBytes, home.limitBytes)
                adapter.submit(home.categories)
                val last = app.session.lastCategoryId
                if (last > 0 && home.categories.any { it.id == last }) adapter.selectedId = last
                binding.txtStatus.text = if (home.categories.isEmpty()) getString(R.string.no_cats) else ""
                updateUploadEnabled()
                if (fromUser) toast("دسته‌ها بروزرسانی شد")
            } catch (e: Exception) {
                if (e.message?.contains("توکن") == true || e.message?.contains("نشست") == true) {
                    app.session.clear()
                    goLogin(ArrayList(pendingUris))
                } else {
                    binding.txtStatus.text = e.message ?: "خطا"
                    toast(e.message ?: "خطا")
                }
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun lookForUpdate() {
        lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { app.api.fetchRelease() }
                val ready = release.versionCode > BuildConfig.VERSION_CODE && release.apkUrl.startsWith("https://")
                pendingRelease = if (ready) release else null
                binding.btnVersionUpdate.visibility = if (ready) View.VISIBLE else View.GONE
            } catch (_: Exception) {
                binding.btnVersionUpdate.visibility = View.GONE
            }
        }
    }

    private fun showStorage(used: Long, limit: Long) {
        binding.txtStorage.text = if (limit <= 0) "—" else "${formatSize(used)} از ${formatSize(limit)}"
        val percent = if (limit <= 0) 0 else ((used * 100) / limit).toInt().coerceIn(0, 100)
        binding.usageBar.setProgressCompat(percent, false)
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val text = if (gb >= 1) {
            String.format(java.util.Locale.US, "%.1f گیگابایت", gb)
        } else {
            String.format(java.util.Locale.US, "%.0f مگابایت", bytes / (1024.0 * 1024.0))
        }
        return faText(text)
    }

    private fun downloadUpdate(release: AppRelease) {
        beginTransfer()
        val dest = File(cacheDir, "nova-uploader-update.apk")
        if (dest.exists()) dest.delete()
        showSend("Nova Uploader", "در حال دریافت آپدیت…", indeterminate = true, percent = 0)
        transferJob = lifecycleScope.launch {
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
                if (userCancelled) return@launch
                showSend("Nova Uploader", "آماده نصب", indeterminate = false, percent = 100)
                installApk(dest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dest.delete()
                if (!userCancelled) {
                    closeSheet()
                    toast(e.message ?: "دانلود آپدیت ناموفق بود")
                }
            } finally {
                endTransfer()
                if (userCancelled) {
                    dest.delete()
                    closeSheet()
                    toast("دریافت لغو شد")
                }
            }
        }
    }

    private fun beginTransfer() {
        userCancelled = false
        busy = true
        ensureSheet()
        sheet?.setCancelable(false)
        sheetBinding?.btnSend?.visibility = View.GONE
        sheetBinding?.btnDismiss?.setText(R.string.cancel_send)
        sheetBinding?.btnDismiss?.setTextColor(ContextCompat.getColor(this, R.color.nova_danger))
        updateUploadEnabled()
    }

    private fun endTransfer() {
        busy = false
        sheet?.setCancelable(true)
        updateUploadEnabled()
    }

    private fun cancelTransfer() {
        if (!busy) return
        userCancelled = true
        app.api.cancelOngoing()
        transferJob?.cancel()
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
        if (!::binding.isInitialized) return
        val hasCat = ::adapter.isInitialized && adapter.selectedId > 0
        binding.txtHint.text = when {
            pendingUris.isNotEmpty() -> getString(R.string.files_ready, fa(pendingUris.size))
            hasCat -> getString(R.string.pick_files_next)
            else -> getString(R.string.pick_category)
        }
    }

    private fun updateUploadEnabled() {
        if (!::binding.isInitialized || !::adapter.isInitialized) return
        binding.btnPick.isEnabled = !busy
        refreshHint()
        val canSend = !busy && adapter.selectedId > 0 && pendingUris.isNotEmpty()
        sheetBinding?.btnSend?.isEnabled = canSend
    }

    private fun startUpload() {
        val token = app.session.appToken ?: return
        val catId = adapter.selectedId
        if (catId <= 0) {
            toast("اول یک دسته را انتخاب کنید")
            return
        }
        if (pendingUris.isEmpty() || busy) return
        beginTransfer()
        val uris = pendingUris.toList()
        transferJob = lifecycleScope.launch {
            var ok = 0
            var bad = 0
            var skipped = 0
            try {
                uris.forEachIndexed { index, uri ->
                    if (userCancelled) return@forEachIndexed
                    val label = "فایل ${fa(index + 1)} از ${fa(uris.size)}"
                    showSend(label, "آماده‌سازی…", indeterminate = true, percent = 0)
                    try {
                        val (file, mime, name) = withContext(Dispatchers.IO) { copyUriToCache(uri) }
                        try {
                            if (userCancelled) return@forEachIndexed
                            showSend(name, "بررسی تکراری…", indeterminate = true, percent = 0)
                            val duplicate = try {
                                val md5 = withContext(Dispatchers.IO) { fileMd5(file) }
                                withContext(Dispatchers.IO) { app.api.findDuplicate(token, md5) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                if (userCancelled) return@forEachIndexed
                                null
                            }
                            if (duplicate != null) {
                                val accept = confirmDuplicate(file, mime, name, duplicate)
                                if (userCancelled) return@forEachIndexed
                                if (!accept) {
                                    skipped++
                                    pendingUris.remove(uri)
                                    return@forEachIndexed
                                }
                            }
                            showSend(name, "ارسال ${fa(index + 1)} از ${fa(uris.size)}", indeterminate = false, percent = 0)
                            withContext(Dispatchers.IO) {
                                app.api.uploadFile(token, catId, file, mime, name) { sent, total ->
                                    val fraction = if (total > 0) sent.toFloat() / total else 0f
                                    val overall = (((index + fraction) / uris.size) * 100).toInt().coerceIn(0, 100)
                                    val filePercent = (fraction * 100).toInt().coerceIn(0, 100)
                                    runOnUiThread {
                                        if (!userCancelled) {
                                            showSend(
                                                name,
                                                "ارسال ${fa(index + 1)} از ${fa(uris.size)} · ${fa(filePercent)}٪",
                                                indeterminate = false,
                                                percent = overall
                                            )
                                        }
                                    }
                                }
                            }
                            ok++
                            pendingUris.remove(uri)
                        } finally {
                            file.delete()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (userCancelled) return@forEachIndexed
                        bad++
                        toast(e.message ?: "خطا در یک فایل")
                    }
                }
            } finally {
                val cancelled = userCancelled
                endTransfer()
                refreshHint()
                closeSheet()
                if (cancelled) {
                    toast(if (ok > 0) "ارسال لغو شد. ${fa(ok)} فایل فرستاده شد" else "ارسال لغو شد")
                } else {
                    pendingUris.clear()
                    refreshHint()
                    val summary = buildString {
                        append("تمام شد: ${fa(ok)} موفق")
                        if (bad > 0) append("، ${fa(bad)} ناموفق")
                        if (skipped > 0) append("، ${fa(skipped)} رد شد")
                    }
                    toast(summary)
                }
            }
        }
    }

    private fun openSendSheet() {
        ensureSheet()
        val b = sheetBinding ?: return
        val cat = adapter.selectedId.takeIf { it > 0 }?.let { id ->
            // name is shown from the hint path; category title comes from the list text if possible
            id
        }
        b.txtTitle.setText(R.string.start_upload)
        b.txtSub.text = "${fa(pendingUris.size)} فایل" + if (cat == null) " — اول دسته را انتخاب کنید" else ""
        b.fileList.visibility = View.VISIBLE
        b.fileList.removeAllViews()
        pendingUris.take(6).forEach { uri ->
            val row = android.widget.TextView(this)
            row.text = queryDisplayName(uri) ?: "فایل"
            row.setTextColor(ContextCompat.getColor(this, R.color.nova_text))
            row.textSize = 15f
            row.setPadding(0, 12, 0, 12)
            b.fileList.addView(row)
        }
        if (pendingUris.size > 6) {
            val more = android.widget.TextView(this)
            more.text = "و ${fa(pendingUris.size - 6)} فایل دیگر"
            more.setTextColor(ContextCompat.getColor(this, R.color.nova_muted))
            b.fileList.addView(more)
        }
        b.txtProgress.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.btnSend.visibility = View.VISIBLE
        b.btnDismiss.setText(R.string.dismiss)
        b.btnDismiss.setTextColor(ContextCompat.getColor(this, R.color.nova_muted))
        updateUploadEnabled()
    }

    private fun ensureSheet() {
        if (sheet?.isShowing == true && sheetBinding != null) return
        val dialog = BottomSheetDialog(this)
        val b = SheetSendBinding.inflate(layoutInflater)
        dialog.setContentView(b.root)
        b.btnSend.setOnClickListener { startUpload() }
        b.btnDismiss.setOnClickListener {
            if (busy) cancelTransfer() else dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        dialog.setOnDismissListener {
            if (!busy) {
                pendingUris.clear()
                sheet = null
                sheetBinding = null
                if (::binding.isInitialized) {
                    refreshHint()
                    updateUploadEnabled()
                }
            }
        }
        sheet = dialog
        sheetBinding = b
        dialog.show()
    }

    private suspend fun confirmDuplicate(file: File, mime: String, name: String, dup: DuplicateFile): Boolean {
        return suspendCancellableCoroutine { cont ->
            val dialog = Dialog(this)
            val b = DialogDupBinding.inflate(layoutInflater)
            dialog.setContentView(b.root)
            dialog.setCancelable(false)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
            listOf(b.imgNew, b.imgOld).forEach { image ->
                image.clipToOutline = true
                image.outlineProvider = ViewOutlineProvider.BACKGROUND
            }
            b.txtNewCat.text = adapter.selectedName().ifBlank { "—" }
            b.txtNewName.text = name
            b.txtOldCat.text = dup.categories
            b.txtOldName.text = dup.title
            fun finish(accept: Boolean) {
                if (cont.isActive) cont.resume(accept) {}
                dialog.dismiss()
            }
            b.btnAccept.setOnClickListener { finish(true) }
            b.btnReject.setOnClickListener { finish(false) }
            val images = lifecycleScope.launch {
                val local = withContext(Dispatchers.IO) { decodePreview(file, mime) }
                val remoteBytes = withContext(Dispatchers.IO) { app.api.fetchPreview(dup.thumbUrl) }
                val remote = remoteBytes?.let { decodeBytes(it) }
                if (!isActive) return@launch
                if (local != null) b.imgNew.setImageBitmap(local) else b.txtNewEmpty.visibility = View.VISIBLE
                if (remote != null) b.imgOld.setImageBitmap(remote) else b.txtOldEmpty.visibility = View.VISIBLE
            }
            cont.invokeOnCancellation {
                images.cancel()
                runOnUiThread { if (dialog.isShowing) dialog.dismiss() }
            }
            dialog.show()
        }
    }

    private fun fileMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun decodePreview(file: File, mime: String): Bitmap? {
        if (mime.startsWith("video")) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0)
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
        return decodeFile(file.absolutePath)
    }

    private fun decodeFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        })
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        })
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 900 || height / sample > 900) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun closeSheet() {
        sheet?.setCancelable(true)
        sheet?.dismiss()
        sheet = null
        sheetBinding = null
    }

    private fun showSend(fileName: String, detail: String, indeterminate: Boolean, percent: Int) {
        ensureSheet()
        val b = sheetBinding ?: return
        b.txtTitle.text = fileName
        b.txtSub.text = detail
        b.fileList.visibility = View.GONE
        b.txtProgress.visibility = View.GONE
        b.progress.visibility = View.VISIBLE
        if (indeterminate) b.progress.isIndeterminate = true
        else {
            b.progress.isIndeterminate = false
            b.progress.setProgressCompat(percent, true)
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

    private fun fa(n: Int): String = faText(n.toString())

    private fun faText(value: String): String = value.map { ch ->
        if (ch in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[ch - '0'] else ch
    }.joinToString("")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PENDING_URIS = "pending_uris"
    }
}
