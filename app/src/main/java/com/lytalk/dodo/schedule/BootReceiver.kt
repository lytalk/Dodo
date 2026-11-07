package com.lytalk.dodo.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lytalk.dodo.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                if (AppDatabase.get(appContext).taskDao().countEnabled() > 0) {
                    KeepAliveService.start(appContext)
                }
            } finally {
                pending.finish()
            }
        }.invokeOnCompletion { scope.cancel() }
    }
}
