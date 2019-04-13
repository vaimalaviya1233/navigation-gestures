package com.xda.nobar.views

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Parcel
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.LinearLayout
import com.xda.nobar.util.*

class NavBlackout : LinearLayout {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    init {
        setBackgroundColor(Color.BLACK)
    }

    private val baseParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PRIORITY_PHONE
        flags = WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    val bottomParams = baseParams.copy.apply {
        gravity = Gravity.BOTTOM
        height = context.navBarHeight
        width = WindowManager.LayoutParams.MATCH_PARENT
        y = -context.adjustedNavBarHeight
    }

    val leftParams = baseParams.copy.apply {
        gravity = Gravity.LEFT
        height = WindowManager.LayoutParams.MATCH_PARENT
        width = context.navBarHeight
        x = -context.adjustedNavBarHeight
    }

    val rightParams = baseParams.copy.apply {
        gravity = Gravity.RIGHT
        height = WindowManager.LayoutParams.MATCH_PARENT
        width = context.navBarHeight
        x = -context.adjustedNavBarHeight
    }

    fun add() {
        val params = if (context.prefManager.useTabletMode) bottomParams
        else when (context.rotation) {
            Surface.ROTATION_0 -> bottomParams
            Surface.ROTATION_180 -> bottomParams
            Surface.ROTATION_90 -> rightParams
            Surface.ROTATION_270 -> if (context.prefManager.useRot270Fix) rightParams else leftParams
            else -> return
        }

        remove()

        mainHandler.post {
            try {
                context.app.wm.addView(this, params)
            } catch (e: Exception) {}
        }
    }

    fun remove() {
        mainHandler.post {
            try {
                context.app.wm.removeView(this)
            } catch (e: Exception) {}
        }
    }

    private val WindowManager.LayoutParams.copy: WindowManager.LayoutParams
        get() = run {
            val parcel = Parcel.obtain()
            writeToParcel(parcel, 0)

            try {
                WindowManager.LayoutParams(parcel)
            } finally {
                parcel.recycle()
            }
        }
}