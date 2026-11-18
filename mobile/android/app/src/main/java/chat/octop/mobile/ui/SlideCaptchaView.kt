package chat.octop.mobile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import chat.octop.mobile.R
import kotlin.math.max
import kotlin.math.min

/**
 * Client-side slide-to-unlock, matching dashboard [SlideCaptcha].
 * Server "slider" provider does not require a captcha_token.
 */
class SlideCaptchaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val track: FrameLayout
    private val fill: FrameLayout
    private val hint: TextView
    private val thumb: FrameLayout
    private val thumbIcon: ImageView

    private var maxOffset = 0
    private var offset = 0
    private var verified = false
    private var dragging = false
    private var startX = 0f
    private var startOffset = 0

    var onVerified: (() -> Unit)? = null
    var onReset: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_slide_captcha, this, true)
        track = findViewById(R.id.slideTrack)
        fill = findViewById(R.id.slideFill)
        hint = findViewById(R.id.slideHint)
        thumb = findViewById(R.id.slideThumb)
        thumbIcon = findViewById(R.id.slideThumbIcon)
        hint.text = context.getString(R.string.slide_hint)
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxOffset = max(0, track.width - thumb.width)
        if (verified) {
            applyOffset(maxOffset)
        }
    }

    fun isVerified(): Boolean = verified

    fun reset() {
        verified = false
        dragging = false
        offset = 0
        track.setBackgroundResource(R.drawable.bg_slide_track)
        thumb.setBackgroundResource(R.drawable.bg_slide_thumb)
        thumbIcon.setImageResource(R.drawable.ic_chevron_right)
        hint.setTextColor(0xFF64748B.toInt())
        hint.text = context.getString(R.string.slide_hint)
        applyOffset(0)
        onReset?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (verified) return false
        return ev.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (verified) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                maxOffset = max(0, track.width - thumb.width)
                startX = event.rawX
                startOffset = offset
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                applyOffset(clamp(startOffset + (event.rawX - startX).toInt()))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                maxOffset = max(0, track.width - thumb.width)
                if (maxOffset > 0 && offset.toFloat() / maxOffset >= COMPLETE_RATIO) {
                    complete()
                } else {
                    applyOffset(0)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun complete() {
        verified = true
        applyOffset(maxOffset)
        track.setBackgroundResource(R.drawable.bg_slide_track_ok)
        thumb.setBackgroundResource(R.drawable.bg_slide_thumb_ok)
        thumbIcon.setImageResource(R.drawable.ic_check)
        hint.setTextColor(0xFF16A34A.toInt())
        hint.text = context.getString(R.string.slide_verified)
        fill.layoutParams = fill.layoutParams.apply { width = track.width }
        fill.requestLayout()
        onVerified?.invoke()
    }

    private fun applyOffset(value: Int) {
        offset = clamp(value)
        thumb.translationX = offset.toFloat()
        val fillWidth = if (verified) track.width else offset + thumb.width / 2
        fill.layoutParams = fill.layoutParams.apply { width = max(0, fillWidth) }
        fill.requestLayout()
    }

    private fun clamp(raw: Int): Int = min(maxOffset, max(0, raw))

    companion object {
        private const val COMPLETE_RATIO = 0.92f
    }
}
