package com.lytalk.dodo.schedule

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred

object WakeGate {
    @Volatile
    var ready: CompletableDeferred<Unit>? = null
}

class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
        )
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            dimAmount = 0f
        }
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        instance = this
        WakeGate.ready?.let { gate ->
            if (!gate.isCompleted) gate.complete(Unit)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: WakeActivity? = null

        fun finishIfUp() {
            instance?.let { activity ->
                activity.runOnUiThread { activity.finish() }
            }
        }
    }
}
