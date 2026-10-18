package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val info = TextView(this).apply {
            textSize = 16f
            text = "Jarvis\n\nPega tu clave de API de Anthropic, pulsa «Activar Jarvis» y concede los permisos. " +
                "Después podrás decir «Jarvis» desde cualquier app, o tocar la burbuja flotante."
        }
        val key = EditText(this).apply {
            hint = "Clave de API (sk-ant-...)"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val start = Button(this).apply { text = "Activar Jarvis" }
        val stop = Button(this).apply { text = "Desactivar" }

        start.setOnClickListener {
            prefs.edit().putString("key", key.text.toString().trim()).apply()
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 1)
                toast("Concede los permisos y pulsa de nuevo")
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                toast("Activa «Mostrar sobre otras apps», vuelve y pulsa de nuevo")
                return@setOnClickListener
            }
            startForegroundService(Intent(this, JarvisService::class.java))
            toast("Jarvis activo. Di «Jarvis» o toca la burbuja.")
        }
        stop.setOnClickListener {
            stopService(Intent(this, JarvisService::class.java))
            toast("Jarvis desactivado")
        }
        root.addView(info); root.addView(key); root.addView(start); root.addView(stop)
        setContentView(root)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
