package com.xda.nobar

import android.Manifest
import android.animation.Animator
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.crashlytics.android.Crashlytics
import com.crashlytics.android.core.CrashlyticsCore
import com.github.anrwatchdog.ANRWatchDog
import com.xda.nobar.activities.helpers.RequestPermissionsActivity
import com.xda.nobar.activities.ui.IntroActivity
import com.xda.nobar.interfaces.OnGestureStateChangeListener
import com.xda.nobar.interfaces.OnLicenseCheckResultListener
import com.xda.nobar.interfaces.OnNavBarHideStateChangeListener
import com.xda.nobar.providers.BaseProvider
import com.xda.nobar.root.RootWrapper
import com.xda.nobar.services.Actions
import com.xda.nobar.util.*
import com.xda.nobar.util.helpers.*
import com.xda.nobar.views.BarView
import com.xda.nobar.views.NavBlackout
import io.fabric.sdk.android.Fabric
import io.fabric.sdk.android.InitializationCallback
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async


/**
 * Centralize important stuff in the App class, so we can be sure to have an instance of it
 */
@Suppress("DEPRECATION")
class App : Application(), SharedPreferences.OnSharedPreferenceChangeListener, AppOpsManager.OnOpChangedListener {
    companion object {
        const val EDGE_TYPE_ACTIVE = 2

        private const val ACTION_MINIVIEW_SETTINGS_CHANGED = "com.lge.android.intent.action.MINIVIEW_SETTINGS_CHANGED"
    }

    val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    val um by lazy { getSystemService(Context.UI_MODE_SERVICE) as UiModeManager }
    val appOps by lazy { getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager }
    val nm by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val imm by lazy { getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    val dm by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    val rootWrapper by lazy { RootWrapper(this) }
    val blackout by lazy { NavBlackout(this) }

    private val stateHandler = ScreenStateHandler()
    private val carModeHandler = CarModeHandler()
    private val premiumHelper by lazy {
        PremiumHelper(this, OnLicenseCheckResultListener { valid, reason ->
            isValidPremium = valid
            prefManager.validPrem = valid

            licenseCheckListeners.forEach { it.onResult(valid, reason) }
        })
    }

    private val premiumInstallListener = PremiumInstallListener()
    private val permissionListener = PermissionReceiver()
    private val displayChangeListener = DisplayChangeListener()
    private val miniViewListener = MiniViewListener()

    private val prefChangeListeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    val immersiveHelperManager by lazy { ImmersiveHelperManager(this) }
    val screenOffHelper by lazy { ScreenOffHelper(this) }

    private var isInOtherWindowApp = false

    var navHidden = false
    var pillShown = false
    var helperAdded = false
    var keyboardShown = false
    var isValidPremium = false
        get() = field || BuildConfig.DEBUG

    private val gestureListeners = ArrayList<OnGestureStateChangeListener>()
    private val navbarListeners = ArrayList<OnNavBarHideStateChangeListener>()
    private val licenseCheckListeners = ArrayList<OnLicenseCheckResultListener>()

    val uiHandler = UIHandler()

    val bar by lazy { BarView(this) }

    val disabledNavReasonManager = DisabledReasonManager()
    val disabledBarReasonManager = DisabledReasonManager()
    val disabledImmReasonManager = DisabledReasonManager()

    override fun onCreate() {
        super.onCreate()

        val core = CrashlyticsCore.Builder()
                .disabled(BuildConfig.DEBUG)
                .build()

        Fabric.with(
                Fabric.Builder(this).kits(
                        Crashlytics.Builder()
                                .core(core)
                                .build()
                ).initializationCallback(object : InitializationCallback<Fabric> {
                    override fun success(p0: Fabric?) {
                        val crashHandler = CrashHandler(Thread.getDefaultUncaughtExceptionHandler(), this@App)
                        Thread.setDefaultUncaughtExceptionHandler(crashHandler)
                    }

                    override fun failure(p0: java.lang.Exception?) {

                    }
                }).build()
        )

        if (prefManager.crashlyticsIdEnabled)
            Crashlytics.setUserIdentifier(prefManager.crashlyticsId)

        if (!prefManager.firstRun) {
            isSuAsync(mainHandler) {
                if (it) rootWrapper.onCreate()
            }
        }

        val watchDog = ANRWatchDog()
        watchDog.setReportMainThreadOnly()
        watchDog.start()
        watchDog.setANRListener {
            Crashlytics.logException(it)
        }

        if (IntroActivity.needsToRun(this)) {
            IntroActivity.start(this)
        }

        stateHandler.register()
        uiHandler.register()
        carModeHandler.register()
        premiumInstallListener.register()
        permissionListener.register()
        dm.registerDisplayListener(displayChangeListener, logicHandler)
        miniViewListener.register()

        isValidPremium = prefManager.validPrem

        prefManager.registerOnSharedPreferenceChangeListener(this)

        refreshPremium()

        if (prefManager.isActive
                && !IntroActivity.needsToRun(this)) {
            addBar()
        }

        if (prefManager.useRot270Fix
                || prefManager.useRot180Fix
                || prefManager.useTabletMode
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            uiHandler.handleRot(true)

        if (!IntroActivity.needsToRun(this)) {
            addImmersiveHelper()
            uiHandler.onGlobalLayout()
            immersiveHelperManager.addOnGlobalLayoutListener(uiHandler)
            immersiveHelperManager.immersiveListener = uiHandler
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, this)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        when (key) {
            PrefManager.IS_ACTIVE -> {
                gestureListeners.forEach { it.onGestureStateChange(bar, prefManager.isActive) }
            }
            PrefManager.HIDE_NAV -> {
                navbarListeners.forEach { it.onNavStateChange(prefManager.shouldUseOverscanMethod) }
            }
            PrefManager.ROT270_FIX -> {
                uiHandler.handleRot(true)
            }
            PrefManager.ROT180_FIX -> {
                uiHandler.handleRot(true)
            }
            PrefManager.TABLET_MODE -> {
                uiHandler.handleRot(true)
            }
            PrefManager.ENABLE_IN_CAR_MODE -> {
                val enabled = prefManager.enableInCarMode
                if (um.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
                    if (enabled) {
                        disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        disabledImmReasonManager.remove(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        if (prefManager.isActive && !pillShown) addBar(false)
                    } else {
                        disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        disabledImmReasonManager.add(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        if (prefManager.isActive && !pillShown) removeBar(false)
                    }
                }
            }
            PrefManager.USE_IMMERSIVE_MODE_WHEN_NAV_HIDDEN -> {
                BaseProvider.sendUpdate(this)
            }
            PrefManager.HIDE_PILL_ON_KEYBOARD -> {
                uiHandler.onGlobalLayout()
            }
            PrefManager.FULL_OVERSCAN -> {
                if (prefManager.shouldUseOverscanMethod) hideNav(false)
            }
        }

        prefChangeListeners.forEach { it.onSharedPreferenceChanged(sharedPreferences, key) }
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

    fun removeNavBarHideListener(listener: OnNavBarHideStateChangeListener) = navbarListeners.remove(listener)

    fun addLicenseCheckListener(listener: OnLicenseCheckResultListener) = licenseCheckListeners.add(listener)

    fun removeLicenseCheckListener(listener: OnLicenseCheckResultListener) = licenseCheckListeners.remove(listener)

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefChangeListeners.add(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefChangeListeners.remove(listener)
    }

    /**
     * Add the pill to the screen
     */
    fun addBar(callListeners: Boolean = true) {
        mainHandler.post {
            if (disabledBarReasonManager.isEmpty() && !pillShown) {
                if (callListeners) gestureListeners.forEach { it.onGestureStateChange(bar, true) }

                addBarInternal()
            }
        }
    }

    fun addImmersiveHelper() {
        mainHandler.post {
            if (!helperAdded) immersiveHelperManager.add()
        }
    }

    /**
     * Remove the pill from the screen
     */
    fun removeBar(callListeners: Boolean = true) {
        mainHandler.post {
            if (callListeners) gestureListeners.forEach { it.onGestureStateChange(bar, false) }

            bar.hide(object : Animator.AnimatorListener {
                override fun onAnimationCancel(animation: Animator?) {}

                override fun onAnimationRepeat(animation: Animator?) {}

                override fun onAnimationStart(animation: Animator?) {}

                override fun onAnimationEnd(animation: Animator?) {
                    try {
                        bar.shouldReAddOnDetach = false
                        wm.removeView(bar)
                    } catch (e: Exception) {
                    }

                    if (!navHidden) removeImmersiveHelper()
                }
            })
        }
    }

    fun removeImmersiveHelper() {
        mainHandler.post {
            immersiveHelperManager.remove()
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

    fun toggleNavState(hidden: Boolean = prefManager.shouldUseOverscanMethod) {
        runSecureSettingsAction {
            prefManager.shouldUseOverscanMethod = !hidden

            if (hidden) showNav()
            else hideNav()

            BaseProvider.sendUpdate(this)
            true
        }
    }

    fun toggleImmersiveWhenNavHidden() {
        prefManager.useImmersiveWhenNavHidden = !prefManager.useImmersiveWhenNavHidden
    }

    fun isPillShown() = prefManager.isActive && pillShown

    /**
     * Hide the navbar
     */
    fun hideNav(callListeners: Boolean = true) {
        if (prefManager.shouldUseOverscanMethod
                && disabledNavReasonManager.isEmpty()
                && hasWss) {
            addImmersiveHelper()

            uiHandler.handleRot(true)

            val fullOverscan = prefManager.useFullOverscan
            if (fullOverscan == blackNav) blackNav = !fullOverscan

            if (isTouchWiz && !prefManager.useImmersiveWhenNavHidden) {
                touchWizNavEnabled = true
            }

            navHidden = true
        }

        mainHandler.post { if (callListeners) navbarListeners.forEach { it.onNavStateChange(true) } }
    }

    /**
     * Show the navbar
     */
    fun showNav(callListeners: Boolean = true, removeImmersive: Boolean = true) {
        if (hasWss) {
            if (removeImmersive && prefManager.useImmersiveWhenNavHidden)
                immersiveHelperManager.exitNavImmersive()

            mainHandler.post { if (callListeners) navbarListeners.forEach { it.onNavStateChange(false) } }

            IWindowManager.setOverscanAsync(0, 0, 0, 0)

            if (blackNav) blackNav = false

            if (isTouchWiz) {
                touchWizNavEnabled = false
            }

            navHidden = false

            if (!prefManager.isActive) {
                removeImmersiveHelper()
            }
        }
    }

    /**
     * Save the current NoBar gesture state to preferences
     */
    fun setGestureState(activated: Boolean) {
        prefManager.isActive = activated
    }

    fun addBarInternal(isRefresh: Boolean = true) {
        try {
            bar.shouldReAddOnDetach = isRefresh
            if (isRefresh) wm.removeView(bar)
            else addBarInternalUnconditionally()
        } catch (e: Exception) {
            addBarInternalUnconditionally()
        }

        addImmersiveHelper()
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

    private fun addBarInternalUnconditionally() {
        try {
            wm.addView(bar, bar.params)
        } catch (e: Exception) {
        }
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
                mainHandler.postDelayed({
                    when (intent?.action) {
                        Intent.ACTION_REBOOT,
                        Intent.ACTION_SHUTDOWN,
                        Intent.ACTION_SCREEN_OFF -> {
                            if (prefManager.shouldntKeepOverscanOnLock) disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.KEYGUARD)
                            bar.forceActionUp()

                            uiHandler.onGlobalLayout()
                        }
                        Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_BOOT_COMPLETED,
                        Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                            if (isOnKeyguard) {
                                if (prefManager.shouldntKeepOverscanOnLock) disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.KEYGUARD)
                            } else {
                                disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.KEYGUARD)
                            }

                            if (prefManager.isActive) addBar(false)

                            uiHandler.onGlobalLayout()
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.KEYGUARD)
                            if (prefManager.isActive) addBar(false)

                            uiHandler.onGlobalLayout()
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
                    if (prefManager.enableInCarMode) {
                        if (pillShown) {
                            bar.params.height = prefManager.customHeight * 2
                            bar.updateLayout()
                        }
                    } else {
                        if (pillShown) removeBar()
                        disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        disabledImmReasonManager.add(DisabledReasonManager.NavBarReasons.CAR_MODE)
                    }
                }

                UiModeManager.ACTION_EXIT_CAR_MODE -> {
                    if (prefManager.enableInCarMode) {
                        if (pillShown) {
                            bar.params.height = prefManager.customHeight
                            bar.updateLayout()
                        }
                    } else {
                        if (prefManager.isActive) addBar()
                        disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.CAR_MODE)
                        disabledImmReasonManager.remove(DisabledReasonManager.NavBarReasons.CAR_MODE)
                    }
                }
            }

            if (pillShown) {
                bar.updateLayout()
            }

            if (prefManager.shouldUseOverscanMethod) {
                if (prefManager.enableInCarMode) hideNav()
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
    inner class UIHandler : ContentObserver(logicHandler), ViewTreeObserver.OnGlobalLayoutListener, (Boolean) -> Unit {
        private var oldRot = Surface.ROTATION_0
        private var asDidContainApp: Boolean = false

        fun register() {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(POLICY_CONTROL), true, this)
            contentResolver.registerContentObserver(Settings.Global.getUriFor("navigationbar_hide_bar_enabled"), true, this)
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, this)

            bar.immersiveNav = immersiveHelperManager.isNavImmersive()

            asDidContainApp = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
        }

        fun setNodeInfoAndUpdate(info: AccessibilityEvent?) {
            GlobalScope.async {
                try {
                    handleNewEvent(info ?: return@async)
                } catch (e: NullPointerException) {}
            }
        }

        private var oldPName: String? = null

        @SuppressLint("WrongConstant")
        private fun handleNewEvent(info: AccessibilityEvent) {
            val pName = info.packageName.toString()

            if (pName != oldPName && pName != packageName) {
                oldPName = pName

                if (pName == "com.android.systemui" && info.className?.contains("TextView") == false) {
                    if (prefManager.shouldUseOverscanMethod
                            && prefManager.useImmersiveWhenNavHidden) {
                        immersiveHelperManager.tempForcePolicyControlForRecents()
                    }
                } else {
                    if (prefManager.shouldUseOverscanMethod
                            && prefManager.useImmersiveWhenNavHidden) {
                        immersiveHelperManager.putBackOldImmersive()
                    }
                }
                runNewNodeInfo(pName)
            }
        }

        private fun runNewNodeInfo(pName: String?) {
            if (pName != null) {
                val navArray = ArrayList<String>().apply { prefManager.loadBlacklistedNavPackages(this) }
                if (navArray.contains(pName)) {
                    disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.NAV_BLACKLIST)
                } else {
                    disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.NAV_BLACKLIST)
                }

                val barArray = ArrayList<String>().apply { prefManager.loadBlacklistedBarPackages(this) }
                if (barArray.contains(pName)) {
                    disabledBarReasonManager.add(DisabledReasonManager.PillReasons.BLACKLIST)
                } else {
                    disabledBarReasonManager.remove(DisabledReasonManager.PillReasons.BLACKLIST)
                }


                val immArray = ArrayList<String>().apply { prefManager.loadBlacklistedImmPackages(this) }
                if (immArray.contains(pName)) {
                    disabledImmReasonManager.add(DisabledReasonManager.ImmReasons.BLACKLIST)
                } else {
                    disabledImmReasonManager.remove(DisabledReasonManager.ImmReasons.BLACKLIST)
                }

                val windowArray = ArrayList<String>().apply { prefManager.loadOtherWindowApps(this) }
                if (windowArray.contains(pName)) {
                    if (!isInOtherWindowApp && prefManager.isActive) {
                        addBar(false)
                        isInOtherWindowApp = true
                    }
                } else if (isInOtherWindowApp) isInOtherWindowApp = false
            }
        }

        override fun invoke(isImmersive: Boolean) {
            handleImmersiveChange(isImmersive)
        }

        @SuppressLint("WrongConstant")
        override fun onGlobalLayout() {
            if (pillShown) {
                bar.updatePositionAndDimens()
            }

            handleRot()

            GlobalScope.async {
                if (!bar.isCarryingOutTouchAction) {
                    keyboardShown = imm.inputMethodWindowVisibleHeight > 0

                    if (prefManager.showNavWithKeyboard) {
                        if (keyboardShown) {
                            showNav(false)
                            disabledNavReasonManager.add(DisabledReasonManager.NavBarReasons.KEYBOARD)
                        } else if (prefManager.shouldUseOverscanMethod) {
                            disabledNavReasonManager.remove(DisabledReasonManager.NavBarReasons.KEYBOARD)
                        }
                    }

                    if (!prefManager.dontMoveForKeyboard) {
                        var changed = false

                        if (keyboardShown) {
                            if (bar.params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0) {
                                bar.params.flags = bar.params.flags and
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
                                changed = true
                            }
                        } else {
                            if (bar.params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN == 0) {
                                bar.params.flags = bar.params.flags or
                                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                changed = true
                            }
                        }

                        if (changed) bar.updateLayout()
                    }

                    if (isTouchWiz) {
                        try {
                            val semCocktailBarManagerClass = Class.forName("com.samsung.android.cocktailbar.SemCocktailBarManager")

                            val manager = getSystemService("CocktailBarService")

                            val getCocktailBarWindowType = semCocktailBarManagerClass.getMethod("getCocktailBarWindowType")

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

                    if (prefManager.origBarInFullscreen) {
                        if (immersiveHelperManager.isFullImmersive()) {
                            showNav(callListeners = false, removeImmersive = false)
                        } else {
                            hideNav(false)
                        }
                    }

                    if (isPillShown()) {
                        try {
                            if (!prefManager.useImmersiveWhenNavHidden) immersiveHelperManager.exitNavImmersive()

                            bar.immersiveNav = immersiveHelperManager.isNavImmersive() && !keyboardShown

                            if (prefManager.hidePillWhenKeyboardShown) {
                                if (keyboardShown) bar.scheduleHide(HiddenPillReasonManager.KEYBOARD)
                                else bar.showPill(HiddenPillReasonManager.KEYBOARD)
                            }

                            if (disabledImmReasonManager.isEmpty()) {
                                if (prefManager.shouldUseOverscanMethod
                                        && prefManager.useImmersiveWhenNavHidden) immersiveHelperManager.enterNavImmersive()
                            } else {
                                immersiveHelperManager.exitNavImmersive()
                            }

                            if (disabledBarReasonManager.isEmpty()) {
                                if (prefManager.isActive
                                        && !pillShown) addBar(false)
                            } else {
                                removeBar(false)
                            }

                        } catch (e: NullPointerException) {
                        }
                    }

                    if (prefManager.shouldUseOverscanMethod) {
                        if (disabledNavReasonManager.isEmpty()) {
                            hideNav()
                        } else {
                            showNav()
                        }
                    } else {
                        showNav()
                    }
                }
            }
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            GlobalScope.async {
                when (uri) {
                    Settings.Global.getUriFor(POLICY_CONTROL) -> {
                        handleImmersiveChange(immersiveHelperManager.isFullImmersive())
                    }

                    Settings.Global.getUriFor("navigationbar_hide_bar_enabled") -> {
                        if (prefManager.isActive) {
                            touchWizNavEnabled = !immersiveHelperManager.isNavImmersive()
                        }
                    }

                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) -> {
                        val contains = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
                        val changed = asDidContainApp != contains

                        if (changed) {
                            asDidContainApp = contains
                            if (wm.defaultDisplay.state == Display.STATE_ON) {
                                mainHandler.postDelayed({
                                    if (contains && prefManager.isActive) {
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
        }

        private fun handleImmersiveChange(isImmersive: Boolean) {
            if (!IntroActivity.needsToRun(this@App)) {
                bar.isImmersive = isImmersive

                val hideInFullScreen = prefManager.hideInFullscreen
                val fadeInFullScreen = prefManager.fullscreenFade

                if (isImmersive) {
                    if (hideInFullScreen && !fadeInFullScreen) bar.scheduleHide(HiddenPillReasonManager.FULLSCREEN)
                    if (fadeInFullScreen && !hideInFullScreen) bar.scheduleFade(prefManager.fullscreenFadeTime)
                } else {
                    bar.showPill(HiddenPillReasonManager.FULLSCREEN)
                    bar.scheduleUnfade()
                }
            }
        }

        fun handleRot(overrideChange: Boolean = false) {
            val rot = wm.defaultDisplay.rotation

            if (oldRot != rot || overrideChange) {
                if (pillShown) {
                    bar.handleRotationOrAnchorUpdate()
                    bar.forceActionUp()
                }

                if (prefManager.shouldUseOverscanMethod) {
                    when {
                        prefManager.useRot270Fix ||
                                prefManager.useRot180Fix -> handle180Or270()
                        prefManager.useTabletMode -> handleTablet()
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> handlePie()
                        else -> handle0()
                    }

                    if (blackNav) blackout.add()
                }

                oldRot = rot
            }
        }

        private fun handle0() {
            IWindowManager.setOverscanAsync(0, 0, 0, -adjustedNavBarHeight)
        }

        private fun handle180Or270() {
            if (wm.defaultDisplay.rotation == Surface.ROTATION_270) {
                IWindowManager.setOverscanAsync(0, -adjustedNavBarHeight, 0, 0)
            } else {
                IWindowManager.setOverscanAsync(0, 0, 0, -adjustedNavBarHeight)
            }
        }

        private fun handleTablet() {
            if (prefManager.shouldUseOverscanMethod) {
                when (wm.defaultDisplay.rotation) {
                    Surface.ROTATION_0 -> {
                        IWindowManager.setOverscanAsync(0, 0, 0, -adjustedNavBarHeight)
                    }

                    Surface.ROTATION_90 -> {
                        IWindowManager.setOverscanAsync(-adjustedNavBarHeight, 0, 0, 0)
                    }

                    Surface.ROTATION_180 -> {
                        IWindowManager.setOverscanAsync(0, -adjustedNavBarHeight, 0, 0)
                    }

                    Surface.ROTATION_270 -> {
                        IWindowManager.setOverscanAsync(0, 0, -adjustedNavBarHeight, 0)
                    }
                }
            }
        }

        private fun handlePie() {
            if (prefManager.shouldUseOverscanMethod) {
                val navSide = IWindowManager.getNavBarPosition()
                val rotation = wm.defaultDisplay.rotation

                when (navSide) {
                    IWindowManager.NAV_BAR_LEFT -> {
                        when (rotation) {
                            Surface.ROTATION_0 -> {
                                IWindowManager.setOverscanAsync(-adjustedNavBarHeight, 0, 0, 0)
                            }

                            Surface.ROTATION_90 -> {
                                IWindowManager.setOverscanAsync(0, -adjustedNavBarHeight, 0, 0)
                            }

                            Surface.ROTATION_180 -> {
                                IWindowManager.setOverscanAsync(0, 0, -adjustedNavBarHeight, 0)
                            }

                            Surface.ROTATION_270 -> {
                                IWindowManager.setOverscanAsync(0, 0, 0, -adjustedNavBarHeight)
                            }
                        }
                    }

                    IWindowManager.NAV_BAR_RIGHT -> {
                        when (rotation) {
                            Surface.ROTATION_0 -> {
                                IWindowManager.setOverscanAsync(0, 0, -adjustedNavBarHeight, 0)
                            }

                            Surface.ROTATION_90 -> {
                                IWindowManager.setOverscanAsync(0, 0, 0, -adjustedNavBarHeight)
                            }

                            Surface.ROTATION_180 -> {
                                IWindowManager.setOverscanAsync(-adjustedNavBarHeight, 0, 0, 0)
                            }

                            Surface.ROTATION_270 -> {
                                IWindowManager.setOverscanAsync(0, -adjustedNavBarHeight, 0, 0)
                            }
                        }
                    }

                    IWindowManager.NAV_BAR_BOTTOM -> {
                        when (rotation) {
                            Surface.ROTATION_0 -> {
                                IWindowManager.setOverscanAsync(0, 0, 0, -adjustedNavBarHeight)
                            }

                            Surface.ROTATION_90 -> {
                                IWindowManager.setOverscanAsync(-adjustedNavBarHeight, 0, 0, 0)
                            }

                            Surface.ROTATION_180 -> {
                                IWindowManager.setOverscanAsync(0, -adjustedNavBarHeight, 0, 0)
                            }

                            Surface.ROTATION_270 -> {
                                IWindowManager.setOverscanAsync(0, 0, -adjustedNavBarHeight, 0)
                            }
                        }
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
                if (intent.dataString?.contains("com.xda.nobar.premium") == true) {
                    refreshPremium()
                }
            }
        }
    }

    inner class PermissionReceiver : BroadcastReceiver() {
        fun register() {
            val filter = IntentFilter()
            filter.addAction(RequestPermissionsActivity.ACTION_RESULT)
            LocalBroadcastManager.getInstance(this@App)
                    .registerReceiver(this, filter)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RequestPermissionsActivity.ACTION_RESULT -> {
                    val className = intent.getParcelableExtra<ComponentName>(RequestPermissionsActivity.EXTRA_CLASS_NAME)

                    when (className) {
                        ComponentName(this@App, BarView::class.java) -> {
                            val which = intent.getIntExtra(Actions.EXTRA_ACTION, -1)
                            val key = intent.getStringExtra(Actions.EXTRA_GESTURE) ?: return

                            bar.currentGestureDetector.actionHandler.handleAction(which, key)
                        }
                    }
                }
            }
        }
    }

    inner class DisplayChangeListener : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == wm.defaultDisplay.displayId) {
                val oldSize = realScreenSize
                val newSize = refreshScreenSize()

                if (oldSize != newSize) {
                    bar.updatePositionAndDimens()
                }
            }
        }

        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}
    }

    inner class MiniViewListener : BroadcastReceiver() {
        fun register() {
            val filter = IntentFilter()
            filter.addAction(ACTION_MINIVIEW_SETTINGS_CHANGED)

            registerReceiver(this, filter)
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_MINIVIEW_SETTINGS_CHANGED -> {
                    bar.forceActionUp()
                }
            }
        }
    }
}