package com.nova.upload

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat

fun Context.novaToast(message: String) {
    val text = LayoutInflater.from(this).inflate(R.layout.toast_nova, null) as TextView
    text.typeface = ResourcesCompat.getFont(this, R.font.yekanbakh_regular)
    text.text = message
    text.maxWidth = (resources.displayMetrics.widthPixels * 0.88f).toInt()
    Toast(this).apply {
        duration = Toast.LENGTH_LONG
        @Suppress("DEPRECATION")
        setView(text)
        setGravity(
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0,
            (88 * resources.displayMetrics.density).toInt()
        )
    }.show()
}
