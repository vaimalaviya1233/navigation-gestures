package com.xda.nobar.activities

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater
import android.widget.CompoundButton
import com.xda.nobar.App
import com.xda.nobar.R
import com.xda.nobar.interfaces.OnGestureStateChangeListener
import com.xda.nobar.interfaces.OnLicenseCheckResultListener
import com.xda.nobar.interfaces.OnNavBarHideStateChangeListener
import com.xda.nobar.prefs.PrefManager
import com.xda.nobar.util.Utils
import com.xda.nobar.views.BarView
import kotlinx.android.synthetic.main.activity_main.*

/**
 * The main app activity
 */
class MainActivity : AppCompatActivity(), OnGestureStateChangeListener, OnNavBarHideStateChangeListener, OnLicenseCheckResultListener {
    companion object {
        fun start(context: Context) {
            context.startActivity(makeIntent(context))
        }

        fun makeIntent(context: Context): Intent {
            val launch = Intent(context, MainActivity::class.java)
            launch.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            return launch
        }
    }

    private val app by lazy { application as App }
    private val prefManager by lazy { PrefManager.getInstance(this) }

    private val navListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        app.toggleNavState(!isChecked)
        if (!IntroActivity.hasWss(this)) onNavStateChange(false)
    }

    private var currentPremReason: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (IntroActivity.needsToRun(this)) {
            IntroActivity.start(this)
        }

        if (!Utils.canRunHiddenCommands(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        setUpActionBar()

        app.addLicenseCheckListener(this)
        app.addGestureActivationListener(this)
        app.addNavBarHideListener(this)

        activate.isChecked = app.areGesturesActivated()
        activate.onCheckedChangeListener = CompoundButton.OnCheckedChangeListener { button, isChecked ->
            if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))
                    && Utils.isAccessibilityEnabled(this)) {
                if (isChecked) app.addBar() else app.removeBar()
                app.setGestureState(isChecked)
            } else {
                button.isChecked = false
                IntroActivity.start(this)
            }
        }

        hide_nav.isChecked = prefManager.shouldUseOverscanMethod
        hide_nav.onCheckedChangeListener = navListener

        refresh_prem.setOnClickListener {
            refresh()
        }

        prem_stat_clicker.setOnClickListener {
            AlertDialog.Builder(this)
                    .setMessage(currentPremReason)
                    .show()
        }

        refresh()
    }

    override fun onGestureStateChange(barView: BarView?, activated: Boolean) {
        activate.isChecked = activated
    }

    override fun onNavStateChange(hidden: Boolean) {
        activate.onCheckedChangeListener = null
        activate.isChecked = hidden
        activate.onCheckedChangeListener = navListener
    }

    override fun onResult(valid: Boolean, reason: String?) {
        currentPremReason = reason
        runOnUiThread {
            prem_stat.setTextColor(if (valid) Color.GREEN else Color.RED)
            prem_stat.text = resources.getText(if (valid) R.string.installed else R.string.not_found)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        app.removeLicenseCheckListener(this)
        app.removeGestureActivationListener(this)
        app.removeNavbarHideListener(this)

        try {
            app.removeGestureActivationListener(this)
        } catch (e: Exception) {}
    }

    private fun refresh() {
        prem_stat.setTextColor(Color.YELLOW)
        prem_stat.text = resources.getText(R.string.checking)

        app.refreshPremium()
    }

    /**
     * Add buttons to the action bar
     */
    private fun setUpActionBar() {
        setSupportActionBar(toolbar)

        val gear = LayoutInflater.from(this).inflate(R.layout.settings_button, toolbar, false)
        gear.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        val about = LayoutInflater.from(this).inflate(R.layout.help_button, toolbar, false)
        about.setOnClickListener { startActivity(Intent(this, HelpAboutActivity::class.java)) }

        toolbar.addView(gear)
        toolbar.addView(about)
    }
}
