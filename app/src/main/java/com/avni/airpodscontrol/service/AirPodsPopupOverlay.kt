package com.avni.airpodscontrol.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.avni.airpodscontrol.R
import com.avni.airpodscontrol.model.AirPodsState

object AirPodsPopupOverlay {
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    fun show(context: Context, state: AirPodsState) {
        if (!Settings.canDrawOverlays(context)) return
        val wm = context.getSystemService(WindowManager::class.java)
        hide(context)

        val density = context.resources.displayMetrics.density
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20*density).toInt(), (16*density).toInt(), (20*density).toInt(), (16*density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF7FFFFFF.toInt())
                cornerRadius = 28*density
                setStroke((1*density).toInt(), 0x16000000)
            }
            elevation = 12*density
        }
        box.addView(TextView(context).apply {
            text = state.pairedAirPodsName ?: context.getString(R.string.airpods_default)
            textSize = 19f
            setTextColor(0xFF111111.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(context).apply {
            val bits = listOfNotNull(
                state.leftBattery?.let { "${context.getString(R.string.left)} $it%" },
                state.rightBattery?.let { "${context.getString(R.string.right)} $it%" },
                state.caseBattery?.let { "${context.getString(R.string.case_label)} $it%" }
            )
            text = if (bits.isEmpty()) context.getString(R.string.popup_nearby) else bits.joinToString("   ")
            textSize = 14f
            setTextColor(0xFF555555.toInt())
            setPadding(0, (8*density).toInt(), 0, 0)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (18*density).toInt()
            horizontalMargin = 0.035f
        }
        runCatching { wm.addView(box, params); view = box }
        hideRunnable = Runnable { hide(context) }.also { handler.postDelayed(it, 4500) }
    }

    fun hide(context: Context) {
        hideRunnable?.let(handler::removeCallbacks)
        hideRunnable = null
        val current = view ?: return
        runCatching { context.getSystemService(WindowManager::class.java).removeView(current) }
        view = null
    }
}
