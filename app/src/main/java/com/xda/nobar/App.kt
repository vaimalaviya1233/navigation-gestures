package com.xda.nobar

import android.Manifest
import android.animation.Animator
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.provider.Settings
import android.support.v4.app.NotificationCompat
import android.support.v4.content.ContextCompat
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.crashlytics.android.Crashlytics
import com.github.anrwatchdog.ANRWatchDog
import com.google.firebase.analytics.FirebaseAnalytics
import com.xda.nobar.activities.IntroActivity
import com.xda.nobar.interfaces.OnGestureStateChangeListener
import com.xda.nobar.interfaces.OnLicenseCheckResultListener
import com.xda.nobar.interfaces.OnNavBarHideStateChangeListener
import com.xda.nobar.providers.BaseProvider
import com.xda.nobar.services.Actions
import com.xda.nobar.services.ForegroundService
import com.xda.nobar.services.RootService
import com.xda.nobar.util.*
import com.xda.nobar.util.IWindowManager
import com.xda.nobar.views.BarView
import com.xda.nobar.views.ImmersiveHelperView
import java.util.*


/**
 * Centralize important stuff in the App class, so we can be sure to have an instance of it
 */
class App : Application(), SharedPreferences.OnSharedPreferenceChangeListener, AppOpsManager.OnOpChangedListener {
    companion object {
        const val EDGE_TYPE_ACTIVE = 2

        var isValidPremium: Boolean = false
    }

    val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    val um by lazy { getSystemService(Context.UI_MODE_SERVICE) as UiModeManager }
    val appOps by lazy { getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager }
    val nm by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val imm by lazy { getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    private val stateHandler = ScreenStateHandler()
    private val carModeHandler = CarModeHandler()
    private val premiumHelper by lazy { PremiumHelper(this, OnLicenseCheckResultListener { valid, reason ->
        val bundle = Bundle()
        bundle.putBoolean("valid", valid)
        bundle.putString("reason", reason)

        FirebaseAnalytics.getInstance(this).logEvent("license_event", bundle)

        isValidPremium = valid
        prefs.edit().putBoolean("valid_prem", valid).apply()

        licenseCheckListeners.forEach { it.onResult(valid, reason) }

        val updateActions = Intent(this, Actions::class.java)
        updateActions.action = Actions.PREMIUM_UPDATE
        updateActions.putExtra(Actions.EXTRA_PREM, isValidPremium)
        startService(updateActions)
    })}

    private val premiumInstallListener = PremiumInstallListener()
    private val rootServiceIntent by lazy { Intent(this, RootService::class.java) }

    val immersiveHelperView by lazy { ImmersiveHelperView(this) }
    val prefs: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    val screenOffHelper by lazy { ScreenOffHelper(this) }

    private var isInOtherWindowApp = false

    var rootBinder: RootService.RootBinder? = null

    var navHidden = false
    var pillShown = false
    var helperAdded = false
    var keyboardShown = false

    private val gestureListeners = ArrayList<OnGestureStateChangeListener>()
    private val navbarListeners = ArrayList<OnNavBarHideStateChangeListener>()
    private val licenseCheckListeners = ArrayList<OnLicenseCheckResultListener>()

    val handler = Handler(Looper.getMainLooper())

    val logicThread = HandlerThread("NoBar-Logic").apply { start() }
    val actionThread = HandlerThread("NoBar-Actions").apply { start() }

    val logicHandler = Handler(logicThread.looper)
    val actionHandler = Handler(actionThread.looper)

    val uiHandler = UIHandler()

    val bar by lazy { BarView(this) }

    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootBinder = service as RootService.RootBinder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootBinder = null
        }
    }

    val disabledNavReasonManager = DisabledReasonManager()
    val disabledBarReasonManager = DisabledReasonManager()
    val disabledImmReasonManager = DisabledReasonManager()
    val actionHolder by lazy { ActionHolder.getInstance(this) }

    /**
     * ***************************************************************
     */

    override fun onCreate() {
        super.onCreate()

        if (isRightProcess()) {
            val watchDog = ANRWatchDog()
            watchDog.setReportMainThreadOnly()
            watchDog.start()
            watchDog.setANRListener {
                Crashlytics.logException(it)
            }

            if (!Utils.canRunHiddenCommands(this) || IntroActivity.needsToRun(this)) {
                IntroActivity.start(this)
            }

            stateHandler.register()
            uiHandler.register()
            carModeHandler.register()
            premiumInstallListener.register()

            isValidPremium = prefs.getBoolean("valid_prem", false)

            prefs.registerOnSharedPreferenceChangeListener(this)

            refreshPremium()

            if (areGesturesActivated() && !IntroActivity.needsToRun(this)) {
                addBar()
            }

            if (Utils.useRot270Fix(this)
                    || Utils.useTabletMode(this)
                    || Utils.useRot180Fix(this)) uiHandler.handleRot()

            if (!IntroActivity.needsToRun(this)) {
                addImmersiveHelper()
                uiHandler.onGlobalLayout()
                immersiveHelperView.viewTreeObserver.addOnGlobalLayoutListener(uiHandler)
                immersiveHelperView.setOnSystemUiVisibilityChangeListener(uiHandler)
                immersiveHelperView.immersiveListener = uiHandler
            }

            appOps.startWatchingMode(AppOpsManager.OP_SYSTEM_ALERT_WINDOW, packageName, this)
        }
    }

    private fun isRightProcess(): Boolean {
        var procName = ""
        val pid = Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        am.runningAppProcesses.filter { it.pid == pid }.forEach { procName = it.processName }

        return !procName.contains("action")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        when (key) {
            "is_active" -> {
                gestureListeners.forEach { it.onGestureStateChange(bar, sharedPreferences.getBoolean(key, false)) }
            }
            "hide_nav" -> {
                navbarListeners.forEach { it.onNavStateChange(sharedPreferences.getBoolean(key, false)) }
            }
            "use_root" -> {
                if (Utils.shouldUseRootCommands(this)) {
                    startService(rootServiceIntent)
                    ensureRootServiceBound()
                } else {
                    stopService(rootServiceIntent)
                }
            }
            "rot270_fix" -> {
                if (Utils.useRot270Fix(this)) uiHandler.handleRot()
            }
            "rot180_fix" -> {
                if (Utils.useRot180Fix(this)) uiHandler.handleRot()
            }
            "tablet_mode" -> {
                if (Utils.useTabletMode(this)) uiHandler.handleRot()
            }
            "enable_in_car_mode" -> {
                val enabled = Utils.enableInCarMode(this)
                if (um.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
                    if (enabled) {
                        disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        if (areGesturesActivated() && !pillShown) addBar(false)
                    } else {
                        disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        if (areGesturesActivated() && !pillShown) removeBar(false)
                    }
                }
            }
            "use_immersive_mode_when_nav_hidden" -> {
                BaseProvider.sendUpdate(this)
            }
            "hide_pill_on_keyboard" -> {
                uiHandler.onGlobalLayout()
            }
            "full_overscan" -> {
                if (Utils.shouldUseOverscanMethod(this)) hideNav(false)
            }
        }
    }

    override fun onOpChanged(op: String?, packageName: String?) {
        if (packageName == this.packageName) {
            when (op) {
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW -> {
                    val mode = appOps.checkOpNoThrow(op, Process.myUid(), packageName)
                    if ((Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1
                                    && mode == AppOpsManager.MODE_DEFAULT
                                    && checkSelfPermission(Manifest.permission.SYSTEM_ALERT_WINDOW) == PackageManager.PERMISSION_GRANTED)
                            || mode == AppOpsManager.MODE_ALLOWED)
                        IntroActivity.start(this)
                }
            }
        }
    }

    fun refreshPremium() {
        premiumHelper.checkPremium()
    }

    /**
     * Add an activation listener
     * Notifies caller when a change in activation occurs
     */
    fun addGestureActivationListener(listener: OnGestureStateChangeListener) = gestureListeners.add(listener)

    /**
     * Remove an activation listener
     */
    fun removeGestureActivationListener(listener: OnGestureStateChangeListener) = gestureListeners.remove(listener)

    fun addNavBarHideListener(listener: OnNavBarHideStateChangeListener) = navbarListeners.add(listener)

    fun removeNavbarHideListener(listener: OnNavBarHideStateChangeListener) = navbarListeners.remove(listener)

    fun addLicenseCheckListener(listener: OnLicenseCheckResultListener) = licenseCheckListeners.add(listener)

    fun removeLicenseCheckListener(listener: OnLicenseCheckResultListener) = licenseCheckListeners.remove(listener)

    /**
     * Add the pill to the screen
     */
    fun addBar(callListeners: Boolean = true) {
        if (disabledBarReasonManager.isEmpty()) {
            handler.post {
                bar.params.apply {
                    x = bar.getAdjustedHomeX()
                    width = Utils.getCustomWidth(this@App)
                    height = Utils.getCustomHeight(this@App)
                    gravity = Gravity.CENTER or Gravity.TOP
                    y = bar.getAdjustedHomeY()
                    type =
                            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1)
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            else
                                WindowManager.LayoutParams.TYPE_PRIORITY_PHONE
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                    format = PixelFormat.TRANSLUCENT
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

                    if (Utils.dontMoveForKeyboard(this@App)) {
                        flags = flags or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN and
                                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
                        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
                    }

                    if (Utils.largerHitbox(this@App)) {
                        val margins = bar.getPillMargins()
                        margins.top = resources.getDimensionPixelSize(R.dimen.pill_margin_top_large_hitbox)
                        bar.changePillMargins(margins)
                    }
                }

                if (callListeners) gestureListeners.forEach { it.onGestureStateChange(bar, true) }

                addBarInternal()
            }
        }
    }

    fun addImmersiveHelper(shouldRemoveFirst: Boolean = true) {
        handler.post {
            try {
                if (shouldRemoveFirst) {
                    immersiveHelperView.shouldReAddOnDetach = true
                    wm.removeView(immersiveHelperView)
                } else addImmersiveHelperUnconditionally()
            } catch (e: Exception) {
                addImmersiveHelperUnconditionally()
            }
        }
    }

    private fun addImmersiveHelperUnconditionally() {
        try {
            wm.addView(immersiveHelperView, immersiveHelperView.params)
        } catch (e: Exception) {}
    }

    /**
     * Remove the pill from the screen
     */
    fun removeBar(callListeners: Boolean = true) {
        handler.post {
            if (callListeners) gestureListeners.forEach { it.onGestureStateChange(bar, false) }

            bar.hide(object : Animator.AnimatorListener {
                override fun onAnimationCancel(animation: Animator?) {}

                override fun onAnimationRepeat(animation: Animator?) {}

                override fun onAnimationStart(animation: Animator?) {}

                override fun onAnimationEnd(animation: Animator?) {
                    try {
                        bar.shouldReAddOnDetach = false
                        wm.removeView(bar)
                    } catch (e: Exception) {}

                    if (!navHidden) removeImmersiveHelper()
                }
            })

            if (!navHidden) stopService(Intent(this, ForegroundService::class.java))
        }
    }

    fun removeImmersiveHelper(forRefresh: Boolean = false) {
        handler.post {
            try {
                immersiveHelperView.shouldReAddOnDetach = forRefresh
                wm.removeView(immersiveHelperView)
            } catch (e: Exception) {}
        }
    }

    fun toggleGestureBar() {
        if (!IntroActivity.needsToRun(this)) {
            val shown = isPillShown()
            setGestureState(!shown)
            if (shown) removeBar()
            else addBar()

            BaseProvider.sendUpdate(this)
        }
    }

    fun toggleNavState(hidden: Boolean = Utils.shouldUseOverscanMethod(this)) {
        if (IntroActivity.hasWss(this)) {
            setNavState(!hidden)

            if (hidden) showNav()
            else hideNav()

            BaseProvider.sendUpdate(this)
        } else {
            IntroActivity.start(this, Bundle().apply { putBoolean(IntroActivity.EXTRA_WSS_ONLY, true) })
        }
    }

    fun toggleImmersiveWhenNavHidden() {
        val enabled = Utils.useImmersiveWhenNavHidden(this)
        prefs.edit().putBoolean("use_immersive_mode_when_nav_hidden", !enabled).apply()
    }

    /**
     * Check if NoBar is currently active
     * @return true if active
     */
    fun areGesturesActivated() = prefs.getBoolean("is_active", false)

    fun isPillShown() = areGesturesActivated() && pillShown

    /**
     * Check if the navbar is currently hidden
     * @return true if hidden
     */
    fun isNavBarHidden(): Boolean {
        val overscan = getOverscan()

        return overscan.bottom < 0 || overscan.top < 0 || overscan.left < 0 || overscan.right < 0
    }

    /**
     * Hide the navbar
     */
    fun hideNav(callListeners: Boolean = true) {
        logicHandler.post {
            if (Utils.shouldUseOverscanMethod(this)
                    && disabledNavReasonManager.isEmpty()
                    && IntroActivity.hasWss(this)) {
                addImmersiveHelper()

                if (!Utils.useRot270Fix(this)
                        && !Utils.useTabletMode(this)
                        && !Utils.useRot180Fix(this))
                    IWindowManager.setOverscan(0, 0, 0, -getAdjustedNavBarHeight())
                else {
                    uiHandler.handleRot()
                }
                Utils.forceNavBlack(this)
                if (Utils.checkTouchWiz(this) && !Utils.useImmersiveWhenNavHidden(this)) {
                    Utils.forceTouchWizNavEnabled(this)
                }

                handler.post { if (callListeners) navbarListeners.forEach { it.onNavStateChange(true) } }
                navHidden = true

                ContextCompat.startForegroundService(this, Intent(this, ForegroundService::class.java))
            }
        }
    }

    /**
     * Show the navbar
     */
    fun showNav(callListeners: Boolean = true, removeImmersive: Boolean = true) {
        if (IntroActivity.hasWss(this)) {
            if (removeImmersive && Utils.useImmersiveWhenNavHidden(this)) immersiveHelperView.exitNavImmersive()

            handler.post { if (callListeners) navbarListeners.forEach { it.onNavStateChange(false) } }

            IWindowManager.setOverscan(0, 0, 0, 0)
            Utils.clearBlackNav(this)

            if (Utils.checkTouchWiz(this)) {
                Utils.undoForceTouchWizNavEnabled(this)
            }

            navHidden = false

            if (!areGesturesActivated()) {
                stopService(Intent(this, ForegroundService::class.java))
                removeImmersiveHelper()
            }

            if (!pillShown) removeImmersiveHelper()
        }
    }

    fun ensureRootServiceBound() = bindService(rootServiceIntent, rootConnection, 0)

    /**
     * Get the current screen overscan
     * @return the overscan as a Rect
     */
    fun getOverscan(): Rect {
        val rect = Rect(0, 0, 0, 0)

        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getOverscanInsets(rect)

        return rect
    }

    /**
     * Save the current NoBar gesture state to preferences
     */
    fun setGestureState(activated: Boolean) = prefs.edit().putBoolean("is_active", activated).apply()

    fun setNavState(hidden: Boolean) {
        Utils.setShouldUseOverscanMethod(this, hidden)
    }

    fun getAdjustedNavBarHeight() =
            Utils.getNavBarHeight(this) - if (Utils.useFullOverscan(this)) 0 else 1

    fun addBarInternal(isRefresh: Boolean = true) {
        handler.post {
            try {
                bar.shouldReAddOnDetach = isRefresh
                if (isRefresh) wm.removeView(bar)
                else addBarInternalUnconditionally()
            } catch (e: Exception) {
                addBarInternalUnconditionally()
            }

            addImmersiveHelper()
            ContextCompat.startForegroundService(this, Intent(this, ForegroundService::class.java))

            if (Utils.shouldUseRootCommands(this)) {
                startService(rootServiceIntent)
                ensureRootServiceBound()
            }
        }
    }

    private val screenOnNotif by lazy {
        NotificationCompat.Builder(this, "nobar-screen-on")
                .setContentTitle(resources.getText(R.string.screen_timeout))
                .setContentText(resources.getText(R.string.screen_timeout_msg))
                .setSmallIcon(R.drawable.ic_navgest)
                .setPriority(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
    }

    fun toggleScreenOn() {
        bar.handler?.post {
            val hasScreenOn = bar.toggleScreenOn()

            if (hasScreenOn) {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                    nm.createNotificationChannel(NotificationChannel("nobar-screen-on", resources.getString(R.string.screen_timeout), NotificationManager.IMPORTANCE_LOW))
                }

                nm.notify(100, screenOnNotif.build())
            } else {
                nm.cancel(100)
            }
        }
    }
    private fun addBarInternalUnconditionally() {
        try {
            wm.addView(bar, bar.params)
        } catch (e: Exception) {}
    }

    /**
     * Listen for changes in the screen state and handle appropriately
     */
    inner class ScreenStateHandler : BroadcastReceiver() {
        fun register() {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_BOOT_COMPLETED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                filter.addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            }
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_REBOOT)
            filter.addAction(Intent.ACTION_SHUTDOWN)

            registerReceiver(this, filter)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            if (!IntroActivity.needsToRun(this@App)) {
                handler.postDelayed({
                    val action = intent?.action
                    when (action) {
                        Intent.ACTION_REBOOT,
                        Intent.ACTION_SHUTDOWN,
                        Intent.ACTION_SCREEN_OFF -> {
                            if (Utils.shouldntKeepOverscanOnLock(this@App)) disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.KEYGUARD)
                        }
                        Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_BOOT_COMPLETED,
                        Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                            if (Utils.isOnKeyguard(this@App)) {
                                if (Utils.shouldntKeepOverscanOnLock(this@App)) disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.KEYGUARD)
                            } else {
                                disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.KEYGUARD)
                            }

                            if (areGesturesActivated()) addBar()
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.KEYGUARD)
                            if (areGesturesActivated()) addBar()
                        }
                    }
                }, 50)
            }
        }
    }

    /**
     * Listen for changes in Car Mode (Android Auto, etc)
     * We want to disable NoBar when Car Mode is active
     */
    inner class CarModeHandler : BroadcastReceiver() {
        fun register() {
            val filter = IntentFilter()
            filter.addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
            filter.addAction(UiModeManager.ACTION_EXIT_CAR_MODE)

            registerReceiver(this, filter)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UiModeManager.ACTION_ENTER_CAR_MODE -> {
                    if (Utils.enableInCarMode(this@App)) {
                        if (pillShown) bar.params.height = Utils.getCustomHeight(this@App) * 2
                    } else {
                        if (pillShown) removeBar()
                        disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        disabledImmReasonManager.add(DisabledReasonManager.NavBarReasons.CAR_MODE)
                    }
                }

                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    if (Utils.enableInCarMode(this@App)) {
                        if (pillShown) bar.params.height = Utils.getCustomHeight(this@App)
                    } else {
                        if (areGesturesActivated()) addBar()
                        disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        disabledImmReasonManager.remove(DisabledReasonManager.NavBarReasons.CAR_MODE)
                    }
                }
            }

            if (pillShown) {
                bar.updateLayout(bar.params)
            }

            if (Utils.shouldUseOverscanMethod(this@App)) {
                if (Utils.enableInCarMode(this@App)) hideNav()
            }
        }
    }

    /**
     * Basically does everything that needs to be dynamically managed
     * Listens for changes in Immersive Mode and adjusts appropriately
     * Listens for changes in rotation and adjusts appropriately
     * Listens for TouchWiz navbar hiding and coloring and adjusts appropriately
     * //TODO: More work may be needed on immersive detection
     */
    inner class UIHandler : ContentObserver(logicHandler), View.OnSystemUiVisibilityChangeListener, ViewTreeObserver.OnGlobalLayoutListener, (Boolean) -> Unit {
        private var oldRot = Surface.ROTATION_0
        private var isActing = false
        private var asDidContainApp: Boolean = false

        fun register() {
            logicHandler.post {
                contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.POLICY_CONTROL), true, this)
                contentResolver.registerContentObserver(Settings.Global.getUriFor("navigationbar_color"), true, this)
                contentResolver.registerContentObserver(Settings.Global.getUriFor("navigationbar_current_color"), true, this)
                contentResolver.registerContentObserver(Settings.Global.getUriFor("navigationbar_use_theme_default"), true, this)
                contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, this)

                bar.immersiveNav = immersiveHelperView.isNavImmersive()

                asDidContainApp = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
            }
        }

        fun setNodeInfoAndUpdate(info: AccessibilityEvent?) {
            logicHandler.post {
                try {
                    handleNewEvent(info ?: return@post)
                } catch (e: NullPointerException) {}
            }
        }

        private var oldPName: String? = null

        @SuppressLint("WrongConstant")
        private fun handleNewEvent(info: AccessibilityEvent) {
            val source: AccessibilityNodeInfo? = info.source
            val pName = source?.packageName?.toString()

            if (pName != oldPName) {
                oldPName = pName

                if (pName == "com.android.systemui" && info.className?.contains("TextView") == false) {
                    if (Utils.shouldUseOverscanMethod(this@App)
                            && Utils.useImmersiveWhenNavHidden(this@App)) {
                        immersiveHelperView.tempForcePolicyControlForRecents()
                    }
                } else {
                    if (Utils.shouldUseOverscanMethod(this@App)
                            && Utils.useImmersiveWhenNavHidden(this@App)) {
                        immersiveHelperView.putBackOldImmersive()
                    }
                }
                runNewNodeInfo(pName)
            } else {
                onGlobalLayout()
            }

            source?.recycle()
        }

        private fun runNewNodeInfo(pName: String?) {
            if (pName != null && pName != packageName) {
                val navArray = ArrayList<String>().apply { Utils.loadBlacklistedNavPackages(this@App, this) }
                if (navArray.contains(pName)) {
                    disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.NAV_BLACKLIST)
                } else {
                    disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.NAV_BLACKLIST)
                }

                val barArray = ArrayList<String>().apply { Utils.loadBlacklistedBarPackages(this@App, this) }
                if (barArray.contains(pName)) {
                    disabledBarReasonManager.add(DisabledReasonManager.PillReasons.BLACKLIST)
                } else {
                    disabledBarReasonManager.remove(DisabledReasonManager.PillReasons.BLACKLIST)
                }


                val immArray = ArrayList<String>().apply { Utils.loadBlacklistedImmPackages(this@App, this) }
                if (immArray.contains(pName)) {
                    disabledImmReasonManager.add(DisabledReasonManager.ImmReasons.BLACKLIST)
                } else {
                    disabledImmReasonManager.remove(DisabledReasonManager.ImmReasons.BLACKLIST)
                }

                val windowArray = ArrayList<String>().apply { Utils.loadOtherWindowApps(this@App, this) }
                if (windowArray.contains(pName)) {
                    if (!isInOtherWindowApp) {
                        addBar(false)
                        isInOtherWindowApp = true
                    }
                } else if (isInOtherWindowApp) isInOtherWindowApp = false
            }

            onGlobalLayout()
        }

        override fun invoke(isImmersive: Boolean) {
            logicHandler.post {
                handleImmersiveChange(isImmersive)
            }
        }

        private var countOfGlobal = 0

        @SuppressLint("WrongConstant")
        override fun onGlobalLayout() {
            keyboardShown = imm.inputMethodWindowVisibleHeight > 0

            logicHandler.post {
                if (Utils.checkTouchWiz(this@App)) {
                    try {
                        val SemCocktailBarManager = Class.forName("com.samsung.android.cocktailbar.SemCocktailBarManager")

                        val manager = getSystemService("CocktailBarService")

                        val getCocktailBarWindowType = SemCocktailBarManager.getMethod("getCocktailBarWindowType")

                        val edgeType = getCocktailBarWindowType.invoke(manager).toString().toInt()

                        if (edgeType == EDGE_TYPE_ACTIVE) {
                            disabledImmReasonManager.add(DisabledReasonManager.ImmReasons.EDGE_SCREEN)
                        } else {
                            disabledImmReasonManager.remove(DisabledReasonManager.ImmReasons.EDGE_SCREEN)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (!isNavBarHidden() && Utils.shouldUseOverscanMethod(this@App)) hideNav()

                val rot = wm.defaultDisplay.rotation
                if (oldRot != rot) {
                    handleRot()

                    oldRot = rot
                }

                if (countOfGlobal == 0) {
                    removeImmersiveHelper(true)
                    countOfGlobal++
                } else if (countOfGlobal > 1) countOfGlobal = 0

                if (!isActing) {
                    isActing = true
                    if (isPillShown()) {
                        try {
                            if (!Utils.useImmersiveWhenNavHidden(this@App)) immersiveHelperView.exitNavImmersive()

                            bar.immersiveNav = immersiveHelperView.isNavImmersive() && !keyboardShown

                            handler.post {
                                if (Utils.hidePillWhenKeyboardShown(this@App)) {
                                    if (keyboardShown) bar.hidePill(true, HiddenPillReasonManager.KEYBOARD)
                                    else if (bar.hiddenPillReasons.onlyContains(HiddenPillReasonManager.KEYBOARD)) bar.showPill(HiddenPillReasonManager.KEYBOARD)
                                }
                            }

                            if (disabledImmReasonManager.isEmpty()) {
                                if (Utils.shouldUseOverscanMethod(this@App)
                                        && Utils.useImmersiveWhenNavHidden(this@App)) immersiveHelperView.enterNavImmersive()
                            } else {
                                immersiveHelperView.exitNavImmersive()
                            }

                            if (Utils.shouldUseOverscanMethod(this@App)) {
                                if (disabledNavReasonManager.isEmpty()) {
                                    hideNav()
                                } else {
                                    showNav()
                                }
                            } else {
                                showNav()
                            }

                            if (disabledBarReasonManager.isEmpty()) {
                                if (areGesturesActivated()
                                        && !pillShown) addBar(false)
                            } else {
                                removeBar(false)
                            }

                        } catch (e: NullPointerException) {}
                    }
                    isActing = false
                }
            }
        }

        override fun onSystemUiVisibilityChange(visibility: Int) {
            logicHandler.post {
                handleImmersiveChange(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
                        || visibility and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0
                        || visibility and 7 != 0)
            }
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            when (uri) {
                Settings.Global.getUriFor(Settings.Global.POLICY_CONTROL) -> {
                    handleImmersiveChange(immersiveHelperView.isFullImmersive())
                }

                Settings.Global.getUriFor("navigationbar_color"),
                Settings.Global.getUriFor("navigationbar_current_color"),
                Settings.Global.getUriFor("navigationbar_use_theme_default") -> {
                    if (isNavBarHidden()
                            && IntroActivity.hasWss(this@App)) Utils.forceNavBlack(this@App)
                }

                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) -> {
                    val contains = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
                    val changed = asDidContainApp != contains

                    if (changed) {
                        asDidContainApp = contains
                        if (wm.defaultDisplay.state == Display.STATE_ON) {
                            logicHandler.postDelayed({
                                if (contains && areGesturesActivated()) {
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                                            || Settings.canDrawOverlays(this@App)) addBar(false)
                                }

                                if (contains) IntroActivity.start(this@App)
                            }, 100)
                        }
                    }
                }
            }
        }

        private fun handleImmersiveChange(isImmersive: Boolean) {
            if (!IntroActivity.needsToRun(this@App)) {
                handler.post {
                    bar.isImmersive = isImmersive
                    val hideInFullScreen = Utils.hideInFullscreen(this@App)
                    if (isImmersive) {
                        if (hideInFullScreen) bar.hidePill(true, HiddenPillReasonManager.FULLSCREEN)
                    } else {
                        if (bar.isHidden) bar.showPill(HiddenPillReasonManager.FULLSCREEN)
                    }
                }
            }
        }

        fun handleRot() {
            logicHandler.post {
                if (pillShown) {
                    try {
                        bar.params.x = bar.getAdjustedHomeX()
                        bar.params.y = bar.getAdjustedHomeY()
                        bar.params.width = Utils.getCustomWidth(this@App)
                        bar.params.height = Utils.getCustomHeight(this@App)
                        bar.updateLayout(bar.params)
                    } catch (e: NullPointerException) {}
                }

                if (Utils.shouldUseOverscanMethod(this@App)) {
                    if (Utils.useRot270Fix(this@App)) handle270()
                    if (Utils.useRot180Fix(this@App)) handle180()
                    if (Utils.useTabletMode(this@App)) handleTablet()
                }
            }
        }

        private fun handle270() {
            if (wm.defaultDisplay.rotation == Surface.ROTATION_270) {
                IWindowManager.setOverscan(0, -getAdjustedNavBarHeight(), 0, 0)
            } else {
                IWindowManager.setOverscan(0, 0, 0, -getAdjustedNavBarHeight())
            }
        }

        private fun handle180() {
            handle270()
        }

        private fun handleTablet() {
            if (Utils.shouldUseOverscanMethod(this@App)) {
                when (wm.defaultDisplay.rotation) {
                    Surface.ROTATION_0 -> {
                        IWindowManager.setOverscan(0, 0, 0, -getAdjustedNavBarHeight())
                    }

                    Surface.ROTATION_90 -> {
                        IWindowManager.setOverscan(-getAdjustedNavBarHeight(), 0, 0, 0)
                    }

                    Surface.ROTATION_180 -> {
                        IWindowManager.setOverscan(0, -getAdjustedNavBarHeight(), 0 ,0)
                    }

                    Surface.ROTATION_270 -> {
                        IWindowManager.setOverscan(0, 0, -getAdjustedNavBarHeight(), 0)
                    }
                }
            }
        }
    }

    /**
     * Listen to see if the premium add-on has been installed/uninstalled, and refresh the premium state
     */
    inner class PremiumInstallListener : BroadcastReceiver() {
        fun register() {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_PACKAGE_ADDED)
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            filter.addDataScheme("package")

            registerReceiver(this, filter)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_ADDED
                    || intent?.action == Intent.ACTION_PACKAGE_CHANGED
                    || intent?.action == Intent.ACTION_PACKAGE_REPLACED
                    || intent?.action == Intent.ACTION_PACKAGE_REMOVED) {
                if (intent.dataString.contains("com.xda.nobar.premium")) {
                    refreshPremium()
                }
            }
        }
    }
}