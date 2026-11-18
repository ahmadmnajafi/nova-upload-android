package com.nova.upload

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nova.upload.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val app get() = application.nova()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSendCode.setOnClickListener { sendCode() }
        binding.btnVerify.setOnClickListener { verify() }
        binding.btnPassword.setOnClickListener { loginWithPassword() }
        binding.btnModePhone.setOnClickListener { showPhone(true) }
        binding.btnModeUser.setOnClickListener { showPhone(false) }
        showPhone(true)
    }

    private fun showPhone(phone: Boolean) {
        binding.phonePanel.visibility = if (phone) View.VISIBLE else View.GONE
        binding.passPanel.visibility = if (phone) View.GONE else View.VISIBLE
        val on = ContextCompat.getColor(this, R.color.nova_primary)
        val off = ContextCompat.getColor(this, R.color.nova_fill)
        val onText = ContextCompat.getColor(this, android.R.color.white)
        val offText = ContextCompat.getColor(this, R.color.nova_text)
        binding.btnModePhone.backgroundTintList = ColorStateList.valueOf(if (phone) on else off)
        binding.btnModeUser.backgroundTintList = ColorStateList.valueOf(if (phone) off else on)
        binding.btnModePhone.setTextColor(if (phone) onText else offText)
        binding.btnModeUser.setTextColor(if (phone) offText else onText)
        binding.txtStatus.text = ""
    }

    private fun loginWithPassword() {
        val user = binding.inputUser.text?.toString()?.trim().orEmpty()
        val pass = binding.inputPassword.text?.toString().orEmpty()
        if (user.isEmpty() || pass.isEmpty()) {
            toast("نام کاربری و رمز را وارد کنید")
            return
        }
        binding.btnPassword.isEnabled = false
        binding.txtStatus.text = "در حال ورود…"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".take(80)
        lifecycleScope.launch {
            try {
                val (token, name) = withContext(Dispatchers.IO) {
                    app.api.loginWithPassword(user, pass, device)
                }
                app.session.appToken = token
                app.session.displayName = name
                goMain()
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "خطا"
                toast(e.message ?: "خطا")
            } finally {
                binding.btnPassword.isEnabled = true
            }
        }
    }

    private fun phone(): String {
        return binding.inputPhone.text?.toString()?.trim()
            ?.replace('۰', '0')?.replace('۱', '1')?.replace('۲', '2')?.replace('۳', '3')
            ?.replace('۴', '4')?.replace('۵', '5')?.replace('۶', '6')?.replace('۷', '7')
            ?.replace('۸', '8')?.replace('۹', '9')
            .orEmpty()
    }

    private fun sendCode() {
        val p = phone()
        if (!p.matches(Regex("^09\\d{9}$"))) {
            toast("شماره را ۱۱ رقمی وارد کنید (مثال ۰۹۱۲…)")
            return
        }
        binding.btnSendCode.isEnabled = false
        binding.txtStatus.text = "در حال ارسال کد…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.api.sendOtp(p) }
                binding.boxCode.visibility = View.VISIBLE
                binding.btnVerify.visibility = View.VISIBLE
                binding.txtStatus.text = "کد پیامک شد. آن را وارد کنید."
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "خطا"
                toast(e.message ?: "خطا")
            } finally {
                binding.btnSendCode.isEnabled = true
            }
        }
    }

    private fun verify() {
        val p = phone()
        val code = binding.inputCode.text?.toString()?.trim()
            ?.replace(Regex("[^0-9۰-۹]"), "")
            ?.map {
                when (it) {
                    '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                    '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                    else -> it
                }
            }?.joinToString("").orEmpty()
        if (code.length != 4) {
            toast("کد ۴ رقمی را وارد کنید")
            return
        }
        binding.btnVerify.isEnabled = false
        binding.txtStatus.text = "در حال ورود…"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".take(80)
        lifecycleScope.launch {
            try {
                val (token, name) = withContext(Dispatchers.IO) {
                    app.api.verifyOtp(p, code, device)
                }
                app.session.appToken = token
                app.session.displayName = name
                goMain()
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "خطا"
                toast(e.message ?: "خطا")
            } finally {
                binding.btnVerify.isEnabled = true
            }
        }
    }

    private fun goMain() {
        val next = Intent(this, MainActivity::class.java)
        intent.getParcelableArrayListExtra<Uri>(MainActivity.EXTRA_PENDING_URIS)?.let {
            next.putParcelableArrayListExtra(MainActivity.EXTRA_PENDING_URIS, it)
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(next)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
