package com.overlaydraw.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 화면 전체에 떠 있는 투명 캔버스.
 *
 * 한 번의 획(stroke)은 점들의 연속으로 저장한다. 테이퍼(끝/시작을 가늘게)를
 * 표현하려면 선 전체를 하나의 Path로 그릴 수 없고, 작은 구간마다 굵기를 바꿔
 * 여러 개의 짧은 선분으로 그려야 한다. 그래서 Path 대신 점 목록을 저장한다.
 *
 * - penColor / penWidth : 현재 펜 색/최대 굵기
 * - taperAmount        : 끝과 시작을 가늘게 하는 강도 (0~1, 날카로운 붓 느낌)
 * - eraserMode          : 지우개 (이미 그린 선을 투명하게 지움)
 * - backgroundBitmap    : 불러온 PNG (이 위에 덧그림). 합쳐서 저장할 때 함께 합성된다.
 */
class DrawingOverlayView(context: Context) : View(context) {

    /** 한 획: 점들 + 색 + 최대 굵기 + 테이퍼 강도(0~1) + 지우개 여부 */
    private class Stroke(
        val color: Int,
        val maxWidth: Float,
        val taperAmount: Float,
        val eraser: Boolean
    ) {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
    }

    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()
    private var active: Stroke? = null

    var penColor: Int = Color.parseColor("#2B6E63")
    var penWidth: Float = 6f
    var taperAmount: Float = 0f   // 0 = 균일한 선, 1 = 양 끝이 완전히 뾰족
    var eraserMode: Boolean = false

    /** false면 터치를 아래 앱으로 흘려보낸다(스크롤 모드). */
    var drawingEnabled: Boolean = true

    /** 불러온 배경 그림(PNG). 화면 좌상단 기준으로 그린다. */
    var backgroundBitmap: Bitmap? = null

    private val linePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val eraserXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    init {
        // PorterDuff.CLEAR(지우개)가 화면에 정확히 반영되려면 소프트웨어 레이어가 필요
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!drawingEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = Stroke(penColor, penWidth, taperAmount, eraserMode)
                s.xs.add(event.x); s.ys.add(event.y)
                active = s
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = active ?: return true
                s.xs.add(event.x); s.ys.add(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active?.let {
                    strokes.add(it)
                    redoStack.clear()
                }
                active = null
                invalidate()
                return true
            }
        }
        return false
    }

    private var strokeLayer: Bitmap? = null
    private var strokeCanvas: Canvas? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            strokeLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            strokeCanvas = Canvas(strokeLayer!!)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 1) 배경 PNG (지우개 영향 없음)
        backgroundBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 2) 선들을 별도 레이어에 새로 그린다 (지우개 CLEAR는 이 레이어 안에서만 작동)
        val layer = strokeLayer
        val lc = strokeCanvas
        if (layer != null && lc != null) {
            layer.eraseColor(Color.TRANSPARENT)
            for (s in strokes) drawStroke(lc, s)
            active?.let { drawStroke(lc, it) }
            canvas.drawBitmap(layer, 0f, 0f, null)
        } else {
            // 레이어가 아직 없으면(초기) 직접 그린다
            for (s in strokes) drawStroke(canvas, s)
            active?.let { drawStroke(canvas, it) }
        }
    }

    /** 하나의 획을 캔버스에 그린다. 테이퍼면 구간마다 굵기를 조절한다. */
    private fun drawStroke(canvas: Canvas, s: Stroke) {
        val n = s.xs.size
        if (n == 0) return

        // 점 하나뿐이면 동그란 점 하나
        if (n == 1) {
            if (s.eraser) return // 지우개로 점만 찍은 건 무시
            linePaint.xfermode = null
            linePaint.color = s.color
            linePaint.strokeWidth = s.maxWidth
            canvas.drawPoint(s.xs[0], s.ys[0], linePaint)
            return
        }

        if (s.eraser) {
            linePaint.xfermode = eraserXfermode
            linePaint.color = Color.BLACK // CLEAR 모드라 색은 무의미
            linePaint.strokeWidth = s.maxWidth
            val path = Path()
            path.moveTo(s.xs[0], s.ys[0])
            for (i in 1 until n) {
                val mx = (s.xs[i - 1] + s.xs[i]) / 2
                val my = (s.ys[i - 1] + s.ys[i]) / 2
                path.quadTo(s.xs[i - 1], s.ys[i - 1], mx, my)
            }
            canvas.drawPath(path, linePaint)
            linePaint.xfermode = null
            return
        }

        linePaint.xfermode = null
        linePaint.color = s.color

        if (s.taperAmount <= 0.01f) {
            // 테이퍼 없음(균일한 선): 부드러운 곡선 하나로
            linePaint.strokeWidth = s.maxWidth
            val path = Path()
            path.moveTo(s.xs[0], s.ys[0])
            for (i in 1 until n) {
                val mx = (s.xs[i - 1] + s.xs[i]) / 2
                val my = (s.ys[i - 1] + s.ys[i]) / 2
                path.quadTo(s.xs[i - 1], s.ys[i - 1], mx, my)
            }
            canvas.drawPath(path, linePaint)
            return
        }

        // 테이퍼 선: 누적 길이를 따라 양 끝 굵기를 줄인다. 짧은 선분 여러 개로 그린다.
        // taperAmount(0~1)가 클수록 끝이 더 가늘어진다.
        val cum = FloatArray(n)
        for (i in 1 until n) {
            cum[i] = cum[i - 1] + hypot(s.xs[i] - s.xs[i - 1], s.ys[i] - s.ys[i - 1])
        }
        val total = cum[n - 1]
        if (total <= 0f) {
            linePaint.strokeWidth = s.maxWidth
            canvas.drawPoint(s.xs[0], s.ys[0], linePaint)
            return
        }
        // 끝부분 최소 굵기: taperAmount가 1이면 거의 0, 0이면 maxWidth
        val endWidth = s.maxWidth * (1f - s.taperAmount)
        for (i in 1 until n) {
            val t = cum[i] / total              // 0~1 위치
            // 양 끝(0, 1)에서 0, 가운데(0.5)에서 1 이 되는 종 모양
            val taperFactor = 1f - kotlin.math.abs(0.5f - t) * 2f
            // endWidth ~ maxWidth 사이를 종 모양으로 보간
            val w = (endWidth + (s.maxWidth - endWidth) * taperFactor).coerceAtLeast(0.5f)
            linePaint.strokeWidth = w
            canvas.drawLine(s.xs[i - 1], s.ys[i - 1], s.xs[i], s.ys[i], linePaint)
        }
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            redoStack.add(strokes.removeAt(strokes.size - 1))
            invalidate()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            strokes.add(redoStack.removeAt(redoStack.size - 1))
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear()
        redoStack.clear()
        active = null
        backgroundBitmap = null   // 불러온 배경 그림도 함께 지움
        invalidate()
    }

    fun hasContent(): Boolean = strokes.isNotEmpty()

    fun hasBackground(): Boolean = backgroundBitmap != null

    /** 선들을 지우개까지 반영해 투명 배경 비트맵에 그린다. (공통 헬퍼) */
    private fun renderStrokesToBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (s in strokes) drawStroke(c, s)
        return bmp
    }

    /**
     * 그린 선만(배경 PNG 제외, 완전 투명 배경) 비트맵으로 만든다. "분리 저장"용.
     */
    fun exportStrokesOnly(): Bitmap? {
        val w = width; val h = height
        if (w <= 0 || h <= 0 || strokes.isEmpty()) return null
        return renderStrokesToBitmap(w, h)
    }

    /**
     * 배경 PNG + 그린 선을 합성한 비트맵을 만든다. "합쳐서 저장"용.
     */
    fun exportMerged(): Bitmap? {
        val w = width; val h = height
        if (w <= 0 || h <= 0) return null
        if (strokes.isEmpty() && backgroundBitmap == null) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        backgroundBitmap?.let { c.drawBitmap(it, 0f, 0f, null) }
        val strokeLayerBmp = renderStrokesToBitmap(w, h)
        c.drawBitmap(strokeLayerBmp, 0f, 0f, null)
        return bmp
    }
}
