package com.xda.nobar.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.PreferenceManager
import android.provider.Settings
import android.support.v4.content.LocalBroadcastManager
import android.util.AttributeSet
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.xda.nobar.App
import com.xda.nobar.R
import com.xda.nobar.services.Actions
import com.xda.nobar.util.HiddenPillReasonManager
import com.xda.nobar.util.NavigationBarSideManager
import com.xda.nobar.util.Utils
import com.xda.nobar.util.Utils.getCustomHeight
import com.xda.nobar.util.Utils.getCustomWidth
import com.xda.nobar.util.Utils.getHomeX
import com.xda.nobar.util.Utils.getHomeY
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

/**
 * The Pill™©® (not really copyrighted)
 */
class BarView : LinearLayout, SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        const val ALPHA_HIDDEN = 0.2f
        const val ALPHA_ACTIVE = 1.0f
        const val ALPHA_GONE = 0.0f

        const val BRIGHTEN_INACTIVE = 0.0f
        const val BRIGHTEN_ACTIVE = 0.5f

        const val SCALE_NORMAL = 1.0f
        const val SCALE_MID = 0.7f
        const val SCALE_SMALL = 0.3f

        const val DEF_MARGIN_LEFT_DP = 2
        const val DEF_MARGIN_RIGHT_DP = 2
        const val DEF_MARGIN_BOTTOM_DP = 2

        val ENTER_INTERPOLATOR = DecelerateInterpolator()
        val EXIT_INTERPOLATOR = AccelerateInterpolator()

        private const val FIRST_SECTION = 0
        private const val SECOND_SECTION = 1
        private const val THIRD_SECTION = 2
    }
    private val app = context.applicationContext as App

    var view: View = View.inflate(context, R.layout.pill, this)
    var pill: LinearLayout = view.findViewById(R.id.pill)
    var pillFlash: LinearLayout = pill.findViewById(R.id.pill_tap_flash)
    var yDownAnimator: ValueAnimator? = null

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        width = Utils.getCustomWidth(context)
        height = Utils.getCustomHeight(context)
        gravity = Gravity.CENTER or Gravity.BOTTOM
        type =
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PRIORITY_PHONE
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        format = PixelFormat.TRANSLUCENT
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        if (Utils.dontMoveForKeyboard(context)) {
            flags = flags or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN and
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        if (Utils.largerHitbox(context)) {
            val margins = getPillMargins()
            margins.top = resources.getDimensionPixelSize(R.dimen.pill_margin_top_large_hitbox)
            changePillMargins(margins)
        }
    }
        get() = field.apply {
            y = getAdjustedHomeY()
            x = getAdjustedHomeX()
        }

    val hiddenPillReasons = HiddenPillReasonManager()

    private val gestureDetector = GestureManager()
    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val navigationBarSideManager = NavigationBarSideManager(context)

    private val pool = Executors.newScheduledThreadPool(1)

    var isHidden = false
    var beingTouched = false
        set(value) {
            field = value
            if (!value) {
                if (needsScheduledHide) {
                    scheduleHide()
                    needsScheduledHide = false
                }
            }
        }
    var isCarryingOutTouchAction = false
        set(value) {
            field = value
            if (!value && !isPillHidingOrShowing && !isHidden) {
                queuedLayoutUpdate?.invoke()
                queuedLayoutUpdate = null
            }
        }
    var isPillHidingOrShowing = false
        set(value) {
            field = value
            if (!value && !isCarryingOutTouchAction && !isHidden) {
                queuedLayoutUpdate?.invoke()
                queuedLayoutUpdate = null
            }
        }
    var isImmersive = false
        set(value) {
            field = value
            if (Utils.shouldUseOverscanMethod(context)) {
                queuedLayoutUpdate = {
                    if (params.y != getAdjustedHomeY()) {
                        params.y = getAdjustedHomeY()
                        updateLayout(params)
                    }
                }

                if (!isCarryingOutTouchAction && !isPillHidingOrShowing && !isHidden) {
                    queuedLayoutUpdate?.invoke()
                    queuedLayoutUpdate = null
                }
            }
        }
    var immersiveNav: Boolean = false
        set(value) {
            field = value
            queuedLayoutUpdate = {
                if (params.y != getAdjustedHomeY()) {
                    params.y = getAdjustedHomeY()
                    updateLayout(params)
                }
            }

            if (!isCarryingOutTouchAction && !isPillHidingOrShowing && !isHidden) {
                queuedLayoutUpdate?.invoke()
                queuedLayoutUpdate = null
            }
        }

    private var queuedLayoutUpdate: (() -> Unit)? = null
    private var needsScheduledHide = false

    private val hideLock = Any()

    constructor(context: Context) : super(context)
    constructor(context: Context, attributeSet: AttributeSet?) : super(context, attributeSet)
    constructor(context: Context, attributeSet: AttributeSet?, defStyleAttr: Int) : super(context, attributeSet, defStyleAttr)
    constructor(context: Context, attributeSet: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attributeSet, defStyleAttr, defStyleRes)

    init {
        alpha = ALPHA_GONE

        gestureDetector.loadActionMap()
    }

    /**
     * Perform setup
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        gestureDetector.loadActionMap()
        prefs.registerOnSharedPreferenceChangeListener(this)

        val layers = pill.background as LayerDrawable
        (layers.findDrawableByLayerId(R.id.background) as GradientDrawable).apply {
            setColor(Utils.getPillBGColor(context))
            cornerRadius = Utils.getPillCornerRadiusInPx(context).toFloat()
        }
        (layers.findDrawableByLayerId(R.id.foreground) as GradientDrawable).apply {
            setStroke(Utils.dpAsPx(context, 1), Utils.getPillFGColor(context))
            cornerRadius = Utils.getPillCornerRadiusInPx(context).toFloat()
        }

        (pillFlash.background as GradientDrawable).apply {
            cornerRadius = Utils.getPillCornerRadiusInPx(context).toFloat()
        }

        pill.elevation = Utils.dpAsPx(context, if (Utils.shouldShowShadow(context)) 2 else 0).toFloat()
        (pill.layoutParams as FrameLayout.LayoutParams).apply {
            val shadow = Utils.shouldShowShadow(context)
            marginEnd = if (shadow) Utils.dpAsPx(context, DEF_MARGIN_RIGHT_DP) else 0
            marginStart = if (shadow) Utils.dpAsPx(context, DEF_MARGIN_LEFT_DP) else 0
            bottomMargin = if (shadow) Utils.dpAsPx(context, DEF_MARGIN_BOTTOM_DP) else 0

            pill.layoutParams = this
        }

        layoutParams.width = getCustomWidth(context)
        layoutParams.height = getCustomHeight(context)
        layoutParams = layoutParams

        isSoundEffectsEnabled = Utils.feedbackSound(context)

        if (Utils.autoHide(context)) {
            hiddenPillReasons.add(HiddenPillReasonManager.AUTO)
            scheduleHide()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    /**
     * Listen for relevant changes in the SharedPreferences
     */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (gestureDetector.actionMap.keys.contains(key)) {
            gestureDetector.loadActionMap()
        }
        if (key != null && key.contains("use_pixels")) {
            params.width = getCustomWidth(context)
            params.height = getCustomHeight(context)
            params.x = getAdjustedHomeX()
            params.y = getAdjustedHomeY()
            updateLayout(params)
        }
        if (key == "custom_width_percent" || key == "custom_width") {
            params.width = getCustomWidth(context)
            params.x = getAdjustedHomeX()
            updateLayout(params)
        }
        if (key == "custom_height_percent" || key == "custom_height") {
            params.height = getCustomHeight(context)
            updateLayout(params)
        }
        if (key == "custom_y_percent" || key == "custom_y") {
            params.y = getAdjustedHomeY()
            updateLayout(params)
        }
        if (key == "custom_x_percent" || key == "custom_x") {
            params.x = getAdjustedHomeX()
            updateLayout(params)
        }
        if (key == "pill_bg" || key == "pill_fg") {
            val layers = pill.background as LayerDrawable
            (layers.findDrawableByLayerId(R.id.background) as GradientDrawable).apply {
                setColor(Utils.getPillBGColor(context))
            }
            (layers.findDrawableByLayerId(R.id.foreground) as GradientDrawable).apply {
                setStroke(Utils.dpAsPx(context, 1), Utils.getPillFGColor(context))
            }
        }
        if (key == "show_shadow") {
            val shadow = Utils.shouldShowShadow(context)
            pill.elevation = Utils.dpAsPx(context, if (shadow) 2 else 0).toFloat()

            (pill.layoutParams as FrameLayout.LayoutParams).apply {
                marginEnd = if (shadow) Utils.dpAsPx(context, DEF_MARGIN_RIGHT_DP) else 0
                marginStart = if (shadow) Utils.dpAsPx(context, DEF_MARGIN_LEFT_DP) else 0
                bottomMargin = if (shadow) Utils.dpAsPx(context, DEF_MARGIN_BOTTOM_DP) else 0

                pill.layoutParams = this
            }
        }
        if (key == "static_pill") {
            if (Utils.dontMoveForKeyboard(context)) {
                params.flags = params.flags or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN and
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            } else {
                params.flags = params.flags or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM and
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
                params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }

            updateLayout(params)
        }
        if (key == "audio_feedback") {
            isSoundEffectsEnabled = Utils.feedbackSound(context)
        }
        if (key == "pill_corner_radius") {
            val layers = pill.background as LayerDrawable
            (layers.findDrawableByLayerId(R.id.background) as GradientDrawable).apply {
                cornerRadius = Utils.dpAsPx(context, Utils.getPillCornerRadiusInDp(context)).toFloat()
            }
            (layers.findDrawableByLayerId(R.id.foreground) as GradientDrawable).apply {
                cornerRadius = Utils.dpAsPx(context, Utils.getPillCornerRadiusInDp(context)).toFloat()
            }
            (pillFlash.background as GradientDrawable).apply {
                cornerRadius = Utils.getPillCornerRadiusInPx(context).toFloat()
            }
        }
        if (key == "larger_hitbox") {
            val enabled = Utils.largerHitbox(context)
            val margins = getPillMargins()
            params.height = Utils.getCustomHeight(context)
            margins.top = resources.getDimensionPixelSize((if (enabled) R.dimen.pill_margin_top_large_hitbox else R.dimen.pill_margin_top_normal))
            changePillMargins(margins)
            updateLayout(params)
        }
        if (key == "auto_hide_pill") {
            if (Utils.autoHide(context)) {
                hiddenPillReasons.add(HiddenPillReasonManager.AUTO)
                if (!isHidden) scheduleHide()
            } else {
                if (isHidden) showPill(HiddenPillReasonManager.AUTO)
            }
        }
    }

    /**
     * Perform cleanup
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    /**
     * Animate the pill to invisibility
     * Used during deactivation
     * @param listener optional animation listener
     */
    fun hide(listener: Animator.AnimatorListener?) {
        animate(listener, ALPHA_GONE)
    }

    /**
     * Animate the pill to full visibility
     * Used during activation
     * @param listener optional animation listener
     */
    fun show(listener: Animator.AnimatorListener?) {
        animate(listener, ALPHA_ACTIVE)
    }

    /**
     * Animate to a chosen alpha
     * @param listener optional animation listener
     * @param alpha desired alpha level (0-1)
     */
    fun animate(listener: Animator.AnimatorListener?, alpha: Float) {
        app.handler.post {
            animate().alpha(alpha).setDuration(getAnimationDurationMs())
                    .setListener(object : Animator.AnimatorListener {
                        override fun onAnimationCancel(animation: Animator?) {
                            listener?.onAnimationCancel(animation)
                        }

                        override fun onAnimationEnd(animation: Animator?) {
                            listener?.onAnimationEnd(animation)
                        }

                        override fun onAnimationRepeat(animation: Animator?) {
                            listener?.onAnimationRepeat(animation)
                        }

                        override fun onAnimationStart(animation: Animator?) {
                            listener?.onAnimationStart(animation)
                        }
                    })
                    .withEndAction {
                        this@BarView.alpha = alpha
                    }
                    .start()
        }
    }

    private var hideHandle: ScheduledFuture<*>? = null

    /**
     * "Hide" the pill by moving it partially offscreen
     */
    fun hidePill(auto: Boolean, autoReason: String?, overrideBeingTouched: Boolean = false) {
        if (auto && autoReason == null) throw IllegalArgumentException("autoReason must not be null when auto is true")
        if (auto && autoReason != null) hiddenPillReasons.add(autoReason)

        if (!beingTouched || overrideBeingTouched) {
            app.handler.post {
                if (app.isPillShown()) {
                    isPillHidingOrShowing = true

                    val navHeight = getZeroY()

                    val animDurScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
                    val time = (getAnimationDurationMs() * animDurScale)
                    val distance = (params.y - navHeight).absoluteValue

                    if (distance == 0) {
                        animateHide()
                    } else {
                        val animator = ValueAnimator.ofInt(params.y, navHeight)
                        animator.interpolator = DecelerateInterpolator()
                        animator.addUpdateListener {
                            params.y = it.animatedValue.toString().toInt()
                            updateLayout(params)
                        }
                        animator.addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator?) {
                                animateHide()
                            }
                        })
                        animator.duration = (time * distance / 100f).toLong()
                        animator.start()
                    }

                    showHiddenToast()
                }
            }
        } else {
            needsScheduledHide = true
            app.handler.post {
                hideHandle?.cancel(true)
                hideHandle = null
            }
        }
    }

    private fun animateHide() {
        app.handler.post {
            pill.animate()
                    .translationY(pill.height.toFloat() / 2f)
                    .alpha(ALPHA_HIDDEN)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .setDuration(getAnimationDurationMs())
                    .withEndAction {
                        pill.translationY = pill.height.toFloat() / 2f

                        isHidden = true

                        isPillHidingOrShowing = false
                    }
                    .start()
        }
    }

    private fun scheduleHide(time: Long? = parseHideTime()) {
        if (time != null) {
            hideHandle = pool.schedule({
                if (hiddenPillReasons.isNotEmpty()) hidePill(true, hiddenPillReasons.getMostRecentReason())
            }, time, TimeUnit.MILLISECONDS)
        }
    }

    private fun parseHideTime(): Long? {
        val reason = hiddenPillReasons.getMostRecentReason()
        return when (reason) {
            HiddenPillReasonManager.AUTO -> Utils.autoHideTime(context)
            HiddenPillReasonManager.FULLSCREEN -> Utils.hideInFullscreenTime(context)
            HiddenPillReasonManager.KEYBOARD -> Utils.hideOnKeyboardTime(context)
            else -> null
        }
    }

    /**
     * "Show" the pill by moving it back to its normal position
     */
    fun showPill(autoReasonToRemove: String?) {
        if (autoReasonToRemove != null) hiddenPillReasons.removeAll(autoReasonToRemove)
        app.handler.post {
            if (app.isPillShown()) {
                isPillHidingOrShowing = true
                synchronized(hideLock) {
                    val reallyForceNotAuto = hiddenPillReasons.isEmpty()

                    if ((reallyForceNotAuto) && hideHandle != null) {
                        hideHandle?.cancel(true)
                        hideHandle = null
                    }

                    if (!reallyForceNotAuto) {
                        scheduleHide()
                    }

                    pill.animate()
                            .translationY(0f)
                            .alpha(ALPHA_ACTIVE)
                            .setInterpolator(EXIT_INTERPOLATOR)
                            .setDuration(getAnimationDurationMs())
                            .withEndAction {
                                pill.translationY = 0f

                                animateShow()
                            }
                            .start()
                }
            }
        }
    }

    private fun animateShow() {
        app.handler.post {
            val animDurScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
            val time = (getAnimationDurationMs() * animDurScale)

            val navHeight = getAdjustedHomeY()
            val distance = (navHeight - params.y).absoluteValue
            val animator = ValueAnimator.ofInt(params.y, navHeight)

            if (distance == 0) {
                app.handler.postDelayed(Runnable {
                    isHidden = false
                    isPillHidingOrShowing = false
                }, (if (getAnimationDurationMs() < 12) 12 else 0))
            } else {
                animator.interpolator = DecelerateInterpolator()
                animator.addUpdateListener {
                    params.y = it.animatedValue.toString().toInt()
                    updateLayout(params)
                }
                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        app.handler.postDelayed(Runnable {
                            isHidden = false
                            isPillHidingOrShowing = false
                        }, (if (getAnimationDurationMs() < 12) 12 else 0))
                    }
                })
                animator.duration = (time * distance / 100f).toLong()
                animator.start()
            }
        }
    }

    fun changePillMargins(margins: Rect) {
        (pill.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = margins.bottom
            topMargin = margins.top
            marginStart = margins.left
            marginEnd = margins.right

            app.handler.post { pill.layoutParams = pill.layoutParams }
        }
    }

    fun getPillMargins(): Rect {
        val rect = Rect()

        (pill.layoutParams as FrameLayout.LayoutParams).apply {
            rect.bottom = bottomMargin
            rect.top = topMargin
            rect.left = marginStart
            rect.right = marginEnd
        }

        return rect
    }

    fun getAdjustedHomeY(): Int {
        return if ((isImmersive || immersiveNav) && Utils.shouldUseOverscanMethod(context)) {
            if ((wm.defaultDisplay.rotation == Surface.ROTATION_90
                            || wm.defaultDisplay.rotation == Surface.ROTATION_270)
                    && !Utils.useTabletMode(context)) if (Utils.hideInFullscreen(context) && isImmersive) 0 else getHomeY(context)
            else if (Utils.origBarInFullscreen(context)) 0 else Utils.getNavBarHeight(context) + if (Utils.hideInFullscreen(context)) 0 else Utils.getHomeY(context)
        } else getHomeY(context)
    }

    fun getZeroY(): Int {
        return if ((isImmersive || immersiveNav) && Utils.shouldUseOverscanMethod(context)) {
            if ((wm.defaultDisplay.rotation == Surface.ROTATION_270
                            || wm.defaultDisplay.rotation == Surface.ROTATION_90)
                    && !Utils.useTabletMode(context)) 0
            else if (Utils.origBarInFullscreen(context)) 0 else Utils.getNavBarHeight(context)
        } else 0
    }

    fun getAdjustedHomeX(): Int {
        val screenSize = Utils.getRealScreenSize(context)
        val frame = Rect().apply { getWindowVisibleDisplayFrame(this) }

        val diff = (frame.left + frame.right) - screenSize.x

        return getHomeX(context) - if (immersiveNav) (diff / 2f).toInt() else 0
    }

    /**
     * Show a toast when the pill is hidden. Only shows once.
     */
    private fun showHiddenToast() {
        if (prefs.getBoolean("show_hidden_toast", true)) {
            Toast.makeText(context, resources.getString(R.string.pill_hidden), Toast.LENGTH_LONG).show()
            prefs.edit().putBoolean("show_hidden_toast", false).apply()
        }
    }
    
    fun updateLayout(params: WindowManager.LayoutParams) {
        app.handler.post {
            try {
                wm.updateViewLayout(this, params)
            } catch (e: Exception) {}
        }
    }

    /**
     * Get the user-defined or default time the user must hold a swipe to perform the swipe and hold action
     * @return the time, in ms
     */
    private fun getHoldTime(): Int {
        return prefs.getInt("hold_time", context.resources.getInteger(R.integer.default_hold_time))
    }

    /**
     * Get the user-defined or default duration of the feedback vibration
     * @return the duration, in ms
     */
    private fun getVibrationDuration(): Int {
        return prefs.getInt("vibration_duration", context.resources.getInteger(R.integer.default_vibe_time))
    }

    /**
     * Get the user-defined or default duration of the pill animations
     * @return the duration, in ms
     */
    private fun getAnimationDurationMs(): Long {
        return Utils.getAnimationDurationMs(context)
    }

    /**
     * The animation for a single tap on the pill
     */
    private fun jiggleTap() {
        app.handler.post {
            animate()
                    .scaleX(SCALE_MID)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .setDuration(getAnimationDurationMs())
                    .withEndAction {
                        animate()
                                .scaleX(SCALE_NORMAL)
                                .setInterpolator(EXIT_INTERPOLATOR)
                                .setDuration(getAnimationDurationMs())
                                .start()
                        animateActiveLayer(BRIGHTEN_INACTIVE)
                    }
                    .start()
            animateActiveLayer(BRIGHTEN_ACTIVE)
        }
    }

    /**
     * The animation for a swipe-left and hold on the pill
     */
    private fun jiggleLeftHold() {
        app.handler.post {
            animate()
                    .scaleX(SCALE_SMALL)
                    .x(-width * (1 - SCALE_SMALL) / 2)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .setDuration(getAnimationDurationMs())
                    .withEndAction {
                        animate()
                                .scaleX(SCALE_NORMAL)
                                .x(0f)
                                .setInterpolator(EXIT_INTERPOLATOR)
                                .setDuration(getAnimationDurationMs())
                                .start()
                        animateActiveLayer(BRIGHTEN_INACTIVE)
                    }
            animateActiveLayer(BRIGHTEN_ACTIVE)
        }
    }

    /**
     * The animation for a swipe-right and hold on the pill
     */
    private fun jiggleRightHold() {
        app.handler.post {
            animate()
                    .scaleX(SCALE_SMALL)
                    .x(width * (1 - SCALE_SMALL) / 2)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .setDuration(getAnimationDurationMs())
                    .withEndAction {
                        animate()
                                .scaleX(SCALE_NORMAL)
                                .x(0f)
                                .setInterpolator(EXIT_INTERPOLATOR)
                                .setDuration(getAnimationDurationMs())
                                .start()
                        animateActiveLayer(BRIGHTEN_INACTIVE)
                    }
            animateActiveLayer(BRIGHTEN_ACTIVE)
        }
    }

    /**
     * The animation for a long-press on the pill
     */
    private fun jiggleHold() {
        app.handler.post {
            animate()
                    .scaleX(SCALE_SMALL)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .setDuration(getAnimationDurationMs())
                    .withEndAction {
                        animate()
                                .scaleX(SCALE_NORMAL)
                                .setInterpolator(EXIT_INTERPOLATOR)
                                .setDuration(getAnimationDurationMs())
                                .start()
                        animateActiveLayer(BRIGHTEN_INACTIVE)
                    }
                    .start()
            animateActiveLayer(BRIGHTEN_ACTIVE)
        }
    }

    /**
     * The animation for an up-swipe and hold on the pill
     */
    private fun jiggleHoldUp() {
        app.handler.post {
            animate()
                    .scaleY(SCALE_SMALL)
                    .y(-height * (1 - SCALE_SMALL) / 2)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .setDuration(getAnimationDurationMs())
                    .withEndAction {
                        animate()
                                .scaleY(SCALE_NORMAL)
                                .y(0f)
                                .setInterpolator(EXIT_INTERPOLATOR)
                                .setDuration(getAnimationDurationMs())
                                .start()
                        animateActiveLayer(BRIGHTEN_INACTIVE)
                    }
                    .start()
            animateActiveLayer(BRIGHTEN_ACTIVE)
        }
    }

    /**
     * The animation for a double-tap on the pill
     */
    private fun jiggleDoubleTap() {
        app.handler.post {
            animate()
                    .scaleX(SCALE_MID)
                    .setInterpolator(AccelerateInterpolator())
                    .setDuration(getAnimationDurationMs())
                    .withEndAction {
                        animate()
                                .scaleX(SCALE_SMALL)
                                .setInterpolator(ENTER_INTERPOLATOR)
                                .setDuration(getAnimationDurationMs())
                                .withEndAction {
                                    animate()
                                            .scaleX(SCALE_NORMAL)
                                            .setInterpolator(EXIT_INTERPOLATOR)
                                            .setDuration(getAnimationDurationMs())
                                            .start()
                                    animateActiveLayer(BRIGHTEN_INACTIVE)
                                }
                                .start()
                    }
                    .start()
            animateActiveLayer(BRIGHTEN_ACTIVE)
        }
    }

    /**
     * This is called twice to "flash" the pill when an action is performed
     */
    fun animateActiveLayer(alpha: Float) {
        app.handler.post {
            pillFlash.apply {
                val alphaRatio = Color.alpha(Utils.getPillBGColor(context)).toFloat() / 255f
                animate()
                        .setDuration(getAnimationDurationMs())
                        .alpha(alpha * alphaRatio)
                        .start()
            }
        }
    }

    /**
     * Vibrate for the specified duration
     * @param duration the desired duration
     */
    fun vibrate(duration: Long) {
        app.handler.post {
            if (duration > 0) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(duration)
                }
            }

            if (isSoundEffectsEnabled) {
                playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
    }

    /**
     * Manage all the gestures on the pill
     */
    inner class GestureManager {
        val actionMap = HashMap<String, Int>()
        private val tapLock = Any()

        private var isSwipeUp = false
        private var isSwipeLeft = false
        private var isSwipeRight = false
        private var isOverrideTap = false
        private var wasHidden = false

        var isActing = false

        private var upHoldHandle: ScheduledFuture<*>? = null
        private var leftHoldHandle: ScheduledFuture<*>? = null
        private var rightHoldHandle: ScheduledFuture<*>? = null

        private var isRunningLongUp = false
        private var isRunningLongLeft = false
        private var isRunningLongRight = false

        private var oldEvent: MotionEvent? = null
        private var oldY = 0F
        private var oldX = 0F

        private var origX = 0F
        private var origY = 0F

        private val manager = GestureDetector(context, Detector())

        fun onTouchEvent(ev: MotionEvent?): Boolean {
            return handleTouchEvent(ev) || manager.onTouchEvent(ev)
        }

        private fun handleTouchEvent(ev: MotionEvent?): Boolean {
            val animDurScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f)
            val time = (getAnimationDurationMs() * animDurScale)
            var ultimateReturn = false

            when (ev?.action) {
                MotionEvent.ACTION_DOWN -> {
                    wasHidden = isHidden
                    app.uiHandler.onGlobalLayout()
                    oldY = ev.rawY
                    oldX = ev.rawX
                    origX = ev.rawX
                    origY = ev.rawY
                    beingTouched = true
                    isCarryingOutTouchAction = true
                }

                MotionEvent.ACTION_UP -> {
                    app.runAsync {
                        beingTouched = false

                        if (wasHidden) {
                            isSwipeUp = false
                        }

                        if (isSwipeUp || (isRunningLongUp &&  getSectionedUpHoldAction(origX) == app.typeNoAction)) {
                            app.handler.post {
                                upHoldHandle?.cancel(true)
                                upHoldHandle = null
                            }
                            sendAction(app.actionUp)
                        }

                        if (isSwipeLeft || (isRunningLongLeft && actionMap[app.actionLeftHold] == app.typeNoAction)) {
                            app.handler.post {
                                leftHoldHandle?.cancel(true)
                                leftHoldHandle = null
                            }
                            sendAction(app.actionLeft)
                        }

                        if (isSwipeRight || (isRunningLongRight && actionMap[app.actionRightHold] == app.typeNoAction)) {
                            app.handler.post {
                                rightHoldHandle?.cancel(true)
                                rightHoldHandle = null
                            }
                            sendAction(app.actionRight)
                        }

                        if (pill.translationX != 0f) {
                            app.handler.post {
                                pill.animate()
                                        .translationX(0f)
                                        .setDuration(getAnimationDurationMs())
                                        .withEndAction {
                                            if (params.x == getAdjustedHomeX()) {
                                                isActing = false
                                                isSwipeLeft = false
                                                isSwipeRight = false
                                            }
                                        }
                                        .start()
                            }
                        }

                        when {
                            params.y > getAdjustedHomeY() -> {
                                val distance = (params.y - getAdjustedHomeY()).absoluteValue
                                if (yDownAnimator != null) {
                                    app.handler.post {
                                        yDownAnimator?.cancel()
                                        yDownAnimator = null
                                    }
                                }
                                yDownAnimator = ValueAnimator.ofInt(params.y, getAdjustedHomeY())
                                yDownAnimator?.interpolator = DecelerateInterpolator()
                                yDownAnimator?.addUpdateListener {
                                    params.y = it.animatedValue.toString().toInt()
                                    updateLayout(params)
                                }
                                yDownAnimator?.addListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator?) {
                                        isActing = false
                                        isSwipeUp = false
                                        isCarryingOutTouchAction = false

                                        yDownAnimator = null
                                    }

                                    override fun onAnimationCancel(animation: Animator?) {
                                        onAnimationEnd(animation)
                                    }
                                })
                                yDownAnimator?.duration = (time * distance / 100f).toLong()
                                app.handler.post { yDownAnimator?.start() }
                            }
                            params.x < getAdjustedHomeX() || params.x > getAdjustedHomeX() -> {
                                val distance = (params.x - getAdjustedHomeX()).absoluteValue
                                val animator = ValueAnimator.ofInt(params.x, getAdjustedHomeX())
                                animator.interpolator = DecelerateInterpolator()
                                animator.addUpdateListener {
                                    params.x = it.animatedValue.toString().toInt()
                                    updateLayout(params)
                                }
                                animator.addListener(object : AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator?) {
                                        isActing = false
                                        isSwipeLeft = false
                                        isSwipeRight = false
                                        isCarryingOutTouchAction = false
                                    }
                                })
                                animator.duration = (time * distance / 100f).toLong()
                                app.handler.post { animator.start() }
                            }
                            else -> {
                                isActing = false
                                isSwipeUp = false
                                isSwipeLeft = false
                                isSwipeRight = false
                                isCarryingOutTouchAction = false
                            }
                        }

                        isRunningLongRight = false
                        isRunningLongLeft = false
                        isRunningLongUp = false

                        wasHidden = isHidden
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    ultimateReturn = handlePotentialSwipe(ev)

                    if (isSwipeUp && !isSwipeLeft && !isSwipeRight) {
                        if (!isActing) isActing = true

                        val velocity = (oldY - ev.rawY)
                        oldY = ev.rawY

                        if (params.y < Utils.getRealScreenSize(context).y / 6 + getAdjustedHomeY() && getAnimationDurationMs() > 0) {
                            params.y = params.y + (velocity / 2).toInt()
                            updateLayout(params)
                        }

                        if (upHoldHandle == null) {
                            upHoldHandle = pool.schedule({
                                app.handler.post {
                                    isRunningLongUp = true
                                    sendAction(app.actionUpHold)
                                    isSwipeUp = false
                                    upHoldHandle = null
                                }
                            }, getHoldTime().toLong(), TimeUnit.MILLISECONDS)
                        }
                    }

                    if ((isSwipeLeft || isSwipeRight) && !isSwipeUp) {
                        if (!isActing) isActing = true

                        val velocity = ev.rawX - oldX
                        oldX = ev.rawX

                        val halfScreen = Utils.getRealScreenSize(context).x / 2f
                        val leftParam = params.x - Utils.getCustomWidth(context).toFloat() / 2f
                        val rightParam = params.x + Utils.getCustomWidth(context).toFloat() / 2f

                        if (getAnimationDurationMs() > 0) {
                            when {
                                leftParam <= -halfScreen && !isSwipeRight -> {
                                    pill.translationX += velocity
                                }
                                rightParam >= halfScreen && !isSwipeLeft -> pill.translationX += velocity
                                else -> {
                                    params.x = params.x + (velocity / 2).toInt()
                                    updateLayout(params)
                                }
                            }
                        }

                        if (isSwipeLeft) {
                            if (leftHoldHandle == null) {
                                leftHoldHandle = pool.schedule({
                                    app.handler.post {
                                        isRunningLongLeft = true
                                        sendAction(app.actionLeftHold)
                                        isSwipeLeft = false
                                        leftHoldHandle = null
                                    }
                                }, getHoldTime().toLong(), TimeUnit.MILLISECONDS)
                            }
                        }

                        if (isSwipeRight) {
                            if (rightHoldHandle == null) {
                                rightHoldHandle = pool.schedule({
                                    app.handler.post {
                                        isRunningLongRight = true
                                        sendAction(app.actionRightHold)
                                        isSwipeRight = false
                                        rightHoldHandle = null
                                    }
                                }, getHoldTime().toLong(), TimeUnit.MILLISECONDS)
                            }
                        }
                    }
                }
            }

            oldEvent = MotionEvent.obtain(ev)

            return ultimateReturn
        }

        private fun handlePotentialSwipe(motionEvent: MotionEvent?): Boolean {
            if (motionEvent == null) return false

            val distanceX = motionEvent.rawX - origX
            val distanceY = motionEvent.rawY - origY
            val xThresh = Utils.getXThresholdPx(context)
            val yThresh = Utils.getYThresholdPx(context)

//            Log.e("NoBar", "$distanceX, $xThresh // $distanceY, $yThresh")

            return if (!isHidden && !isActing) {
                when {
                    distanceX < -xThresh && distanceY.absoluteValue <= distanceX.absoluteValue -> { //left swipe
                        isSwipeLeft = true
                        true
                    }
                    distanceX > xThresh && distanceY.absoluteValue <= distanceX.absoluteValue -> { //right swipe
                        isSwipeRight = true
                        true
                    }
                    distanceY > yThresh && distanceY.absoluteValue > distanceX.absoluteValue -> { //down swipe
                        isActing = true
                        sendAction(app.actionDown)
                        true
                    }
                    distanceY < -yThresh && distanceY.absoluteValue > distanceX.absoluteValue -> { //up swipe and up hold-swipe
                        isSwipeUp = true
                        true
                    }
                    else -> false
                }
            } else if (isHidden
                    && !isActing
                    && distanceY < -yThresh
                    && distanceY.absoluteValue > distanceX.absoluteValue) { //up swipe
                showPill(null)
                true
            } else false
        }

        private fun getSectionedUpHoldAction(x: Float): Int? {
            return if (!Utils.sectionedPill(context)) actionMap[app.actionUpHold]
            else when (getSection(x)) {
                FIRST_SECTION -> actionMap[app.actionUpHoldLeft]
                SECOND_SECTION -> actionMap[app.actionUpHoldCenter]
                else -> actionMap[app.actionUpHoldRight]
            }
        }

        private fun String.isEligible() = arrayListOf(
                app.actionUp,
                app.actionUpHold
        ).contains(this) && Utils.sectionedPill(context)

        private fun getSection(x: Float): Int {
            val third = params.width / 3f

            return when {
                x < third -> FIRST_SECTION
                x <= (2f * third) -> SECOND_SECTION
                else -> THIRD_SECTION
            }
        }

        private fun sendAction(action: String) {
            if (action.isEligible()) {
                when(getSection(origX)) {
                    FIRST_SECTION -> sendActionInternal("${action}_left")
                    SECOND_SECTION -> sendActionInternal("${action}_center")
                    THIRD_SECTION -> sendActionInternal("${action}_right")
                }
            } else {
                sendActionInternal(action)
            }
        }

        /**
         * Parse the action index and broadcast to {@link com.xda.nobar.services.Actions}
         * @param key one of app.action*
         */
        private fun sendActionInternal(key: String) {
            val which = actionMap[key] ?: return

            if (which == app.typeNoAction) return

            vibrate(getVibrationDuration().toLong())

            if (key == app.actionDouble) app.handler.postDelayed({ vibrate(getVibrationDuration().toLong()) }, getVibrationDuration().toLong())

            if (which == app.typeHide) {
                if (key == app.actionUp || key == app.actionUpHold) {
                    app.handler.post {
                        yDownAnimator?.cancel()
                        yDownAnimator = null
                    }
                }
                hidePill(false, null, true)
                return
            }

            when (key) {
                app.actionDouble -> jiggleDoubleTap()
                app.actionHold -> jiggleHold()
                app.actionTap -> jiggleTap()
                app.actionUpHold -> jiggleHoldUp()
                app.actionLeftHold -> jiggleLeftHold()
                app.actionRightHold -> jiggleRightHold()
            }

            if (key == app.actionUp || key == app.actionLeft || key == app.actionRight) {
                animate(null, ALPHA_ACTIVE)
            }

            val intent = Intent(Actions.ACTION)
            intent.putExtra(Actions.EXTRA_ACTION, which)
            intent.putExtra(Actions.EXTRA_GESTURE, key)

            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

            if (Utils.shouldUseRootCommands(context)) {
                app.rootBinder?.handle(which)
            }
        }

        /**
         * Load the user's custom gesture/action pairings; default values if a pairing doesn't exist
         */
        fun loadActionMap() {
            Utils.getActionsList(context, actionMap)
        }

        inner class Detector : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(ev: MotionEvent): Boolean {
                return if (actionMap[app.actionDouble] == app.typeNoAction && !isActing && !wasHidden) {
                    synchronized(tapLock) {
                        isOverrideTap = true
                        sendAction(app.actionTap)
                    }
                    isActing = false
                    true
                } else false
            }

            override fun onLongPress(ev: MotionEvent) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val isPinned = Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1 && am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

                if (!isHidden && !isActing) {
                    if (isPinned) {
                       if (Utils.shouldUseOverscanMethod(context)) app.showNav()
                    } else {
                        isActing = true
                        sendAction(app.actionHold)
                    }
                }
            }

            override fun onDoubleTap(ev: MotionEvent): Boolean {
                return if (!isHidden &&!isActing) {
                    isActing = true
                    sendAction(app.actionDouble)
                    true
                } else false
            }

            override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
                synchronized(tapLock) {
                    return if (!isOverrideTap && !isHidden) {
                        isActing = false

                        sendAction(app.actionTap)
                        true
                    } else if (isHidden) {
                        isOverrideTap = false
                        vibrate(getVibrationDuration().toLong())
                        showPill(null)
                        true
                    } else {
                        isOverrideTap = false
                        false
                    }
                }
            }
        }
    }
}