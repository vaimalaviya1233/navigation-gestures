package com.xda.nobar.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.SearchView
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import com.github.lzyzsd.circleprogress.ArcProgress
import com.xda.nobar.App
import com.xda.nobar.R
import com.xda.nobar.util.AppInfo
import com.xda.nobar.util.AppSelectAdapter
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers

abstract class BaseAppSelectActivity : AppCompatActivity(), SearchView.OnQueryTextListener {
    internal abstract val adapter: AppSelectAdapter

    private val origAppSet = ArrayList<AppInfo>()

    private lateinit var list: RecyclerView

    internal abstract fun loadAppList(): ArrayList<*>
    internal abstract fun loadAppInfo(info: Any): AppInfo

    internal open fun canRun(): Boolean {
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!canRun()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val app = application as App
        app.refreshPremium()

        setContentView(R.layout.activity_app_launch_select)

        val loader = findViewById<ArcProgress>(R.id.progress)
        list = findViewById(R.id.list)

        list.layoutManager = LinearLayoutManager(this, LinearLayout.VERTICAL, false)
        list.addItemDecoration(DividerItemDecoration(list.context, (list.layoutManager as LinearLayoutManager).orientation))

        Observable.fromCallable { loadAppList() }
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe {
                    it.forEach { info ->
                        val appInfo = loadAppInfo(info)

                        adapter.add(appInfo)
                        origAppSet.add(appInfo)

                        val index = it.indexOf(info)
                        val percent = (index.toFloat() / it.size.toFloat() * 100).toInt()

                        runOnUiThread {
                            loader.progress = percent
                        }
                    }

                    runOnUiThread {
                        list.adapter = adapter
                        loader.visibility = View.GONE
                        list.visibility = View.VISIBLE
                    }
                }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(this)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        val resultIntent = Intent()
        resultIntent.putExtra(AppLaunchSelectActivity.EXTRA_KEY, intent.getStringExtra(AppLaunchSelectActivity.EXTRA_KEY))
        setResult(Activity.RESULT_CANCELED, resultIntent)
        finish()
    }

    override fun onQueryTextChange(newText: String): Boolean {
        val list = filter(newText)
        adapter.replaceAll(list)
        this.list.scrollToPosition(0)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    private fun filter(query: String): ArrayList<AppInfo> {
        val lowercase = query.toLowerCase()

        val filteredList = ArrayList<AppInfo>()

        origAppSet.forEach {
            val text = it.displayName.toLowerCase()
            if (text.contains(lowercase)) {
                filteredList.add(it)
            }
        }

        return filteredList
    }
}