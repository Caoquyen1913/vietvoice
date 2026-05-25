package com.vietvoice.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.vietvoice.app.R
import java.util.LinkedList
import kotlin.math.abs

class OverlayController(private val context: Context) {

    interface Listener {
        fun onStartStop()
        fun onToggleTranscript()
    }

    var listener: Listener? = null
    var isRunning = false
    var transcriptVisible = false
    var srcFlag = "🇨🇳"
    var tgtFlag = "🇻🇳"

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null
    private var transcriptView: View? = null

    private val transcriptHistory = LinkedList<Pair<String, String>>()
    private var tvBubbleBtn: TextView? = null
    private var fontSp = 13f

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    private val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private val bubbleParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50
        y = 300
    }

    private val transcriptParams by lazy {
        WindowManager.LayoutParams(
            dpToPx(320),
            dpToPx(220),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dpToPx(380)
        }
    }

    fun show() {
        val inflater = LayoutInflater.from(context)

        val bubble = inflater.inflate(R.layout.overlay_bubble, null)
        tvBubbleBtn = bubble.findViewById(R.id.tv_bubble_btn)
        updateBubbleText()
        setupBubbleTouchAndGesture(bubble)
        bubbleView = bubble

        val transcript = inflater.inflate(R.layout.overlay_transcript, null)
        transcript.visibility = View.GONE
        transcriptView = transcript

        transcript.findViewById<View>(R.id.tv_drag_handle).let { setupTranscriptDrag(it) }

        transcript.findViewById<View>(R.id.btn_font_smaller).setOnClickListener {
            fontSp = (fontSp - 1f).coerceAtLeast(9f)
            renderTranscripts()
        }
        transcript.findViewById<View>(R.id.btn_font_larger).setOnClickListener {
            fontSp = (fontSp + 1f).coerceAtMost(24f)
            renderTranscripts()
        }
        transcript.findViewById<View>(R.id.btn_clear).setOnClickListener {
            transcriptHistory.clear()
            renderTranscripts()
        }
        transcript.findViewById<View>(R.id.btn_close).setOnClickListener {
            showTranscript(false)
        }
        transcript.findViewById<View>(R.id.v_resize_handle).let { setupTranscriptResize(it) }

        windowManager.addView(bubble, bubbleParams)
        windowManager.addView(transcript, transcriptParams)
    }

    private fun setupBubbleTouchAndGesture(view: View) {
        var startX = 0; var startY = 0
        var startRawX = 0f; var startRawY = 0f
        var isDragging = false

        val gestureDetector = GestureDetector(context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    listener?.onStartStop()
                    isRunning = !isRunning
                    updateBubbleText()
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    listener?.onToggleTranscript()
                    transcriptVisible = !transcriptVisible
                    showTranscript(transcriptVisible)
                }
            })

        view.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (!isDragging && (abs(dx) > 12 || abs(dy) > 12)) isDragging = true
                    if (isDragging) {
                        bubbleParams.x = startX + dx
                        bubbleParams.y = startY + dy
                        windowManager.updateViewLayout(v, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupTranscriptDrag(handleView: View) {
        var startX = 0; var startY = 0
        var startRawX = 0f; var startRawY = 0f

        handleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = transcriptParams.x
                    startY = transcriptParams.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    transcriptParams.x = startX + (event.rawX - startRawX).toInt()
                    transcriptParams.y = startY + (event.rawY - startRawY).toInt()
                    transcriptView?.let { windowManager.updateViewLayout(it, transcriptParams) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun setupTranscriptResize(resizeHandle: View) {
        var startRawX = 0f; var startRawY = 0f
        var startW = 0; var startH = 0

        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startW = transcriptParams.width
                    startH = transcriptParams.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    transcriptParams.width = (startW + (event.rawX - startRawX).toInt())
                        .coerceAtLeast(dpToPx(200))
                    transcriptParams.height = (startH + (event.rawY - startRawY).toInt())
                        .coerceAtLeast(dpToPx(140))
                    transcriptView?.let { windowManager.updateViewLayout(it, transcriptParams) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun renderTranscripts() {
        val tv = transcriptView ?: return
        val container = tv.findViewById<LinearLayout>(R.id.ll_transcripts) ?: return
        container.removeAllViews()
        for ((orig, trans) in transcriptHistory) {
            val row = TextView(context).apply {
                text = "$srcFlag $orig\n$tgtFlag $trans"
                textSize = fontSp
                setTextColor(0xFFECEFF4.toInt())
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dpToPx(2)) }
            container.addView(row, lp)
        }
    }

    fun addTranscript(original: String, translated: String) {
        transcriptHistory.addFirst(Pair(original, translated))
        if (transcriptHistory.size > 12) transcriptHistory.removeLast()
        renderTranscripts()
    }

    fun showTranscript(show: Boolean) {
        transcriptVisible = show
        transcriptView?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateBubbleText() {
        tvBubbleBtn?.text = if (isRunning) "⏸" else "▶"
    }

    fun hide() {
        try { bubbleView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { transcriptView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        bubbleView = null
        transcriptView = null
    }
}
