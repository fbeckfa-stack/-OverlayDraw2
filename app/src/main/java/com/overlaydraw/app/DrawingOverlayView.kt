package com.overlaydraw.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
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

    /** 그리기 영역(이 사각형 안에서만 그려짐). null이면 화면 전체에 그릴 수 있다. */
    var clipRect: RectF? = null

    /** 영역 편집 모드. true면 그리는 대신 사각형 틀을 드래그로 만들고 조절한다. */
    var areaEditMode: Boolean = false

    /** 영역이 바뀔 때 알리는 콜백(없어도 됨). */
    var onAreaChanged: (() -> Unit)? = null

    // 영역 편집용 드래그 상태
    private enum class DragMode { NONE, CREATE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var dragMode = DragMode.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var rectStart: RectF? = null
    private val handleTouchSize = 60f  // 모서리 핸들 터치 인식 반경(px)

    private val areaBorderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#D98E3A")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val areaHandlePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#D98E3A")
    }
    private val areaDimPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#552B6E63")
    }

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

        // 영역 편집 모드: 사각형 틀을 만들고 조절한다.
        if (areaEditMode) {
            return handleAreaEdit(event)
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 그리기 영역이 설정돼 있으면, 그 밖에서 시작한 터치는 무시
                val r = clipRect
                if (r != null && !r.contains(x, y)) return true
                val s = Stroke(penColor, penWidth, taperAmount, eraserMode)
                s.xs.add(x); s.ys.add(y)
                active = s
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = active ?: return true
                // 영역이 있으면 점을 영역 안으로 가둔다(clamp)
                val r = clipRect
                if (r != null) {
                    s.xs.add(x.coerceIn(r.left, r.right))
                    s.ys.add(y.coerceIn(r.top, r.bottom))
                } else {
                    s.xs.add(x); s.ys.add(y)
                }
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

    /** 영역 편집 모드에서 사각형 틀을 만들고 이동/리사이즈한다. */
    private fun handleAreaEdit(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = x; dragStartY = y
                val r = clipRect
                if (r == null) {
                    // 영역이 아직 없으면 새로 그리기 시작
                    dragMode = DragMode.CREATE
                    clipRect = RectF(x, y, x, y)
                } else {
                    rectStart = RectF(r)
                    dragMode = when {
                        near(x, y, r.left, r.top) -> DragMode.RESIZE_TL
                        near(x, y, r.right, r.top) -> DragMode.RESIZE_TR
                        near(x, y, r.left, r.bottom) -> DragMode.RESIZE_BL
                        near(x, y, r.right, r.bottom) -> DragMode.RESIZE_BR
                        r.contains(x, y) -> DragMode.MOVE
                        else -> DragMode.CREATE.also { clipRect = RectF(x, y, x, y) }
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = clipRect ?: return true
                when (dragMode) {
                    DragMode.CREATE -> {
                        r.set(
                            minOf(dragStartX, x), minOf(dragStartY, y),
                            maxOf(dragStartX, x), maxOf(dragStartY, y)
                        )
                    }
                    DragMode.MOVE -> {
                        val rs = rectStart ?: return true
                        val dx = x - dragStartX
                        val dy = y - dragStartY
                        r.set(rs.left + dx, rs.top + dy, rs.right + dx, rs.bottom + dy)
                    }
                    DragMode.RESIZE_TL -> { r.left = x; r.top = y }
                    DragMode.RESIZE_TR -> { r.right = x; r.top = y }
                    DragMode.RESIZE_BL -> { r.left = x; r.bottom = y }
                    DragMode.RESIZE_BR -> { r.right = x; r.bottom = y }
                    else -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = clipRect
                if (r != null) {
                    // 좌표 정규화(왼<오, 위<아래) 및 너무 작은 영역 방지
                    val nl = minOf(r.left, r.right)
                    val nt = minOf(r.top, r.bottom)
                    val nr = maxOf(r.left, r.right)
                    val nb = maxOf(r.top, r.bottom)
                    r.set(nl, nt, nr, nb)
                    if (r.width() < 40f || r.height() < 40f) {
                        // 너무 작으면 영역 해제(전체 그리기로)
                        clipRect = null
                    }
                }
                dragMode = DragMode.NONE
                rectStart = null
                onAreaChanged?.invoke()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun near(x: Float, y: Float, px: Float, py: Float): Boolean =
        hypot(x - px, y - py) <= handleTouchSize

    /** 그리기 영역을 해제(전체 화면에 그리기). */
    fun clearArea() {
        clipRect = null
        invalidate()
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
            for (s in strokes) drawStroke(canvas, s)
            active?.let { drawStroke(canvas, it) }
        }

        // 3) 그리기 영역 틀 표시 (영역 편집 모드일 때만 보임)
        val r = clipRect
        if (areaEditMode && r != null) {
            // 영역 바깥을 살짝 어둡게 (영역을 강조)
            canvas.drawRect(0f, 0f, width.toFloat(), r.top, areaDimPaint)
            canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), areaDimPaint)
            canvas.drawRect(0f, r.top, r.left, r.bottom, areaDimPaint)
            canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, areaDimPaint)
            // 점선 테두리
            canvas.drawRect(r, areaBorderPaint)
            // 네 모서리 핸들(원)
            val hr = 16f
            canvas.drawCircle(r.left, r.top, hr, areaHandlePaint)
            canvas.drawCircle(r.right, r.top, hr, areaHandlePaint)
            canvas.drawCircle(r.left, r.bottom, hr, areaHandlePaint)
            canvas.drawCircle(r.right, r.bottom, hr, areaHandlePaint)
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
