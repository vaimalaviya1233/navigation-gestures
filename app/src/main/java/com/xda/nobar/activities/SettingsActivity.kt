package com.xda.nobar.activities

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.preference.*
import android.support.v7.app.AppCompatActivity
import android.view.MenuItem
import com.jaredrummler.android.colorpicker.ColorPreference
import com.pavelsikun.seekbarpreference.SeekBarPreference
import com.xda.nobar.R
import com.xda.nobar.prefs.CustomPreferenceCategory
import com.xda.nobar.prefs.PrefManager
import com.xda.nobar.prefs.SectionableListPreference
import com.xda.nobar.prefs.SeekBarSwitchPreference
import com.xda.nobar.util.ActionHolder
import com.xda.nobar.util.Utils
import java.util.*

/**
 * The configuration activity
 */
class SettingsActivity : AppCompatActivity() {
    companion object {
        const val REQ_APP = 10
        const val REQ_INTENT = 11
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        fragmentManager?.beginTransaction()?.replace(R.id.content, MainFragment())?.addToBackStack("main")?.commit()
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> {
                handleBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        handleBackPressed()
    }

    private fun handleBackPressed() {
        if (fragmentManager != null) {
            if (fragmentManager.backStackEntryCount > 1) {
                fragmentManager.popBackStack()
            } else {
                finish()
            }
        } else {
            finish()
        }
    }

    /**
     * Main settings page
     */
    class MainFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            addPreferencesFromResource(R.xml.prefs_main)
        }

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.settings)

            setListeners()
        }

        private fun setListeners() {
            val listener = Preference.OnPreferenceClickListener {
                val whichFrag = when (it.key) {
                    "gestures" -> GestureFragment()
                    "appearance" -> AppearanceFragment()
                    "behavior" -> BehaviorFragment()
                    "compatibility" -> CompatibilityFragment()
                    "experimental" -> ExperimentalFragment()
                    else -> null
                }

                if (whichFrag != null) fragmentManager?.beginTransaction()?.replace(R.id.content, whichFrag, it.key)?.addToBackStack(it.key)?.commit()
                true
            }

            findPreference("gestures").onPreferenceClickListener = listener
            findPreference("appearance").onPreferenceClickListener = listener
            findPreference("behavior").onPreferenceClickListener = listener
            findPreference("compatibility").onPreferenceClickListener = listener
            findPreference("experimental").onPreferenceClickListener = listener
        }
    }

    /**
     * Gesture preferences
     */
    class GestureFragment : BasePrefFragment(), SharedPreferences.OnSharedPreferenceChangeListener {
        override val resId = R.xml.prefs_gestures

        private val listPrefs = ArrayList<SectionableListPreference>()
        private val actionHolder by lazy { ActionHolder(activity) }

        private val sectionedScreen by lazy { preferenceManager.inflateFromResource(activity, R.xml.prefs_sectioned, null) }
        private val sectionedCategory by lazy { sectionedScreen.findPreference("section_gestures") as PreferenceCategory }
        private val sectionedCategoryHolder by lazy { findPreference("sectioned_pill_cat") as CustomPreferenceCategory }
        private val swipeUpCategory by lazy { findPreference("swipe_up_cat") as CustomPreferenceCategory }
        private val swipeUpHoldCategory by lazy { findPreference("swipe_up_hold_cat") as CustomPreferenceCategory }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            refreshListPrefs()

            removeNougatActionsIfNeeded()
            removeRootActionsIfNeeded()
        }

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.gestures)

            val sectionedPill = findPreference(PrefManager.SECTIONED_PILL) as SwitchPreference
            if (sectionedPill.isChecked) {
                sectionedCategoryHolder.addPreference(sectionedCategory)
                swipeUpCategory.removeAll()
                swipeUpHoldCategory.removeAll()
            } else {
                sectionedCategoryHolder.removePreference(sectionedCategory)
                swipeUpCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up)))
                swipeUpHoldCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up_hold)))
            }

            refreshListPrefs()
            updateSummaries()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            val map = HashMap<String, Int>()
            prefManager.getActionsList(map)

            if (map.keys.contains(key)) updateSummaries()

            if (key == PrefManager.SECTIONED_PILL) {
                if (sharedPreferences.getBoolean(key, false).toString().toBoolean()) {
                    sectionedCategoryHolder.addPreference(sectionedCategory)
                    swipeUpCategory.removeAll()
                    swipeUpHoldCategory.removeAll()

                    refreshListPrefs()

                    updateSummaries()

                    AlertDialog.Builder(activity)
                            .setTitle(R.string.use_recommended_settings)
                            .setView(R.layout.use_recommended_settings_dialog_message_view)
                            .setPositiveButton(android.R.string.yes) { _, _ -> setSectionedSettings() }
                            .setNegativeButton(R.string.no, null)
                            .show()
                } else {
                    AlertDialog.Builder(activity)
                            .setTitle(R.string.back_to_default)
                            .setMessage(R.string.back_to_default_desc)
                            .setPositiveButton(android.R.string.yes) { _, _ -> resetSectionedSettings() }
                            .setNegativeButton(R.string.no, null)
                            .show()

                    sectionedCategoryHolder.removePreference(sectionedCategory)
                    swipeUpCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up)))
                    swipeUpHoldCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up_hold)))

                    refreshListPrefs()
                }
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == REQ_APP) {
                val key = data?.getStringExtra(BaseAppSelectActivity.EXTRA_KEY)
                val appName = data?.getStringExtra(AppLaunchSelectActivity.EXTRA_RESULT_DISPLAY_NAME)
                val forActivity = data?.getBooleanExtra(AppLaunchSelectActivity.FOR_ACTIVITY_SELECT, false) == true

                when (resultCode) {
                    Activity.RESULT_OK -> {
                        updateAppLaunchSummary(key ?: return, appName ?: return)
                    }

                    Activity.RESULT_CANCELED -> {
                        listPrefs.forEach {
                            if (it.key == key) {
                                val pack = if (forActivity) prefManager.getActivity(it.key) else prefManager.getPackage(it.key)
                                if (pack == null) {
                                    it.saveValue(actionHolder.typeNoAction.toString())
                                } else {
                                    it.saveValueWithoutListener((if (forActivity) actionHolder.premTypeLaunchActivity else actionHolder.premTypeLaunchApp).toString())
                                }
                            }
                        }
                    }
                }
            } else if (requestCode == REQ_INTENT) {
                val key = data?.getStringExtra(BaseAppSelectActivity.EXTRA_KEY)

                when (resultCode) {
                    Activity.RESULT_OK -> {
                        val res = prefManager.getIntentKey(key ?: return)
                        updateIntentSummary(key, res)
                    }

                    Activity.RESULT_CANCELED -> {
                        listPrefs.forEach {
                            if (key != null && key == it.key) {
                                val res = prefManager.getIntentKey(key)

                                if (res < 1) {
                                    it.saveValue(actionHolder.typeNoAction.toString())
                                } else {
                                    it.saveValueWithoutListener(actionHolder.premTypeIntent.toString())
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun refreshListPrefs() {
            listPrefs.clear()
            listPrefs.addAll(getAllListPrefs())

            listPrefs.forEach {
                it.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == actionHolder.premTypeLaunchApp.toString() || newValue == actionHolder.premTypeLaunchActivity.toString()) {
                        val forActivity = newValue == actionHolder.premTypeLaunchActivity.toString()
                        val intent = Intent(activity, AppLaunchSelectActivity::class.java)

                        var pack = if (forActivity) prefManager.getActivity(it.key) else prefManager.getPackage(it.key)
                        var activity: String? = null
                        if (pack != null) {
                            activity = pack.split("/")[1]
                            pack = pack.split("/")[0]
                        }

                        intent.putExtra(BaseAppSelectActivity.EXTRA_KEY, it.key)
                        intent.putExtra(AppLaunchSelectActivity.CHECKED_PACKAGE, pack)
                        intent.putExtra(AppLaunchSelectActivity.CHECKED_ACTIVITY, activity)
                        intent.putExtra(AppLaunchSelectActivity.FOR_ACTIVITY_SELECT, forActivity)

                        startActivityForResult(intent, REQ_APP)
                    } else if (newValue == actionHolder.premTypeIntent.toString()) {
                        val intent = Intent(activity, IntentSelectorActivity::class.java)

                        intent.putExtra(BaseAppSelectActivity.EXTRA_KEY, it.key)

                        startActivityForResult(intent, REQ_INTENT)
                    }

                    true
                }
            }
        }

        private fun setSectionedSettings() {
            preferenceManager.sharedPreferences.edit().apply {
                putBoolean("use_pixels_width", false)
                putBoolean("use_pixels_height", true)
                putBoolean("use_pixels_y", false)
                putBoolean("larger_hitbox", false)
                putBoolean("hide_in_fullscreen", false)
                putBoolean("static_pill", true)
                putBoolean("hide_pill_on_keyboard", false)
                putBoolean("auto_hide_pill", false)

                putInt("custom_width_percent", 1000)
                putInt("custom_height", Utils.minPillHeightPx(activity) + Utils.dpAsPx(activity, 7f))
                putInt("custom_y_percent", 0)
                putInt("pill_corner_radius", 0)
                putInt("pill_bg", Color.TRANSPARENT)
                putInt("pill_fg", Color.TRANSPARENT)
                putInt("anim_duration", 0)
                putInt("hold_time", 500)

                putString(actionHolder.actionTap, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionDouble, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionHold, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionDown, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionLeft, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionLeftHold, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionRight, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionRightHold, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionUp, actionHolder.typeNoAction.toString())
                putString(actionHolder.actionUpHold, actionHolder.typeNoAction.toString())
            }.apply()

            updateSummaries()
        }
        
        private fun resetSectionedSettings() {
            preferenceManager.sharedPreferences.edit().apply {
                remove(PrefManager.USE_PIXELS_WIDTH)
                remove(PrefManager.USE_PIXELS_HEIGHT)
                remove(PrefManager.USE_PIXELS_Y)
                remove(PrefManager.LARGER_HITBOX)
                remove(PrefManager.HIDE_IN_FULLSCREEN)
                remove(PrefManager.STATIC_PILL)
                remove(PrefManager.HIDE_PILL_ON_KEYBOARD)
                remove(PrefManager.AUTO_HIDE_PILL)

                remove(PrefManager.CUSTOM_WIDTH_PERCENT)
                remove(PrefManager.CUSTOM_HEIGHT)
                remove(PrefManager.CUSTOM_Y_PERCENT)
                remove(PrefManager.PILL_CORNER_RADIUS)
                remove(PrefManager.PILL_BG)
                remove(PrefManager.PILL_FG)
                remove(PrefManager.ANIM_DURATION)
                remove(PrefManager.HOLD_TIME)

                remove(actionHolder.actionTap)
                remove(actionHolder.actionDouble)
                remove(actionHolder.actionHold)
                remove(actionHolder.actionDown)
                remove(actionHolder.actionLeft)
                remove(actionHolder.actionLeftHold)
                remove(actionHolder.actionRight)
                remove(actionHolder.actionRightHold)
                remove(actionHolder.actionUp)
                remove(actionHolder.actionUpHold)
            }.apply()

            updateSummaries()
        }

        private fun updateAppLaunchSummary(key: String, appName: String) {
            listPrefs.forEach {
                if (key == it.key) {
                    it.summary = String.format(Locale.getDefault(), it.summary.toString(), appName)
                    return
                }
            }
        }

        private fun updateIntentSummary(key: String, res: Int) {
            listPrefs.forEach {
                if (key == it.key) {
                    it.summary = String.format(Locale.getDefault(), it.summary.toString(), resources.getString(res))
                    return
                }
            }
        }

        private fun updateSummaries() {
            listPrefs.forEach {
                it.updateSummary(it.getSavedValue())

                if (it.getSavedValue() == actionHolder.premTypeLaunchApp.toString() || it.getSavedValue() == actionHolder.premTypeLaunchActivity.toString()) {
                    val forActivity = it.getSavedValue() == actionHolder.premTypeLaunchActivity.toString()
                    val packageInfo = (if (forActivity) prefManager.getActivity(it.key) else prefManager.getPackage(it.key)) ?: return@forEach

                    it.summary = String.format(Locale.getDefault(),
                            resources.getString(if (forActivity) R.string.prem_launch_activity else R.string.prem_launch_app),
                            prefManager.getDisplayName(it.key) ?: packageInfo.split("/")[0])
                } else if (it.getSavedValue() == actionHolder.premTypeIntent.toString()) {
                    val res = prefManager.getIntentKey(it.key)
                    it.summary = String.format(Locale.getDefault(),
                            resources.getString(R.string.prem_intent),
                            if (res > 0) resources.getString(res) else "")
                }
            }
        }

        private fun removeNougatActionsIfNeeded() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                listPrefs.forEach {
                    val actions = resources.getStringArray(R.array.nougat_action_values)
                    it.removeItemsByValue(actions)
                }
            }
        }

        private fun removeRootActionsIfNeeded() {
            if (!prefManager.useRoot) {
                listPrefs.forEach {
                    val actions = resources.getStringArray(R.array.root_action_values)
                    it.removeItemsByValue(actions)
                }
            }
        }

        private fun getAllListPrefs(): ArrayList<SectionableListPreference> {
            val ret = ArrayList<SectionableListPreference>()

            for (i in 0 until preferenceScreen.preferenceCount) {
                val pref = preferenceScreen.getPreference(i)

                if (pref is PreferenceGroup) {
                    for (j in 0 until pref.preferenceCount) {
                        val child = pref.getPreference(j)

                        if (child is SectionableListPreference) ret.add(child)
                        else if (child is PreferenceGroup) {
                            for (k in 0 until child.preferenceCount) {
                                val c = child.getPreference(k)

                                if (c is SectionableListPreference) ret.add(c)
                            }
                        }
                    }
                }
            }

            return ret
        }
    }

    /**
     * Appearance settings
     */
    class AppearanceFragment : BasePrefFragment() {
        override val resId = R.xml.prefs_appearance

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.appearance)
            setListeners()
            setup()
        }

        private fun setListeners() {
            val pillColor = findPreference(PrefManager.PILL_BG) as ColorPreference
            val pillBorderColor = findPreference(PrefManager.PILL_FG) as ColorPreference

            pillColor.setDefaultValue(Utils.getDefaultPillBGColor(activity))
            pillBorderColor.setDefaultValue(Utils.getDefaultPillFGColor(activity))

            pillColor.saveValue(prefManager.pillBGColor)
            pillBorderColor.saveValue(prefManager.pillFGColor)
        }

        private fun setup() {
            val screen = preferenceManager.inflateFromResource(activity, R.xml.prefs_appearance_dimens, null)

            val pixelsW = findPreference("use_pixels_width") as SwitchPreference
            val pixelsH = findPreference("use_pixels_height") as SwitchPreference
            val pixelsX = findPreference("use_pixels_x") as SwitchPreference
            val pixelsY = findPreference("use_pixels_y") as SwitchPreference

            val catW = findPreference("cat_width") as CustomPreferenceCategory
            val catH = findPreference("cat_height") as CustomPreferenceCategory
            val catX = findPreference("cat_x") as CustomPreferenceCategory
            val catY = findPreference("cat_y") as CustomPreferenceCategory

            val widthPercent = screen.findPreference("custom_width_percent") as SeekBarPreference
            val heightPercent = screen.findPreference("custom_height_percent") as SeekBarPreference
            val xPercent = screen.findPreference("custom_x_percent") as SeekBarPreference
            val yPercent = screen.findPreference("custom_y_percent") as SeekBarPreference

            val widthPixels = screen.findPreference("custom_width") as SeekBarPreference
            val heightPixels = screen.findPreference("custom_height") as SeekBarPreference
            val xPixels = screen.findPreference("custom_x") as SeekBarPreference
            val yPixels = screen.findPreference("custom_y") as SeekBarPreference

            widthPixels.minValue = Utils.minPillWidthPx(activity)
            heightPixels.minValue = Utils.minPillHeightPx(activity)
            xPixels.minValue = Utils.minPillXPx(activity)
            yPixels.minValue = Utils.minPillYPx(activity)

            widthPixels.maxValue = Utils.maxPillWidthPx(activity)
            heightPixels.maxValue = Utils.maxPillHeightPx(activity)
            yPixels.maxValue = Utils.maxPillYPx(activity)
            xPixels.maxValue = Utils.maxPillXPx(activity)

            yPercent.setDefaultValue(prefManager.defaultYPercent)
            yPixels.setDefaultValue(prefManager.defaultY)
            widthPixels.setDefaultValue(Utils.defPillWidthPx(activity))
            heightPixels.setDefaultValue(Utils.defPillHeightPx(activity))

            val listener = Preference.OnPreferenceChangeListener { pref, newValue ->
                val new = newValue.toString().toBoolean()

                when (pref) {
                    pixelsW -> {
                        catW.removeAll()
                        catW.addPreference(if (new) widthPixels else widthPercent)
                    }

                    pixelsH -> {
                        catH.removeAll()
                        catH.addPreference(if (new) heightPixels else heightPercent)
                    }

                    pixelsX -> {
                        catX.removeAll()
                        catX.addPreference(if (new) xPixels else xPercent)
                    }

                    pixelsY -> {
                        catY.removeAll()
                        catY.addPreference(if (new) yPixels else yPercent)
                    }
                }

                true
            }

            listener.onPreferenceChange(pixelsW, pixelsW.isChecked)
            listener.onPreferenceChange(pixelsH, pixelsH.isChecked)
            listener.onPreferenceChange(pixelsX, pixelsX.isChecked)
            listener.onPreferenceChange(pixelsY, pixelsY.isChecked)

            pixelsW.onPreferenceChangeListener = listener
            pixelsH.onPreferenceChangeListener = listener
            pixelsX.onPreferenceChangeListener = listener
            pixelsY.onPreferenceChangeListener = listener
        }
    }

    /**
     * Behavior settings
     */
    class BehaviorFragment : BasePrefFragment() {
        override val resId = R.xml.prefs_behavior

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.behavior)

            setBlacklistListeners()
        }

        private fun setBlacklistListeners() {
            val barBL = findPreference("bar_blacklist")
            val navBL = findPreference("nav_blacklist")

            val listener = Preference.OnPreferenceClickListener {
                val which = when (it.key) {
                    barBL.key -> BlacklistSelectorActivity.FOR_BAR
                    navBL.key -> BlacklistSelectorActivity.FOR_NAV
                    else -> return@OnPreferenceClickListener false
                }

                val blIntent = Intent(activity, BlacklistSelectorActivity::class.java)
                blIntent.putExtra(BlacklistSelectorActivity.EXTRA_WHICH, which)
                startActivity(blIntent)

                true
            }

            barBL.onPreferenceClickListener = listener
            navBL.onPreferenceClickListener = listener
        }
    }

    /**
     * Compatibility settings
     */
    class CompatibilityFragment : BasePrefFragment()  {
        override val resId = R.xml.prefs_compatibility

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            when (key) {
                PrefManager.ROT270_FIX -> {
                    val enabled = sharedPreferences.getBoolean(key, false)
                    val tabletMode = findPreference(PrefManager.TABLET_MODE) as SwitchPreference

                    tabletMode.isEnabled = !enabled
                    tabletMode.isChecked = if (enabled) false else tabletMode.isChecked
                }
                PrefManager.TABLET_MODE -> {
                    val enabled = sharedPreferences.getBoolean(key, false)
                    val rot270Fix = findPreference(PrefManager.ROT270_FIX) as SwitchPreference

                    rot270Fix.isEnabled = !enabled
                    rot270Fix.isChecked = if (enabled) false else rot270Fix.isChecked
                }
                PrefManager.ORIG_NAV_IN_IMMERSIVE -> {
                    val enabled = sharedPreferences.getBoolean(key, false)
                    val immNav = findPreference(PrefManager.USE_IMMERSIVE_MODE_WHEN_NAV_HIDDEN) as SwitchPreference

                    immNav.isEnabled = !enabled
                    immNav.isChecked = if (enabled) false else immNav.isChecked
                }
                PrefManager.USE_IMMERSIVE_MODE_WHEN_NAV_HIDDEN -> {
                    val enabled = sharedPreferences.getBoolean(key, false)
                    val origNav = findPreference(PrefManager.ORIG_NAV_IN_IMMERSIVE) as SwitchPreference

                    origNav.isEnabled = !enabled
                    origNav.isChecked = if (enabled) false else origNav.isChecked
                }
            }
        }

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.compatibility)

            setUpRotListeners()
            setUpImmersiveListeners()
        }

        override fun onDestroy() {
            super.onDestroy()

            preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        }

        private fun setUpRotListeners() {
            val rot270Fix = findPreference("rot270_fix") as SwitchPreference
            val tabletMode = findPreference("tablet_mode") as SwitchPreference

            if (rot270Fix.isChecked) {
                tabletMode.isChecked = false
                tabletMode.isEnabled = false
            }

            if (tabletMode.isChecked) {
                rot270Fix.isChecked = false
                rot270Fix.isEnabled = false
            }

        }

        private fun setUpImmersiveListeners() {
            val origNav = findPreference("orig_nav_in_immersive") as SwitchPreference
            val immNav = findPreference("use_immersive_mode_when_nav_hidden") as SwitchPreference
            val immBL = findPreference("immersive_blacklist")

            if (origNav.isChecked) {
                immNav.isChecked = false
                immNav.isEnabled = false
            }

            if (immNav.isChecked) {
                origNav.isChecked = false
                origNav.isEnabled = false
            }

            immBL.setOnPreferenceClickListener {
                val selector = Intent(activity, BlacklistSelectorActivity::class.java)
                selector.putExtra(BlacklistSelectorActivity.EXTRA_WHICH, BlacklistSelectorActivity.FOR_IMM)
                startActivity(selector)
                true
            }
        }
    }

    /**
     * Experimental, but mostly working settings
     */
    class ExperimentalFragment : BasePrefFragment() {
        override val resId = R.xml.prefs_experimental

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.experimental_prefs)

            setListeners()
        }

        private fun setListeners() {
            val hideOnKb = findPreference("hide_pill_on_keyboard") as SeekBarSwitchPreference

            val winFix = findPreference("window_fix")
            winFix.setOnPreferenceClickListener {
                val blIntent = Intent(activity, BlacklistSelectorActivity::class.java)
                blIntent.putExtra(BlacklistSelectorActivity.EXTRA_WHICH, BlacklistSelectorActivity.FOR_WIN)
                startActivity(blIntent)

                true
            }
        }
    }

    abstract class BasePrefFragment : PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {
        internal val prefManager by lazy { PrefManager.getInstance(activity) }

        abstract val resId: Int

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            addPreferencesFromResource(resId)
            preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {}

        override fun onDestroy() {
            super.onDestroy()

            preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        }
    }
}
