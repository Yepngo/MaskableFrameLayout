package com.yepngo.maskablelayout

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

class MaskableFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        private const val TAG = "MaskableFrameLayout"

        private val MODE_ADD = 0
        private val MODE_CLEAR = 1
        private val MODE_DARKEN = 2
        private val MODE_DST = 3
        private val MODE_DST_ATOP = 4
        private val MODE_DST_IN = 5
        private val MODE_DST_OUT = 6
        private val MODE_DST_OVER = 7
        private val MODE_LIGHTEN = 8
        private val MODE_MULTIPLY = 9
        private val MODE_OVERLAY = 10
        private val MODE_SCREEN = 11
        private val MODE_SRC = 12
        private val MODE_SRC_ATOP = 13
        private val MODE_SRC_IN = 14
        private val MODE_SRC_OUT = 15
        private val MODE_SRC_OVER = 16
        private val MODE_XOR = 17
    }

    private val handlerThread = HandlerThread("MaskableFrameLayoutThread")
    private val mHandler: Handler
    private var mDrawableMask: Drawable? = null
    private var mFinalMask: Bitmap? = null
    private var mPaint: Paint = createPaint(false)
    private var mPorterDuffXferMode: PorterDuffXfermode =
        PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        attrs?.let { attributeSet ->
            context.obtainStyledAttributes(
                attributeSet,
                R.styleable.MaskableLayout,
                0,
                0
            ).use { a ->
                initMask(loadMask(a))
                mPorterDuffXferMode =
                    getModeFromInteger(a.getInteger(R.styleable.MaskableLayout_porterduffxfermode, 0))
                initMask(mDrawableMask)
                if (a.getBoolean(R.styleable.MaskableLayout_anti_aliasing, false)) {
                    mPaint = createPaint(true)
                }
            }
        }
        handlerThread.start()
        mHandler = Handler(handlerThread.looper)
        registerMeasure()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handlerThread.quit()
    }


    private fun createPaint(antiAliasing: Boolean): Paint {
        val output = Paint(Paint.ANTI_ALIAS_FLAG)
        output.isAntiAlias = antiAliasing
        output.xfermode = mPorterDuffXferMode
        return output
    }

    private fun loadMask(a: TypedArray): Drawable? {
        val drawableResId = a.getResourceId(R.styleable.MaskableLayout_mask, -1)
        return if (drawableResId != -1) {
            ContextCompat.getDrawable(context, drawableResId)
        } else {
            null
        }
    }

    private fun initMask(input: Drawable?) {
        if (input != null) {
            mDrawableMask = input
            if (mDrawableMask is AnimationDrawable) {
                mDrawableMask?.callback = this
            }
        } else {
            log("No bitmap mask loaded, view will NOT be masked!")
        }
    }

    private fun makeBitmapMask(drawable: Drawable?): Bitmap? {
        if (drawable != null && measuredWidth > 0 && measuredHeight > 0) {
            val mask = Bitmap.createBitmap(
                measuredWidth,
                measuredHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(mask)
            drawable.setBounds(0, 0, measuredWidth, measuredHeight)
            drawable.draw(canvas)
            return mask
        } else {
            log("Can't create a mask with height 0 or width 0.")
            return null
        }
    }

    fun setMask(drawableRes: Int) {
        ContextCompat.getDrawable(context, drawableRes)?.let { drawable ->
            setMask(drawable)
        }
    }

    fun setMask(input: Drawable?) {
        initMask(input)
        swapBitmapMask(makeBitmapMask(mDrawableMask))
        invalidate()
    }

    fun setPorterDuffXferMode(mode: PorterDuff.Mode) {
        mPorterDuffXferMode = PorterDuffXfermode(mode)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setSize(w, h)
    }

    private fun setSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && mDrawableMask != null) {
            swapBitmapMask(makeBitmapMask(mDrawableMask))
        } else {
            log("Width and height must be higher than 0")
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        mFinalMask?.let { mask ->
            mPaint.xfermode = mPorterDuffXferMode
            canvas.drawBitmap(mask, 0.0f, 0.0f, mPaint)
            mPaint.xfermode = null
        } ?: log("Mask or paint is null.")
    }

    private fun registerMeasure() {
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
                swapBitmapMask(makeBitmapMask(mDrawableMask))
            }
        })
    }

    override fun invalidateDrawable(dr: Drawable) {
        initMask(dr)
        swapBitmapMask(makeBitmapMask(dr))
        invalidate()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) {
        mHandler.postAtTime(what, whenMillis)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        mHandler.removeCallbacks(what)
    }

    private fun swapBitmapMask(newMask: Bitmap?) {
        newMask?.let { mask ->
            mFinalMask?.recycle()
            mFinalMask = mask
        }
    }

    private fun getModeFromInteger(index: Int): PorterDuffXfermode {
        val mode = when (index) {
            MODE_ADD -> PorterDuff.Mode.ADD
            MODE_CLEAR -> PorterDuff.Mode.CLEAR
            MODE_DARKEN -> PorterDuff.Mode.DARKEN
            MODE_DST -> PorterDuff.Mode.DST
            MODE_DST_ATOP -> PorterDuff.Mode.DST_ATOP
            MODE_DST_IN -> PorterDuff.Mode.DST_IN
            MODE_DST_OUT -> PorterDuff.Mode.DST_OUT
            MODE_DST_OVER -> PorterDuff.Mode.DST_OVER
            MODE_LIGHTEN -> PorterDuff.Mode.LIGHTEN
            MODE_MULTIPLY -> PorterDuff.Mode.MULTIPLY
            MODE_OVERLAY -> PorterDuff.Mode.OVERLAY
            MODE_SCREEN -> PorterDuff.Mode.SCREEN
            MODE_SRC -> PorterDuff.Mode.SRC
            MODE_SRC_ATOP -> PorterDuff.Mode.SRC_ATOP
            MODE_SRC_IN -> PorterDuff.Mode.SRC_IN
            MODE_SRC_OUT -> PorterDuff.Mode.SRC_OUT
            MODE_SRC_OVER -> PorterDuff.Mode.SRC_OVER
            MODE_XOR -> PorterDuff.Mode.XOR
            else -> PorterDuff.Mode.DST_IN
        }
        log("Mode is $mode")
        return PorterDuffXfermode(mode)
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}
