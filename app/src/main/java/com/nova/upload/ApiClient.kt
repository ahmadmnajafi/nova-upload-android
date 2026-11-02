package com.nova.upload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class CategoryItem(
    val id: Int,
    val name: String,
    val parent: Int,
    val order: Int,
    val depth: Int = 0
)

class ApiClient(
    private val baseUrl: String = BuildConfig.API_BASE
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun form(vararg pairs: Pair<String, String>): okhttp3.RequestBody {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        pairs.forEach { (k, v) -> body.addFormDataPart(k, v) }
        return body.build()
    }

    private fun post(body: okhttp3.RequestBody, token: String? = null): JSONObject {
        val req = Request.Builder()
            .url(baseUrl)
            .post(body)
            .apply {
                if (!token.isNullOrBlank()) header("X-Nova-Upload-Token", token)
            }
            .build()
        client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                throw IllegalStateException("پاسخ نامعتبر سرور")
            }
            if (!json.optBoolean("success", false)) {
                val msg = json.optJSONObject("data")?.optString("message")
                    ?: json.optString("data", "خطا در ارتباط با سرور")
                throw IllegalStateException(msg.ifBlank { "خطا در ارتباط با سرور" })
            }
            return json.optJSONObject("data") ?: JSONObject()
        }
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

    fun fetchCategories(token: String): Pair<String, List<CategoryItem>> {
        val data = post(form("action" to "nova_upload_app_cats", "app_token" to token), token)
        val name = data.optString("display_name")
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
        return name to nest(raw)
    }

    fun logout(token: String) {
        try {
            post(form("action" to "nova_upload_app_logout", "app_token" to token), token)
        } catch (_: Exception) {
            // بی‌صدا؛ توکن محلی پاک می‌شود
        }
    }

    fun uploadFile(token: String, categoryId: Int, file: File, mime: String, displayName: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("action", "nova_upload_dual")
            .addFormDataPart("app_token", token)
            .addFormDataPart("category_id", categoryId.toString())
            .addFormDataPart("folder_id", "0")
            .addFormDataPart(
                "file",
                displayName,
                file.asRequestBody(mime.toMediaType())
            )
            .build()
        post(body, token)
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
