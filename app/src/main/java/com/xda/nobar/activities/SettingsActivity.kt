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
import com.xda.nobar.App
import com.xda.nobar.R
import com.xda.nobar.prefs.CustomPreferenceCategory
import com.xda.nobar.prefs.SectionableListPreference
import com.xda.nobar.util.Utils
import java.util.*

/**
 * The configuration activity
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        fragmentManager?.beginTransaction()?.replace(R.id.content, MainFragment())?.addToBackStack("main")?.commit()
    }

    override fun onResume() {
        super.onResume()

        (application as App).refreshPremium()
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
                    else -> null
                }

                if (whichFrag != null) fragmentManager?.beginTransaction()?.replace(R.id.content, whichFrag, it.key)?.addToBackStack(it.key)?.commit()
                true
            }

            findPreference("gestures").onPreferenceClickListener = listener
            findPreference("appearance").onPreferenceClickListener = listener
            findPreference("behavior").onPreferenceClickListener = listener
            findPreference("compatibility").onPreferenceClickListener = listener
        }
    }

    /**
     * Gesture preferences
     */
    class GestureFragment : PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val listPrefs = ArrayList<SectionableListPreference>()

        private lateinit var app: App
        private lateinit var sectionedScreen: PreferenceScreen
        private lateinit var sectionedCategory: PreferenceCategory
        private lateinit var sectionedCategoryHolder: CustomPreferenceCategory
        private lateinit var swipeUpCategory: CustomPreferenceCategory
        private lateinit var swipeUpHoldCategory: CustomPreferenceCategory

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            app = activity.application as App
            sectionedScreen = preferenceManager.inflateFromResource(activity, R.xml.prefs_sectioned, null)
            sectionedCategory = sectionedScreen.findPreference("section_gestures") as PreferenceCategory

            addPreferencesFromResource(R.xml.prefs_gestures)

            swipeUpCategory = findPreference("swipe_up_cat") as CustomPreferenceCategory
            swipeUpHoldCategory = findPreference("swipe_up_hold_cat") as CustomPreferenceCategory
            sectionedCategoryHolder = findPreference("sectioned_pill_cat") as CustomPreferenceCategory

            val sectionedPill = findPreference("sectioned_pill") as SwitchPreference
            sectionedPill.setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString().toBoolean()) {
                    sectionedCategoryHolder.addPreference(sectionedCategory)
                    swipeUpCategory.removeAll()
                    swipeUpHoldCategory.removeAll()
                    setListeners()

                    AlertDialog.Builder(activity)
                            .setTitle(R.string.use_recommended_settings)
                            .setView(R.layout.use_recommended_settings_dialog_message_view)
                            .setPositiveButton(android.R.string.yes, { _, _ -> setSectionedSettings() })
                            .setNegativeButton(R.string.no, null)
                            .show()
                }
                else {
                    sectionedCategoryHolder.removePreference(sectionedCategory)
                    swipeUpCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up)))
                    swipeUpHoldCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up_hold)))
                }
                true
            }

            listPrefs.addAll(getAllListPrefs())

            removeNougatActionsIfNeeded()
            removeRootActionsIfNeeded()
            setListeners()

            preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.gestures)

            val sectionedPill = findPreference("sectioned_pill") as SwitchPreference
            if (sectionedPill.isChecked) {
                sectionedCategoryHolder.addPreference(sectionedCategory)
                swipeUpCategory.removeAll()
                swipeUpHoldCategory.removeAll()
                setListeners()
            } else {
                sectionedCategoryHolder.removePreference(sectionedCategory)
                swipeUpCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up)))
                swipeUpHoldCategory.addPreference(sectionedScreen.findPreference(resources.getString(R.string.action_up_hold)))
            }

            updateSummaries()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            val map = HashMap<String, Int>()
            Utils.getActionsList(activity, map)

            if (map.keys.contains(key)) updateSummaries()
        }

        override fun onDestroy() {
            super.onDestroy()
            preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == 10) {
                val key = data?.getStringExtra(AppLaunchSelectActivity.EXTRA_KEY)
                val appName = data?.getStringExtra(AppLaunchSelectActivity.EXTRA_RESULT_DISPLAY_NAME)

                when (resultCode) {
                    Activity.RESULT_OK -> {
                        updateAppLaunchSummary(key ?: return, appName ?: return)
                    }

                    Activity.RESULT_CANCELED -> {
                        listPrefs.forEach {
                            if (it.key == key) {
                                val pack = preferenceManager.sharedPreferences.getString("${key}_package", null)
                                if (pack == null) {
                                    it.saveValue(app.typeNoAction.toString())
                                } else {
                                    it.saveValueWithoutListener(app.premTypeLaunchApp.toString())
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun setSectionedSettings() {
            preferenceManager.sharedPreferences.edit().apply {
                putBoolean("use_pixels_width", false)
                putBoolean("use_pixels_height", false)
                putBoolean("use_pixels_y", false)
                putBoolean("larger_hitbox", false)
                putBoolean("hide_in_fullscreen", false)
                putBoolean("static_pill", true)

                putInt("custom_width_percent", 1000)
                putInt("custom_height_percent", 150)
                putInt("custom_y_percent", 0)
                putInt("pill_corner_radius", 0)
                putInt("pill_bg", Color.TRANSPARENT)
                putInt("pill_fg", Color.TRANSPARENT)

                putInt(app.actionTap, app.typeNoAction)
                putInt(app.actionDouble, app.typeNoAction)
                putInt(app.actionHold, app.typeNoAction)
                putInt(app.actionDown, app.typeNoAction)
                putInt(app.actionLeft, app.typeNoAction)
                putInt(app.actionLeftHold, app.typeNoAction)
                putInt(app.actionRight, app.typeNoAction)
                putInt(app.actionRightHold, app.typeNoAction)
                putInt(app.actionUp, app.typeNoAction)
                putInt(app.actionUpHold, app.typeNoAction)
            }.apply()

            updateSummaries()
        }

        private fun updateAppLaunchSummary(key: String, appName: String) {
            listPrefs.forEach {
                if (key == it.key) {
                    it.summary = String.format(Locale.getDefault(), it.summary.toString(), appName)
                    return@forEach
                }
            }
        }

        private fun updateSummaries() {
            listPrefs.forEach {
                it.updateSummary(it.getSavedValue())

                if (it.getSavedValue() == app.premTypeLaunchApp.toString()) {
                    val packageInfo = preferenceManager.sharedPreferences.getString("${it.key}_package", null) ?: return

                    it.summary = String.format(Locale.getDefault(),
                            resources.getString(R.string.prem_launch_app),
                            try {
                                activity.packageManager.getApplicationLabel(
                                        activity.packageManager.getApplicationInfo(packageInfo.split("/")[0], 0))
                            } catch (e: Exception) {
                                packageInfo.split("/")[0]
                            })
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
            if (!Utils.shouldUseRootCommands(activity)) {
                listPrefs.forEach {
                    val actions = resources.getStringArray(R.array.root_action_values)
                    it.removeItemsByValue(actions)
                }
            }
        }

        private fun setListeners() {
            listPrefs.forEach {
                it.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue?.toString() == app.premTypeLaunchApp.toString()) {
                        val intent = Intent(activity, AppLaunchSelectActivity::class.java)

                        var pack = preferenceManager.sharedPreferences.getString("${it.key}_package", null)
                        if (pack != null) pack = pack.split("/")[0]

                        intent.putExtra(AppLaunchSelectActivity.EXTRA_KEY, it.key)
                        intent.putExtra(AppLaunchSelectActivity.CHECKED_PACKAGE, pack)

                        startActivityForResult(intent, 10)
                    }
                    true
                }
            }
        }

        private fun getAllListPrefs(): ArrayList<SectionableListPreference> {
            val ret = ArrayList<SectionableListPreference>()

            for (i in 0 until preferenceScreen.preferenceCount) {
                val pref = preferenceScreen.getPreference(i)

                if (pref is PreferenceCategory) {
                    for (j in 0 until pref.preferenceCount) {
                        val child = pref.getPreference(j)

                        if (child is SectionableListPreference) ret.add(child)
                    }
                }
            }

            return ret
        }
    }

    /**
     * Appearance settings
     */
    class AppearanceFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            addPreferencesFromResource(R.xml.prefs_appearance)
        }

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.appearance)
            setListeners()
            setup()
        }

        private fun setListeners() {
            val pillColor = findPreference("pill_bg") as ColorPreference
            val pillBorderColor = findPreference("pill_fg") as ColorPreference

            pillColor.setDefaultValue(Utils.getDefaultPillBGColor())
            pillBorderColor.setDefaultValue(Utils.getDefaultPillFGColor())

            pillColor.saveValue(Utils.getPillBGColor(activity))
            pillBorderColor.saveValue(Utils.getPillFGColor(activity))
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

            widthPixels.minValue = Utils.dpAsPx(activity, 10)
            heightPixels.minValue = Utils.dpAsPx(activity, 5)
            xPixels.minValue = -(Utils.getRealScreenSize(activity).x.toFloat() / 2f - Utils.getCustomWidth(activity).toFloat() / 2f).toInt()
            yPixels.minValue = 0

            widthPixels.maxValue = Utils.getRealScreenSize(activity).x
            heightPixels.maxValue = Utils.dpAsPx(activity, 50)
            yPixels.maxValue = Utils.getRealScreenSize(activity).y
            xPixels.maxValue = -xPixels.minValue

            yPercent.setDefaultValue(Utils.getDefaultYPercent(activity))
            yPixels.setDefaultValue(Utils.getDefaultY(activity))
            widthPixels.setDefaultValue(resources.getDimensionPixelSize(R.dimen.pill_width))
            heightPixels.setDefaultValue(resources.getDimensionPixelSize(R.dimen.pill_height))

//            category.addPreference(category.preferenceList.indexOf(pixelsW) + 1, if (pixelsW.isChecked) widthPixels else widthPercent)
//            category.addPreference(category.preferenceList.indexOf(pixelsH) + 1, if (pixelsH.isChecked) heightPixels else heightPercent)
//            category.addPreference(category.preferenceList.indexOf(pixelsX) + 1, if (pixelsX.isChecked) xPixels else xPercent)
//            category.addPreference(category.preferenceList.indexOf(pixelsY) + 1, if (pixelsY.isChecked) yPixels else yPercent)

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
    class BehaviorFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            addPreferencesFromResource(R.xml.prefs_behavior)
        }

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.behavior)
        }
    }

    /**
     * Compatibility settings
     */
    class CompatibilityFragment : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            addPreferencesFromResource(R.xml.prefs_compatibility)
        }

        override fun onResume() {
            super.onResume()

            activity.title = resources.getText(R.string.compatibility)

            setUpListeners()
        }
        
        private fun setUpListeners() {
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

            rot270Fix.setOnPreferenceChangeListener { _, newValue -> 
                val enabled = newValue.toString().toBoolean()
                
                tabletMode.isEnabled = !enabled
                tabletMode.isChecked = if (enabled) false else tabletMode.isChecked
                
                true
            }

            tabletMode.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue.toString().toBoolean()

                rot270Fix.isEnabled = !enabled
                rot270Fix.isChecked = if (enabled) false else rot270Fix.isChecked

                true
            }
        }
    }
}
