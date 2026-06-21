package com.overlaydraw.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val penColors = listOf(
        "#2B6E63", // 초록 (기본)
        "#C2543F", // 코랄
        "#D98E3A", // 앰버
        "#1C2B2A", // 잉크(거의 검정)
        "#F6F1E7", // 종이색(흰색 계열)
        "#3A5FC2"  // 파랑
    )
    private var selectedColor: Int = Color.parseColor(penColors[0])
    private var selectedWidth: Float = 6f
    private var backgroundOpacity: Int = 100

    private lateinit var permissionStatus: TextView
    private lateinit var btnToggleOverlay: Button
    private var overlayRunning = false

    // 오버레이의 "불러오기"가 이 화면을 띄웠는지 여부
    private var pickingForOverlay = false

    private val PICK_IMAGE_REQUEST_CODE = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionStatus = findViewById(R.id.permissionStatus)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)

        setupPermissionButton()
        setupOpacitySlider()
        setupColorRow()
        setupWidthSlider()
        setupStartStopButton()
        refreshPermissionStatus()
        requestLegacyStoragePermissionIfNeeded()

        handlePickIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePickIntent(intent)
    }

    /** 오버레이의 불러오기 버튼이 이 화면을 띄웠다면, 곧바로 사진 선택을 연다. */
    private fun handlePickIntent(intent: Intent?) {
        if (intent?.action == OverlayService.ACTION_PICK_IMAGE) {
            pickingForOverlay = true
            val pickIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(pickIntent, PICK_IMAGE_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST_CODE) {
            val uri: Uri? = data?.data
            if (uri != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_IMPORT_IMAGE
                    putExtra(OverlayService.EXTRA_IMPORT_URI, uri.toString())
                }
                startService(intent)
            }
            if (pickingForOverlay) {
                pickingForOverlay = false
                // 사진을 골랐든 취소했든, 오버레이로 돌아가게 화면을 내림
                moveTaskToBack(true)
            }
        }
    }

    /** Android 9(API 28) 이하에서만 PNG 저장에 필요한 저장소 권한을 요청한다.
     * Android 10 이상은 MediaStore + scoped storage라 별도 권한이 필요 없다. */
    private fun requestLegacyStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1001
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun refreshPermissionStatus() {
        if (hasOverlayPermission()) {
            permissionStatus.text = "① 권한이 허용되어 있어요 ✓"
            permissionStatus.setTextColor(Color.parseColor("#2B6E63"))
        } else {
            permissionStatus.text = "① 다른 앱 위에 표시 권한이 필요해요"
            permissionStatus.setTextColor(Color.parseColor("#C2543F"))
        }
    }

    private fun setupPermissionButton() {
        findViewById<Button>(R.id.btnGrantPermission).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun setupOpacitySlider() {
        val seek = findViewById<SeekBar>(R.id.seekBackgroundOpacity)
        val label = findViewById<TextView>(R.id.txtOpacityValue)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                backgroundOpacity = value
                label.text = "$value%"
                if (overlayRunning) sendUpdateToService()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupColorRow() {
        val row = findViewById<LinearLayout>(R.id.colorRow)
        val swatchViews = mutableListOf<android.view.View>()

        penColors.forEachIndexed { index, hex ->
            val swatch = android.view.View(this)
            val size = dp(34)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginEnd = dp(12)
            swatch.layoutParams = params

            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(Color.parseColor(hex))
            drawable.setStroke(
                if (index == 0) dp(3) else dp(1),
                if (index == 0) Color.parseColor("#F6F1E7") else Color.parseColor("#55F6F1E7")
            )
            swatch.background = drawable

            swatch.setOnClickListener {
                selectedColor = Color.parseColor(hex)
                swatchViews.forEachIndexed { i, v ->
                    val d = v.background as GradientDrawable
                    d.setStroke(
                        if (i == index) dp(3) else dp(1),
                        if (i == index) Color.parseColor("#F6F1E7") else Color.parseColor("#55F6F1E7")
                    )
                }
                if (overlayRunning) sendUpdateToService()
            }

            swatchViews.add(swatch)
            row.addView(swatch)
        }
    }

    private fun setupWidthSlider() {
        val seek = findViewById<SeekBar>(R.id.seekStrokeWidth)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                selectedWidth = (value + 2).toFloat() // 최소 굵기 2dp 보장
                if (overlayRunning) sendUpdateToService()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupStartStopButton() {
        btnToggleOverlay.setOnClickListener {
            if (!overlayRunning) {
                if (!hasOverlayPermission()) {
                    permissionStatus.text = "① 먼저 권한을 허용해주세요"
                    permissionStatus.setTextColor(Color.parseColor("#C2543F"))
                    return@setOnClickListener
                }
                startOverlay()
            } else {
                stopOverlay()
            }
        }
    }

    private fun startOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_BG_OPACITY, backgroundOpacity)
            putExtra(OverlayService.EXTRA_PEN_COLOR, selectedColor)
            putExtra(OverlayService.EXTRA_PEN_WIDTH, selectedWidth)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        overlayRunning = true
        btnToggleOverlay.text = "화면 위 그리기 중지"
        btnToggleOverlay.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#C2543F"))
        moveTaskToBack(true)
    }

    private fun stopOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
        overlayRunning = false
        btnToggleOverlay.text = "화면 위에 그리기 시작"
        btnToggleOverlay.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#2B6E63"))
    }

    private fun sendUpdateToService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_BG_OPACITY, backgroundOpacity)
            putExtra(OverlayService.EXTRA_PEN_COLOR, selectedColor)
            putExtra(OverlayService.EXTRA_PEN_WIDTH, selectedWidth)
        }
        startService(intent)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
