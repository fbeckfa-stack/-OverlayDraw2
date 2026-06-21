package com.overlaydraw.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 화면 위에 떠 있는 투명 캔버스(DrawingOverlayView)와 플로팅 컨트롤 바를 관리하는 서비스.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var drawingView: DrawingOverlayView
    private lateinit var dimView: View
    private lateinit var controlBar: LinearLayout

    private var drawingParams: WindowManager.LayoutParams? = null
    private var controlParams: WindowManager.LayoutParams? = null

    // 토글 가능한 버튼들(선택 상태 배경 갱신용)
    private var btnModeDraw: ImageButton? = null
    private var btnModeScroll: ImageButton? = null
    private var btnEraser: ImageButton? = null
    private var palettePanel: View? = null
    private var sizePreview: View? = null
    private var swatchRow: LinearLayout? = null
    private val swatchViews = ArrayList<View>()

    // 저장 선택 다이얼로그(오버레이) 보관
    private var saveDialog: View? = null

    private var isDrawMode = true
    private var currentPenWidth = 6f
    private val minPenWidth = 1f
    private val maxPenWidth = 40f
    private var currentPenColor = Color.parseColor("#2B6E63")

    private val palette = listOf(
        "#2B6E63", "#C2543F", "#D98E3A", "#1C2B2A", "#F6F1E7", "#3A5FC2", "#8E44AD", "#E84393"
    )

    companion object {
        const val CHANNEL_ID = "overlay_draw_channel"
        const val NOTIF_ID = 1

        const val EXTRA_BG_OPACITY = "extra_bg_opacity"
        const val EXTRA_PEN_COLOR = "extra_pen_color"
        const val EXTRA_PEN_WIDTH = "extra_pen_width"
        const val EXTRA_IMPORT_URI = "extra_import_uri"

        const val ACTION_STOP = "action_stop"
        const val ACTION_IMPORT_IMAGE = "action_import_image"

        // OverlayService -> MainActivity (사진 선택 요청)
        const val ACTION_PICK_IMAGE = "com.overlaydraw.app.PICK_IMAGE"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        buildDimLayer()
        buildDrawingLayer()
        buildControlBar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_STOP -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_IMPORT_IMAGE -> {
                    val uriStr = it.getStringExtra(EXTRA_IMPORT_URI)
                    if (uriStr != null) loadBackgroundImage(Uri.parse(uriStr))
                    return START_STICKY
                }
                else -> {
                    val opacity = it.getIntExtra(EXTRA_BG_OPACITY, 100)
                    applyBackgroundOpacity(opacity)
                    val color = it.getIntExtra(EXTRA_PEN_COLOR, currentPenColor)
                    val width = it.getFloatExtra(EXTRA_PEN_WIDTH, currentPenWidth)
                    currentPenColor = color
                    currentPenWidth = width
                    drawingView.penColor = color
                    drawingView.penWidth = width
                    refreshSwatchSelection()
                    updateSizePreview()
                }
            }
        }
        return START_STICKY
    }

    // ---------- 알림 ----------

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "오버레이 드로우 실행 중",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("오버레이 드로우 실행 중")
            .setContentText("여기를 누르면 그리기를 종료합니다")
            .setSmallIcon(R.drawable.ic_pen)
            .setContentIntent(stopPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "종료", stopPending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- 배경 막(투명도) ----------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun buildDimLayer() {
        dimView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(dimView, params)
    }

    private fun applyBackgroundOpacity(opacityPercent: Int) {
        dimView.alpha = ((100 - opacityPercent) / 100f).coerceIn(0f, 1f)
    }

    // ---------- 드로잉 캔버스 ----------

    private fun buildDrawingLayer() {
        drawingView = DrawingOverlayView(this)
        drawingView.penColor = currentPenColor
        drawingView.penWidth = currentPenWidth
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        drawingParams = params
        windowManager.addView(drawingView, params)
        // 초기 상태는 필기 모드. (컨트롤 바가 아직 없으므로 재추가 로직은 쓰지 않음)
        isDrawMode = true
        drawingView.drawingEnabled = true
    }

    /** 필기 모드 ON: 캔버스가 터치를 받음 / OFF(스크롤): 터치를 아래 앱으로 통과 */
    private fun setDrawMode(enabled: Boolean) {
        isDrawMode = enabled
        drawingView.drawingEnabled = enabled
        val params = drawingParams ?: return
        params.flags = if (enabled) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            // 스크롤 모드: 캔버스가 터치를 가로채지 않고 아래 앱으로 모두 통과시킨다
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        // 일부 기기는 updateViewLayout으로 FLAG_NOT_TOUCHABLE 변경을 즉시 반영하지 않으므로
        // 뷰를 제거했다가 다시 추가해 플래그를 확실히 적용한다.
        runCatching {
            windowManager.removeViewImmediate(drawingView)
            windowManager.addView(drawingView, params)
        }.onFailure {
            // 재추가에 실패하면 최소한 레이아웃 갱신이라도 시도
            runCatching { windowManager.updateViewLayout(drawingView, params) }
        }
        // 캔버스를 다시 추가하면 컨트롤 바보다 아래로 내려갈 수 있으니,
        // 컨트롤 바를 다시 맨 위로 올린다.
        bringControlBarToFront()
    }

    /** 컨트롤 바를 다른 오버레이보다 위에 다시 올린다(항상 누를 수 있도록). */
    private fun bringControlBarToFront() {
        val cp = controlParams ?: return
        if (!::controlBar.isInitialized) return
        runCatching {
            windowManager.removeViewImmediate(controlBar)
            windowManager.addView(controlBar, cp)
        }
    }

    // ---------- 컨트롤 바 ----------

    private fun buildControlBar() {
        val inflater = LayoutInflater.from(this)
        controlBar = inflater.inflate(R.layout.overlay_control_bar, null) as LinearLayout

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 24
        params.y = 120
        controlParams = params

        // grip 드래그로 이동
        val grip = controlBar.findViewById<ImageView>(R.id.grip)
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        grip.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(controlBar, params); true
                }
                else -> false
            }
        }

        palettePanel = controlBar.findViewById(R.id.palettePanel)
        swatchRow = controlBar.findViewById(R.id.swatchRow)
        sizePreview = controlBar.findViewById(R.id.sizePreview)
        btnModeDraw = controlBar.findViewById(R.id.btnModeDraw)
        btnModeScroll = controlBar.findViewById(R.id.btnModeScroll)
        btnEraser = controlBar.findViewById(R.id.btnEraser)

        val btnPalette = controlBar.findViewById<ImageButton>(R.id.btnPalette)
        val btnUndo = controlBar.findViewById<ImageButton>(R.id.btnUndo)
        val btnRedo = controlBar.findViewById<ImageButton>(R.id.btnRedo)
        val btnClear = controlBar.findViewById<ImageButton>(R.id.btnClear)
        val btnImport = controlBar.findViewById<ImageButton>(R.id.btnImport)
        val btnSave = controlBar.findViewById<ImageButton>(R.id.btnSave)
        val btnClose = controlBar.findViewById<ImageButton>(R.id.btnClose)
        val widthSeek = controlBar.findViewById<SeekBar>(R.id.widthSeek)
        val taperSeek = controlBar.findViewById<SeekBar>(R.id.taperSeek)
        val taperValue = controlBar.findViewById<TextView>(R.id.taperValue)

        // 색상 스와치 생성
        buildSwatches()

        // 굵기 슬라이더 (그리는 중에도 조절 가능)
        widthSeek.progress = currentPenWidth.toInt().coerceIn(1, 40)
        widthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                currentPenWidth = value.toFloat().coerceIn(minPenWidth, maxPenWidth)
                drawingView.penWidth = currentPenWidth
                updateSizePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 테이퍼(선 끝 가늘기) 슬라이더 — 0~100%
        taperSeek.progress = (drawingView.taperAmount * 100).toInt()
        taperValue.text = "${(drawingView.taperAmount * 100).toInt()}%"
        taperSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                drawingView.taperAmount = value / 100f
                taperValue.text = "$value%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 팔레트 펼치기/접기
        btnPalette.setOnClickListener {
            val p = palettePanel ?: return@setOnClickListener
            p.visibility = if (p.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        btnModeDraw?.setOnClickListener { setDrawMode(true); refreshToggleButtons() }
        btnModeScroll?.setOnClickListener { setDrawMode(false); refreshToggleButtons() }

        btnEraser?.setOnClickListener {
            drawingView.eraserMode = !drawingView.eraserMode
            refreshToggleButtons()
        }

        btnUndo.setOnClickListener { drawingView.undo() }
        btnRedo.setOnClickListener { drawingView.redo() }
        btnClear.setOnClickListener { drawingView.clearAll() }
        btnImport.setOnClickListener { requestImagePick() }
        btnSave.setOnClickListener { showSaveDialog() }
        btnClose.setOnClickListener { stopSelf() }

        refreshToggleButtons()
        updateSizePreview()
        windowManager.addView(controlBar, params)
    }

    private fun buildSwatches() {
        val row = swatchRow ?: return
        row.removeAllViews()
        swatchViews.clear()
        val density = resources.displayMetrics.density
        val size = (30 * density).toInt()
        val margin = (8 * density).toInt()
        palette.forEachIndexed { index, hex ->
            val v = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            v.layoutParams = lp
            v.background = swatchDrawable(Color.parseColor(hex), Color.parseColor(hex) == currentPenColor)
            v.setOnClickListener {
                currentPenColor = Color.parseColor(hex)
                drawingView.penColor = currentPenColor
                // 색을 고르면 지우개는 자동 해제
                if (drawingView.eraserMode) {
                    drawingView.eraserMode = false
                    refreshToggleButtons()
                }
                refreshSwatchSelection()
                updateSizePreview()
            }
            swatchViews.add(v)
            row.addView(v)
        }
    }

    private fun swatchDrawable(color: Int, selected: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                ((if (selected) 3 else 1) * density).toInt(),
                if (selected) Color.parseColor("#F6F1E7") else Color.parseColor("#55F6F1E7")
            )
        }
    }

    private fun refreshSwatchSelection() {
        palette.forEachIndexed { i, hex ->
            if (i < swatchViews.size) {
                swatchViews[i].background =
                    swatchDrawable(Color.parseColor(hex), Color.parseColor(hex) == currentPenColor)
            }
        }
    }

    private fun refreshToggleButtons() {
        btnModeDraw?.setBackgroundResource(
            if (isDrawMode) R.drawable.mode_btn_active else R.drawable.mode_btn_inactive
        )
        btnModeScroll?.setBackgroundResource(
            if (!isDrawMode) R.drawable.mode_btn_active else R.drawable.mode_btn_inactive
        )
        btnEraser?.setBackgroundResource(
            if (drawingView.eraserMode) R.drawable.mode_btn_active else R.drawable.mode_btn_inactive
        )
    }

    private fun updateSizePreview() {
        val preview = sizePreview ?: return
        val density = resources.displayMetrics.density
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(currentPenColor)
        }
        preview.background = dot
        val container = (24 * density).toInt()
        val dotPx = (currentPenWidth * density / 1.8f).toInt().coerceIn((4 * density).toInt(), container)
        val pad = ((container - dotPx) / 2).coerceAtLeast(0)
        preview.setPadding(pad, pad, pad, pad)
    }

    // ---------- PNG 불러오기 ----------

    /** 오버레이에서는 직접 갤러리를 못 열어, MainActivity를 띄워 사진을 고르게 한다. */
    private fun requestImagePick() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_PICK_IMAGE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        Toast.makeText(this, "불러올 그림을 선택하세요", Toast.LENGTH_SHORT).show()
    }

    private fun loadBackgroundImage(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri)
            val bmp = BitmapFactory.decodeStream(input)
            input?.close()
            if (bmp == null) {
                Toast.makeText(this, "그림을 불러오지 못했어요", Toast.LENGTH_SHORT).show()
                return
            }
            // 화면 크기에 맞춰 비율 유지하며 스케일
            val vw = drawingView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val vh = drawingView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            val scale = minOf(vw.toFloat() / bmp.width, vh.toFloat() / bmp.height)
            val scaled = if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            else bmp
            drawingView.backgroundBitmap = scaled
            drawingView.invalidate()
            Toast.makeText(this, "그림을 불러왔어요. 위에 덧그릴 수 있어요", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "불러오기 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 저장 (분리 / 합침 선택) ----------

    private fun showSaveDialog() {
        if (!drawingView.hasContent() && !drawingView.hasBackground()) {
            Toast.makeText(this, "저장할 내용이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        // 이미 떠 있으면 중복 방지
        dismissSaveDialog()

        val dialog = LayoutInflater.from(this).inflate(R.layout.overlay_save_dialog, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        val btnMerged = dialog.findViewById<Button>(R.id.btnSaveMerged)
        val btnSeparate = dialog.findViewById<Button>(R.id.btnSaveSeparate)
        val btnCancel = dialog.findViewById<Button>(R.id.btnSaveCancel)
        val info = dialog.findViewById<TextView>(R.id.saveInfo)

        // 배경이 없으면 "합침"은 사실상 선만 저장이므로 안내만 조정
        if (!drawingView.hasBackground()) {
            info.text = "불러온 배경이 없어, 그린 선만 저장됩니다."
        }

        btnMerged.setOnClickListener {
            val bmp = drawingView.exportMerged()
            savePng(bmp, "merged")
            dismissSaveDialog()
        }
        btnSeparate.setOnClickListener {
            val bmp = drawingView.exportStrokesOnly()
            savePng(bmp, "strokes")
            dismissSaveDialog()
        }
        btnCancel.setOnClickListener { dismissSaveDialog() }

        saveDialog = dialog
        windowManager.addView(dialog, params)
    }

    private fun dismissSaveDialog() {
        saveDialog?.let { runCatching { windowManager.removeView(it) } }
        saveDialog = null
    }

    private fun savePng(bitmap: Bitmap?, suffix: String) {
        if (bitmap == null) {
            Toast.makeText(this, "저장할 내용이 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "overlaydraw_${suffix}_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".png"
        try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OverlayDraw")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "저장에 실패했어요", Toast.LENGTH_SHORT).show()
                return
            }
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Toast.makeText(this, "사진의 OverlayDraw 폴더에 저장했어요", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "저장 오류: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissSaveDialog()
        runCatching { windowManager.removeView(drawingView) }
        runCatching { windowManager.removeView(dimView) }
        runCatching { windowManager.removeView(controlBar) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
