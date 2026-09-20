package com.maalik.projectlily

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Lightweight, scrollable result grid. The view only draws rows that are near
 * the viewport, keeps the row-number gutter pinned, and supports column
 * resizing, sorting, cell selection, double-tap copy and long-press inspect.
 */
class VirtualResultGridView(
    context: Context,
    private val rows: List<List<String>>,
    private val truncated: Boolean,
    private val onHeaderClick: ((Int) -> Unit)? = null,
    private val onCellClick: ((Int, Int, String) -> Unit)? = null,
    private val onCellCopy: ((String) -> Unit)? = null,
    private val onCellInspect: ((String) -> Unit)? = null,
    initialZoom: Float = 1f,
    private val onZoomChanged: ((Float) -> Unit)? = null
) : View(context) {
    private val density = resources.displayMetrics.density
    private val baseRowNumberWidth = 48f
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = Color.rgb(231, 227, 233)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = Color.rgb(185, 181, 191)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val nullPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = Color.rgb(139, 142, 151)
        typeface = android.graphics.Typeface.MONOSPACE
        alpha = 230
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 51, 62)
        strokeWidth = density
    }
    private val headerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 31, 39) }
    private val gutterBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 23, 29) }
    private val rowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 26, 33) }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(54, 62, 76) }
    private val selectedRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 34, 42) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = Color.rgb(140, 136, 148)
    }
    private val rowNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = Color.rgb(119, 122, 132)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val baseRowHeight = (34f * density).toInt().coerceAtLeast(28)
    private val baseHeaderHeight = (38f * density).toInt().coerceAtLeast(32)
    private val baseTextSize = 12f * density
    private val baseHintTextSize = 11f * density
    private val rowHeight get() = (baseRowHeight * zoom).toInt().coerceAtLeast((18f * density).toInt())
    private val headerHeight get() = (baseHeaderHeight * zoom).toInt().coerceAtLeast((22f * density).toInt())
    private val rowNumberWidth get() = (baseRowNumberWidth * density * zoom).toInt().coerceAtLeast(38)
    private fun scaled(valuePx: Float): Float = valuePx * zoom
    private val cellPadding get() = scaled(14f * density)
    private val baselineExtra get() = scaled(4f * density)
    private val headerBaselineExtra get() = scaled(5f * density)
    private var scrollXpx = 0f
    private var scrollYpx = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastX = 0f
    private var lastY = 0f
    private var colWidths = IntArray(0)
    private var contentWidth = 0
    private var zoom = initialZoom.coerceIn(0.42f, 1.30f)
    private var widthsInitialized = false
    private var userAdjustedWidths = false
    private var selectedRow = -1
    private var selectedColumn = -1
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            setZoom(zoom * detector.scaleFactor)
            return true
        }
    })
    private var resizingColumn = -1
    private var resizeStartX = 0f
    private var resizeStartWidth = 0

    init {
        isFocusable = true
        setBackgroundColor(Color.rgb(15, 16, 21))
        textPaint.textSize = baseTextSize * zoom
        headerPaint.textSize = baseTextSize * zoom
        nullPaint.textSize = baseTextSize * zoom
        hintPaint.textSize = baseHintTextSize * zoom
        rowNumberPaint.textSize = baseHintTextSize * zoom
        calculateWidths()
    }

    fun setZoom(value: Float) {
        val nextZoom = value.coerceIn(0.42f, 1.30f)
        val ratio = if (zoom > 0f) nextZoom / zoom else 1f
        zoom = nextZoom
        textPaint.textSize = baseTextSize * zoom
        headerPaint.textSize = baseTextSize * zoom
        nullPaint.textSize = baseTextSize * zoom
        hintPaint.textSize = baseHintTextSize * zoom
        rowNumberPaint.textSize = baseHintTextSize * zoom
        if (widthsInitialized) {
            for (i in colWidths.indices) {
                colWidths[i] = (colWidths[i] * ratio).toInt().coerceIn(
                    (88 * density * zoom).toInt().coerceAtLeast(42),
                    (420 * density * zoom).toInt().coerceAtLeast(120)
                )
            }
            contentWidth = colWidths.sum()
        } else {
            calculateWidths()
        }
        requestLayout()
        invalidate()
        onZoomChanged?.invoke(zoom)
    }

    private fun calculateWidths(availableWidth: Int = width) {
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }
        if (widthsInitialized && userAdjustedWidths) return
        colWidths = IntArray(cols)
        val availableDataWidth = (availableWidth - rowNumberWidth).coerceAtLeast((cols * 120 * density).toInt())
        val minWidth = (88 * density * zoom).toInt().coerceAtLeast(42)
        val maxWidth = (420 * density * zoom).toInt().coerceAtLeast(minWidth)
        val minimumTotal = cols * minWidth
        val maximumTotal = cols * maxWidth
        if (availableDataWidth in minimumTotal..maximumTotal) {
            val base = availableDataWidth / cols
            var remainder = availableDataWidth % cols
            for (c in 0 until cols) {
                colWidths[c] = base + if (remainder-- > 0) 1 else 0
            }
        } else {
            val target = (availableDataWidth / cols.toFloat()).toInt().coerceIn(minWidth, maxWidth)
            for (c in 0 until cols) colWidths[c] = target
        }
        contentWidth = colWidths.sum()
        widthsInitialized = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (!userAdjustedWidths && w > 0) calculateWidths(w)
        val desiredRows = rows.size.coerceAtMost(1001) + if (truncated) 1 else 0
        val desiredH = headerHeight + desiredRows * rowHeight
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightMeasureSpec)
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) return
        val maxScrollX = (contentWidth - (width - rowNumberWidth)).coerceAtLeast(0).toFloat()
        val footer = if (truncated) (rowHeight + 12 * density).toInt() else 0
        val contentHeight = headerHeight + (rows.size - 1).coerceAtLeast(0) * rowHeight + footer
        val maxScrollY = (contentHeight - height).coerceAtLeast(0).toFloat()
        scrollXpx = scrollXpx.coerceIn(0f, maxScrollX)
        scrollYpx = scrollYpx.coerceIn(0f, maxScrollY)

        // Body: horizontally scrollable data region, with only visible rows drawn.
        canvas.save()
        canvas.clipRect(rowNumberWidth.toFloat(), headerHeight.toFloat(), width.toFloat(), height.toFloat())
        canvas.translate(rowNumberWidth - scrollXpx, -scrollYpx)
        var x = 0f
        for (c in colWidths.indices) {
            canvas.drawLine(x + colWidths[c], headerHeight.toFloat(), x + colWidths[c], contentHeight.toFloat(), linePaint)
            x += colWidths[c]
        }
        val firstRow = maxOf(1, 1 + ((scrollYpx - headerHeight).toInt().coerceAtLeast(0) / rowHeight))
        val lastRow = min(rows.size, firstRow + height / rowHeight + 6)
        for (r in firstRow until lastRow) {
            val top = headerHeight + (r - 1) * rowHeight
            if (r == selectedRow) canvas.drawRect(0f, top.toFloat(), contentWidth.toFloat(), (top + rowHeight).toFloat(), selectedRowPaint)
            else if (r % 2 == 0) canvas.drawRect(0f, top.toFloat(), contentWidth.toFloat(), (top + rowHeight).toFloat(), rowBg)
            x = 0f
            for (c in colWidths.indices) {
                if (r == selectedRow && c == selectedColumn) {
                    canvas.drawRect(x + 1, top + 1f, x + colWidths[c] - 1f, top + rowHeight - 1f, selectionPaint)
                }
                val raw = rows[r].getOrElse(c) { "NULL" }
                val paint = if (raw == "NULL") nullPaint else textPaint
                canvas.drawText(ellipsize(raw, c), x + cellPadding, top + rowHeight / 2f + baselineExtra, paint)
                canvas.drawLine(x + colWidths[c], top.toFloat(), x + colWidths[c], (top + rowHeight).toFloat(), linePaint)
                x += colWidths[c]
            }
            canvas.drawLine(0f, (top + rowHeight).toFloat(), contentWidth.toFloat(), (top + rowHeight).toFloat(), linePaint)
        }
        if (truncated) {
            val footerY = headerHeight + (rows.size - 1) * rowHeight
            canvas.drawText("Showing first ${rows.size - 1} rows · result continues", cellPadding, footerY + rowHeight / 2f + baselineExtra, hintPaint)
        }
        canvas.restore()

        // Fixed row-number gutter.
        canvas.save()
        canvas.clipRect(0f, 0f, rowNumberWidth.toFloat(), height.toFloat())
        canvas.drawRect(0f, 0f, rowNumberWidth.toFloat(), height.toFloat(), gutterBg)
        val firstRowGutter = maxOf(1, 1 + ((scrollYpx - headerHeight).toInt().coerceAtLeast(0) / rowHeight))
        val lastRowGutter = min(rows.size, firstRowGutter + height / rowHeight + 6)
        for (r in firstRowGutter until lastRowGutter) {
            val top = headerHeight + (r - 1) * rowHeight - scrollYpx
            if (r == selectedRow) canvas.drawRect(0f, top, rowNumberWidth.toFloat(), top + rowHeight, selectedRowPaint)
            val label = r.toString()
            val tw = rowNumberPaint.measureText(label)
            canvas.drawText(label, rowNumberWidth - tw - scaled(8f * density), top + rowHeight / 2f + baselineExtra, rowNumberPaint)
        }
        canvas.drawLine(rowNumberWidth.toFloat(), headerHeight.toFloat(), rowNumberWidth.toFloat(), height.toFloat(), linePaint)
        canvas.restore()

        // Fixed header row-number cell.
        canvas.save()
        canvas.clipRect(0f, 0f, rowNumberWidth.toFloat(), headerHeight.toFloat())
        canvas.drawRect(0f, 0f, rowNumberWidth.toFloat(), headerHeight.toFloat(), headerBg)
        canvas.drawText("#", rowNumberWidth / 2f - headerPaint.measureText("#") / 2f, headerHeight / 2f + headerBaselineExtra, headerPaint)
        canvas.restore()

        // Header labels scroll horizontally with the body.
        canvas.save()
        canvas.clipRect(rowNumberWidth.toFloat(), 0f, width.toFloat(), headerHeight.toFloat())
        canvas.translate(rowNumberWidth - scrollXpx, 0f)
        canvas.drawRect(0f, 0f, contentWidth.toFloat(), headerHeight.toFloat(), headerBg)
        x = 0f
        for (c in colWidths.indices) {
            canvas.drawText(ellipsize(rows[0].getOrElse(c) { "" }, c), x + cellPadding, headerHeight / 2f + headerBaselineExtra, headerPaint)
            canvas.drawLine(x + colWidths[c], 0f, x + colWidths[c], headerHeight.toFloat(), linePaint)
            x += colWidths[c]
        }
        canvas.drawLine(0f, headerHeight.toFloat(), contentWidth.toFloat(), headerHeight.toFloat(), linePaint)
        canvas.restore()
    }

    private fun ellipsize(value: String, column: Int): String {
        val charWidth = (7.2f * density * zoom).coerceAtLeast(3.8f)
        val horizontalPadding = (28f * density * zoom).coerceAtLeast(8f)
        val maxChars = ((colWidths.getOrElse(column) { 120 } - horizontalPadding) / charWidth).toInt().coerceAtLeast(4)
        return if (value.length > maxChars) value.take(maxChars - 1) + "…" else value
    }

    private fun cellAt(viewX: Float, viewY: Float): Pair<Int, Int>? {
        if (viewX < rowNumberWidth || viewY < headerHeight) return null
        val cx = viewX - rowNumberWidth + scrollXpx
        val cy = viewY + scrollYpx
        val row = 1 + ((cy - headerHeight) / rowHeight).toInt()
        if (row !in 1 until rows.size) return null
        var x = 0f
        for (c in colWidths.indices) {
            x += colWidths[c]
            if (cx <= x) return row to c
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                longPressTriggered = false
                val cx = event.x - rowNumberWidth + scrollXpx
                if (event.x >= rowNumberWidth) {
                    var x = 0f
                    for (i in colWidths.indices) {
                        x += colWidths[i]
                        if (abs(cx - x) <= 10 * density) {
                            resizingColumn = i
                            resizeStartX = cx
                            resizeStartWidth = colWidths[i]
                            break
                        }
                    }
                }
                longPressRunnable = Runnable {
                    if (!moved && resizingColumn < 0) {
                        cellAt(downX, downY)?.let { (r, c) ->
                            selectedRow = r
                            selectedColumn = c
                            longPressTriggered = true
                            invalidate()
                            onCellInspect?.invoke(rows[r].getOrElse(c) { "NULL" })
                        }
                    }
                }.also { postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(event.x - downX) > 8 * density || abs(event.y - downY) > 8 * density) {
                    moved = true
                    longPressRunnable?.let { removeCallbacks(it) }
                }
                if (resizingColumn >= 0) {
                    val cx = event.x - rowNumberWidth + scrollXpx
                    colWidths[resizingColumn] = (resizeStartWidth + (cx - resizeStartX)).toInt().coerceIn(
                        (88 * density * zoom).toInt().coerceAtLeast(42),
                        (420 * density * zoom).toInt().coerceAtLeast(120)
                    )
                    userAdjustedWidths = true
                    contentWidth = colWidths.sum()
                    invalidate()
                    return true
                }
                scrollXpx -= dx
                scrollYpx -= dy
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { removeCallbacks(it) }
                if (resizingColumn >= 0) {
                    resizingColumn = -1
                    performClick()
                    return true
                }
                if (!moved && !longPressTriggered) {
                    if (event.x >= rowNumberWidth && downY < headerHeight) {
                        val cx = downX - rowNumberWidth + scrollXpx
                        var x = 0f
                        for (i in colWidths.indices) {
                            x += colWidths[i]
                            if (cx <= x) {
                                onHeaderClick?.invoke(i)
                                break
                            }
                        }
                    } else {
                        cellAt(downX, downY)?.let { (r, c) ->
                            selectedRow = r
                            selectedColumn = c
                            val value = rows[r].getOrElse(c) { "NULL" }
                            val now = System.currentTimeMillis()
                            val doubleTap = now - lastTapTime < 320L && abs(downX - lastTapX) < 20 * density && abs(downY - lastTapY) < 20 * density
                            lastTapTime = now
                            lastTapX = downX
                            lastTapY = downY
                            invalidate()
                            onCellClick?.invoke(r, c, value)
                            if (doubleTap) onCellCopy?.invoke(value)
                        }
                    }
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { removeCallbacks(it) }
                resizingColumn = -1
                return true
            }
        }
        return true
    }

    fun selectedValue(): String? = if (selectedRow in 1 until rows.size && selectedColumn >= 0) rows[selectedRow].getOrElse(selectedColumn) { "NULL" } else null

    fun selectedRowValues(): List<String>? = if (selectedRow in 1 until rows.size) rows[selectedRow].toList() else null

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
