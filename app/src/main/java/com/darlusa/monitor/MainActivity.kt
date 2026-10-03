package com.darlusa.monitor

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var deviceLabelInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        // Already configured phones: keep reporting on the new 15-min schedule after an update
        if (!prefs.getString("device_label", "").isNullOrEmpty()) scheduleWork()
        if (!prefs.getString("device_label", "").isNullOrEmpty()) showLock() else showSetup()
    }

    private var tapCount = 0
    private var lastTap = 0L

    // Locked screen: nothing responds except a hidden 5-tap on the title (Admin only)
    private fun showLock() {
        tapCount = 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        val title = TextView(this).apply {
            text = "DarLusa Monitor"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "DarLusa Monitor inafanya kazi vizuri."
            setPadding(0, 0, 0, 32)
        })
        val pin = EditText(this).apply {
            hint = "Admin PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            visibility = android.view.View.GONE
        }
        val open = Button(this).apply {
            text = "Fungua"
            visibility = android.view.View.GONE
            setOnClickListener {
                val saved = (Prefs.get(this@MainActivity).getString("admin_pin", DEFAULT_PIN) ?: DEFAULT_PIN).trim()
                val typed = pin.text.toString().filter { it.isDigit() }
                if (typed == saved || typed == DEFAULT_PIN && saved.isEmpty()) showSetup()
                else Toast.makeText(this@MainActivity, "PIN si sahihi", Toast.LENGTH_SHORT).show()
            }
        }
        title.setOnClickListener {
            val now = System.currentTimeMillis()
            tapCount = if (now - lastTap < 1500) tapCount + 1 else 1
            lastTap = now
            if (tapCount >= 5) {
                pin.visibility = android.view.View.VISIBLE
                open.visibility = android.view.View.VISIBLE
                pin.requestFocus()
            }
        }
        root.addView(pin)
        root.addView(open)
        setContentView(root)
    }

    override fun onBackPressed() {
        if (!Prefs.get(this).getString("device_label", "").isNullOrEmpty()) { showLock(); moveTaskToBack(true) }
        else super.onBackPressed()
    }

    private fun showSetup() {
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 72, 48, 48)
            }

            val title = TextView(this).apply {
                text = "DarLusa Monitor"
                textSize = 24f
                setPadding(0, 0, 0, 32)
            }
            root.addView(title)

            val info = TextView(this).apply {
                text = "Simu hii itatuma kila siku: muda wa kuingia Kariakoo, " +
                        "muda na data za WhatsApp/TikTok/Snapchat/Instagram/YouTube, na matumizi ya Hotspot."
                setPadding(0, 0, 0, 32)
            }
            root.addView(info)

            val labelRow = TextView(this).apply { text = "Jina la mfanyakazi (device label):" }
            root.addView(labelRow)

            deviceLabelInput = EditText(this).apply {
                setText(Prefs.get(this@MainActivity).getString("device_label", "") ?: "")
                hint = "mfano: Michael"
            }
            root.addView(deviceLabelInput)

            statusView = TextView(this).apply {
                setPadding(0, 32, 0, 32)
            }
            root.addView(statusView)

            val perm = Button(this).apply {
                text = "1) Toa ruhusa (Usage Access)"
                setOnClickListener { openUsageAccess() }
            }
            root.addView(perm)

            val locPerm = Button(this).apply {
                text = "2) Toa ruhusa ya Location"
                setOnClickListener { requestLocation() }
            }
            root.addView(locPerm)

            val save = Button(this).apply {
                text = "3) Hifadhi & Anza"
                setOnClickListener {
                    val label = deviceLabelInput.text.toString().trim()
                    if (label.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Weka jina kwanza", Toast.LENGTH_SHORT).show()
                    } else {
                        Prefs.get(this@MainActivity).edit().putString("device_label", label).apply()
                        scheduleWork()
                        runOnceNow()
                        refreshStatus()
                        Toast.makeText(this@MainActivity, "Imeanzishwa", Toast.LENGTH_SHORT).show(); showLock()
                    }
                }
            }
            root.addView(save)

            val runNow = Button(this).apply {
                text = "Tuma sasa (test)"
                setOnClickListener {
                    runOnceNow()
                    Toast.makeText(this@MainActivity, "Imetumwa, angalia dashibodi", Toast.LENGTH_SHORT).show()
                }
            }
            root.addView(runNow)

            val newPin = EditText(this).apply {
                hint = "Badilisha Admin PIN (tarakimu 4-8)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            root.addView(newPin)
            root.addView(Button(this).apply {
                text = "Hifadhi PIN mpya"
                setOnClickListener {
                    val p = newPin.text.toString().filter { it.isDigit() }
                    if (p.length in 4..8) {
                        Prefs.get(this@MainActivity).edit().putString("admin_pin", p).apply()
                        Toast.makeText(this@MainActivity, "PIN imebadilishwa", Toast.LENGTH_SHORT).show()
                        newPin.setText("")
                    } else Toast.makeText(this@MainActivity, "Weka tarakimu 4 hadi 8", Toast.LENGTH_SHORT).show()
                }
            })
            root.addView(Button(this).apply {
                text = "Funga (Lock)"
                setOnClickListener { showLock() }
            })

            setContentView(root)
            refreshStatus()
        } catch (t: Throwable) {
            t.printStackTrace()
            Toast.makeText(this, "Hitilafu: " + t.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
    }

    override fun onStop() {
        super.onStop()
        // Relock whenever the app leaves the screen
        if (!Prefs.get(this).getString("device_label", "").isNullOrEmpty() && !isChangingConfigurations) showLock()
    }

    private fun refreshStatus() {
        try {
            val usage = if (hasUsageAccess()) "✅" else "❌"
            val loc = if (hasLocation()) "✅" else "❌"
            val label = Prefs.get(this).getString("device_label", "") ?: ""
            statusView.text = "Usage Access: $usage\nLocation: $loc\nLabel: $label"
        } catch (_: Throwable) {}
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow("android:get_usage_stats", Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasLocation(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Throwable) {
                Toast.makeText(this, "Fungua Settings -> Apps -> Special App Access -> Usage Access", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestLocation() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        ActivityCompat.requestPermissions(this, perms, 100)
    }

    private fun scheduleWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<MetricsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "darlusa_metrics_v2", ExistingPeriodicWorkPolicy.KEEP, req
            )
            WorkManager.getInstance(this).cancelUniqueWork("darlusa_metrics")
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun runOnceNow() {
        try {
            val one = OneTimeWorkRequestBuilder<MetricsWorker>().build()
            WorkManager.getInstance(this).enqueue(one)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}

const val DEFAULT_PIN = "2026"

object Prefs {
    fun get(ctx: Context) = ctx.getSharedPreferences("darlusa_monitor", Context.MODE_PRIVATE)
}
