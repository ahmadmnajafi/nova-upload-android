package com.nova.upload

import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class CategoryItem(
    val id: Int,
    val name: String,
    val parent: Int,
    val order: Int,
    val depth: Int = 0
)

data class AccountHome(
    val displayName: String,
    val kioskName: String,
    val kioskCode: String,
    val usedBytes: Long,
    val limitBytes: Long,
    val categories: List<CategoryItem>
)

data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

data class DuplicateFile(
    val id: Int,
    val title: String,
    val categories: String,
    val thumbUrl: String,
    val mime: String
)

class ApiClient(
    private val baseUrl: String = BuildConfig.API_BASE
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun cancelOngoing() {
        activeCall?.cancel()
    }

    private fun form(vararg pairs: Pair<String, String>): RequestBody {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        pairs.forEach { (k, v) -> body.addFormDataPart(k, v) }
        return body.build()
    }

    private fun post(body: RequestBody, token: String? = null): JSONObject {
        val req = Request.Builder()
            .url(baseUrl)
            .post(body)
            .apply {
                if (!token.isNullOrBlank()) header("X-Nova-Upload-Token", token)
            }
            .build()
        val call = client.newCall(req)
        activeCall = call
        try {
            call.execute().use { res ->
                val text = res.body?.string().orEmpty()
                val json = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    throw IllegalStateException("پاسخ نامعتبر سرور")
                }
                if (!json.optBoolean("success", false)) {
                    val data = json.opt("data")
                    val msg = when (data) {
                        is JSONObject -> data.optString("message")
                        is String -> data
                        else -> ""
                    }
                    throw IllegalStateException(msg.ifBlank { "خطا در ارتباط با سرور" })
                }
                return json.optJSONObject("data") ?: JSONObject()
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw IllegalStateException("ارسال لغو شد")
            throw IllegalStateException(e.message ?: "ارتباط قطع شد")
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    fun loginWithPassword(username: String, password: String, device: String): Pair<String, String> {
        val data = post(
            form(
                "action" to "nova_upload_app_password",
                "username" to username,
                "password" to password,
                "device" to device
            )
        )
        val token = data.optString("app_token")
        val name = data.optString("display_name")
        if (token.isBlank()) throw IllegalStateException("توکن دریافت نشد")
        return token to name
    }

    fun sendOtp(phone: String) {
        post(form("action" to "nova_otp_send", "phone" to phone))
    }

    fun verifyOtp(phone: String, code: String, device: String): Pair<String, String> {
        val data = post(
            form(
                "action" to "nova_upload_app_otp_verify",
                "phone" to phone,
                "code" to code,
                "device" to device
            )
        )
        val token = data.optString("app_token")
        val name = data.optString("display_name")
        if (token.isBlank()) throw IllegalStateException("توکن دریافت نشد")
        return token to name
    }

    fun fetchCategories(token: String): AccountHome {
        val data = post(form("action" to "nova_upload_app_cats", "app_token" to token), token)
        val arr = data.optJSONArray("cats") ?: org.json.JSONArray()
        val raw = mutableListOf<CategoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            raw += CategoryItem(
                id = o.optInt("id"),
                name = o.optString("name"),
                parent = o.optInt("parent"),
                order = o.optInt("order")
            )
        }
        return AccountHome(
            displayName = data.optString("display_name"),
            kioskName = data.optString("kiosk_name"),
            kioskCode = data.optString("kiosk_code"),
            usedBytes = data.optLong("used_bytes"),
            limitBytes = data.optLong("limit_bytes"),
            categories = nest(raw)
        )
    }

    fun fetchRelease(): AppRelease {
        val data = post(form("action" to "nova_upload_app_version"))
        return AppRelease(
            versionCode = data.optInt("version_code"),
            versionName = data.optString("version_name"),
            apkUrl = data.optString("apk_url"),
            notes = data.optString("notes")
        )
    }

    fun createCategory(token: String, name: String): Int {
        val data = post(
            form(
                "action" to "nova_upload_app_cat_add",
                "app_token" to token,
                "name" to name
            ),
            token
        )
        val id = data.optInt("id")
        if (id <= 0) throw IllegalStateException("ساخت دسته‌بندی نشد")
        return id
    }

    fun findDuplicate(token: String, md5: String): DuplicateFile? {
        val data = post(
            form(
                "action" to "nova_upload_app_dup",
                "app_token" to token,
                "md5" to md5
            ),
            token
        )
        if (!data.optBoolean("duplicate")) return null
        return DuplicateFile(
            id = data.optInt("id"),
            title = data.optString("title").ifBlank { "فایل موجود" },
            categories = data.optString("categories").ifBlank { "بدون دسته‌بندی" },
            thumbUrl = data.optString("thumb_url"),
            mime = data.optString("mime")
        )
    }

    fun fetchPreview(url: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val host = try {
            java.net.URI(url).host
        } catch (_: Exception) {
            null
        } ?: return null
        val apiHost = try {
            java.net.URI(baseUrl).host
        } catch (_: Exception) {
            null
        } ?: return null
        if (!host.equals(apiHost, ignoreCase = true)) return null
        val req = Request.Builder().url(url).get().build()
        return try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body ?: return null
                val length = body.contentLength()
                if (length > 4L * 1024L * 1024L) return null
                body.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun logout(token: String) {
        try {
            post(form("action" to "nova_upload_app_logout", "app_token" to token), token)
        } catch (_: Exception) {
            // بی‌صدا؛ توکن محلی پاک می‌شود
        }
    }

    fun uploadFile(
        token: String,
        categoryId: Int,
        file: File,
        mime: String,
        displayName: String,
        replaceId: Int = 0,
        onProgress: (sent: Long, total: Long) -> Unit
    ) {
        val fileBody = ProgressRequestBody(file, mime.toMediaType(), onProgress)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("action", "nova_upload_dual")
            .addFormDataPart("app_token", token)
            .addFormDataPart("category_id", categoryId.toString())
            .addFormDataPart("folder_id", "0")
            .apply { if (replaceId > 0) addFormDataPart("replace_id", replaceId.toString()) }
            .addFormDataPart("file", displayName, fileBody)
            .build()
        post(body, token)
    }

    fun download(url: String, dest: File, onProgress: (sent: Long, total: Long) -> Unit) {
        if (!url.startsWith("https://")) throw IllegalStateException("آدرس دانلود معتبر نیست")
        val req = Request.Builder().url(url).get().build()
        val call = client.newCall(req)
        activeCall = call
        try {
            call.execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("دانلود آپدیت ناموفق بود")
                val body = res.body ?: throw IllegalStateException("فایل آپدیت خالی است")
                val total = body.contentLength().coerceAtLeast(0L)
                var sent = 0L
                var last = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            val now = System.currentTimeMillis()
                            if (total <= 0 || sent == total || now - last >= 80L) {
                                last = now
                                onProgress(sent, total)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw IllegalStateException("ارسال لغو شد")
            throw IllegalStateException(e.message ?: "ارتباط قطع شد")
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun nest(list: List<CategoryItem>): List<CategoryItem> {
        val byParent = list.groupBy { it.parent }
        val out = mutableListOf<CategoryItem>()
        fun walk(pid: Int, depth: Int) {
            val kids = byParent[pid].orEmpty().sortedWith(compareBy({ it.order }, { it.name }))
            kids.forEach { c ->
                out += c.copy(depth = depth)
                walk(c.id, depth + 1)
            }
        }
        walk(0, 0)
        list.filter { item -> out.none { it.id == item.id } }.forEach { out += it }
        return out
    }
}

private class ProgressRequestBody(
    private val file: File,
    private val mediaType: MediaType?,
    private val onProgress: (sent: Long, total: Long) -> Unit
) : RequestBody() {
    override fun contentType() = mediaType
    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().coerceAtLeast(1L)
        var sent = 0L
        var last = 0L
        file.source().use { source ->
            while (true) {
                val read = source.read(sink.buffer, 32 * 1024)
                if (read == -1L) break
                sent += read
                sink.flush()
                val now = System.currentTimeMillis()
                if (sent >= total || now - last >= 80L) {
                    last = now
                    onProgress(sent.coerceAtMost(total), total)
                }
            }
        }
    }
}
