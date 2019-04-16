package com.xda.nobar.root

import android.content.Context
import com.xda.nobar.RootActions
import com.xda.nobar.util.isSuAsync
import eu.chainfire.librootjava.BuildConfig
import eu.chainfire.librootjava.RootIPCReceiver
import eu.chainfire.librootjava.RootJava
import eu.chainfire.libsuperuser.Shell
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class RootWrapper(private val context: Context) {
    private val receiver = object : RootIPCReceiver<RootActions>(context, 200, RootActions::class.java) {
        override fun onConnect(ipc: RootActions?) {
            actions = ipc
        }

        override fun onDisconnect(ipc: RootActions?) {
            actions = null
        }
    }
    private var isCreated = false

    var actions: RootActions? = null

    fun onCreate() {
        if (!isCreated) {
            isCreated = true

            val script =
                    RootJava.getLaunchScript(context, RootHandler::class.java, null,
                            null, null, BuildConfig.APPLICATION_ID + ":root")

            isSuAsync {
                GlobalScope.launch {
                    Shell.SU.run(script.toTypedArray())
                }
            }
        }
    }

    fun onDestroy() {
        receiver.release()
        GlobalScope.launch {
            RootJava.cleanupCache(context)
        }
        isCreated = false
    }
}