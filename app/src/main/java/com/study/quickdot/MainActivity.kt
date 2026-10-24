package com.study.quickdot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var cfg: ConfigStore.Config

    private lateinit var spProfile: Spinner
    private lateinit var spModel: Spinner
    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etNewModel: EditText
    private lateinit var rgMode: RadioGroup
    private lateinit var etCustomTokens: EditText
    private lateinit var tvStatus: TextView

    /** 重绘 Spinner 时挡住 onItemSelected，避免把选择过程误当成用户操作 */
    private var suppressSpinner = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("quickdot", MODE_PRIVATE)
        cfg = ConfigStore.load(prefs)

        spProfile = findViewById(R.id.spProfile)
        spModel = findViewById(R.id.spModel)
        etBase = findViewById(R.id.etBaseUrl)
        etKey = findViewById(R.id.etApiKey)
        etNewModel = findViewById(R.id.etNewModel)
        rgMode = findViewById(R.id.rgMode)
        etCustomTokens = findViewById(R.id.etCustomTokens)
        tvStatus = findViewById(R.id.tvStatus)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }

        renderProfileSpinner()
        renderModelSpinner()
        loadProfileToEdits()
        renderMode()

        spProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                saveCurrentProfileEdits()   // 切走前把编辑框内容存回原档案
                cfg.profileIndex = pos
                loadProfileToEdits()
                renderModelSpinner()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressSpinner) return
                val p = ConfigStore.current(cfg)
                if (pos in p.models.indices) cfg.model = p.models[pos]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            etCustomTokens.isEnabled = checkedId == R.id.rbCustom
        }

        findViewById<Button>(R.id.btnAddProfile).setOnClickListener {
            saveCurrentProfileEdits()
            cfg.profiles.add(ConfigStore.Profile("", "", mutableListOf()))
            cfg.profileIndex = cfg.profiles.size - 1
            renderProfileSpinner()
            loadProfileToEdits()
            renderModelSpinner()
            Toast.makeText(this, "已新增接口，填好地址和 Key 后点保存", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnDelProfile).setOnClickListener {
            if (cfg.profiles.size <= 1) {
                Toast.makeText(this, "至少保留一个接口", Toast.LENGTH_SHORT).show()
            } else {
                cfg.profiles.removeAt(cfg.profileIndex.coerceIn(0, cfg.profiles.size - 1))
                cfg.profileIndex = 0
                cfg.model = ConfigStore.current(cfg).models.firstOrNull() ?: ""
                renderProfileSpinner()
                loadProfileToEdits()
                renderModelSpinner()
            }
        }
        findViewById<Button>(R.id.btnAddModel).setOnClickListener {
            val m = etNewModel.text.toString().trim()
            val p = ConfigStore.current(cfg)
            when {
                m.isEmpty() -> Toast.makeText(this, "先在输入框填模型名", Toast.LENGTH_SHORT).show()
                p.models.contains(m) -> Toast.makeText(this, "该模型已存在", Toast.LENGTH_SHORT).show()
                else -> {
                    p.models.add(m)
                    cfg.model = m
                    renderModelSpinner()
                    etNewModel.setText("")
                    Toast.makeText(this, "已添加并选用 $m", Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnDelModel).setOnClickListener {
            val p = ConfigStore.current(cfg)
            if (p.models.size <= 1) {
                Toast.makeText(this, "至少保留一个模型", Toast.LENGTH_SHORT).show()
            } else {
                p.models.remove(cfg.model)
                cfg.model = p.models.first()
                renderModelSpinner()
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveCurrentProfileEdits()
            cfg.tokenMode = when (rgMode.checkedRadioButtonId) {
                R.id.rbLow -> "low"
                R.id.rbMax -> "max"
                R.id.rbCustom -> "custom"
                else -> "high"
            }
            cfg.customTokens = etCustomTokens.text.toString().trim().toLongOrNull()
                ?.coerceIn(64L, 32000L) ?: 2000L
            if (cfg.model.isBlank() && ConfigStore.current(cfg).models.isNotEmpty()) {
                cfg.model = ConfigStore.current(cfg).models.first()
            }
            ConfigStore.save(prefs, cfg)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val base = etBase.text.toString().trim()
            val key = etKey.text.toString().trim()
            val model = cfg.model
            if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
                tvStatus.text = "请先填全：接口地址 / API Key / 模型"
                return@setOnClickListener
            }
            tvStatus.text = "测试中…"
            ApiClient.test(base, key, model) { ok, msg ->
                runOnUiThread {
                    tvStatus.text = if (ok) "连接成功，模型返回：$msg" else "失败：$msg"
                }
            }
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (OverlayService.isRunning) {
                Toast.makeText(this, "悬浮球已在运行", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先允许「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return@setOnClickListener
            }
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(pm.createScreenCaptureIntent(), 20)
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            tvStatus.text = "状态：已停止"
        }

        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20) return
        if (resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_CODE, resultCode)
                .putExtra(OverlayService.EXTRA_DATA, data)
            startForegroundService(i)
            tvStatus.text = "状态：悬浮球已启动，切到题目界面双击圆点识屏"
        } else {
            tvStatus.text = "状态：未授权屏幕录制，无法识屏"
        }
    }

    private fun renderProfileSpinner() {
        suppressSpinner = true
        val labels = cfg.profiles.mapIndexed { i, p ->
            val host = p.baseUrl.trim()
                .removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
            "接口${i + 1} · ${host.ifEmpty { "未填写" }}"
        }
        spProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spProfile.setSelection(cfg.profileIndex.coerceIn(0, labels.size - 1), false)
        suppressSpinner = false
    }

    private fun renderModelSpinner() {
        suppressSpinner = true
        val p = ConfigStore.current(cfg)
        spModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, p.models).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val idx = p.models.indexOf(cfg.model)
        if (idx >= 0) spModel.setSelection(idx, false)
        suppressSpinner = false
    }

    private fun loadProfileToEdits() {
        val p = ConfigStore.current(cfg)
        etBase.setText(p.baseUrl)
        etKey.setText(p.apiKey)
    }

    private fun saveCurrentProfileEdits() {
        val p = ConfigStore.current(cfg)
        p.baseUrl = etBase.text.toString().trim()
        p.apiKey = etKey.text.toString().trim()
    }

    private fun renderMode() {
        val id = when (cfg.tokenMode) {
            "low" -> R.id.rbLow
            "max" -> R.id.rbMax
            "custom" -> R.id.rbCustom
            else -> R.id.rbHigh
        }
        rgMode.check(id)
        etCustomTokens.setText(cfg.customTokens.toString())
        etCustomTokens.isEnabled = cfg.tokenMode == "custom"
    }
}
