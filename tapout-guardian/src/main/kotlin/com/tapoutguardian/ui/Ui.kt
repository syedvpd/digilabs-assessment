package com.tapoutguardian.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny programmatic widget kit (the environment cannot download Material/Compose, see README). */
object Ui {
    val INK = Color.parseColor("#111827")
    val MUTED = Color.parseColor("#4B5563")
    val PAGE = Color.parseColor("#F3F4F6")
    val CARD = Color.WHITE
    val RED = Color.parseColor("#B3261E")
    val DARK_RED = Color.parseColor("#7F1D1D")
    val GREEN = Color.parseColor("#1B5E20")
    val AMBER = Color.parseColor("#8A5A00")
    val BLUE = Color.parseColor("#1D4ED8")

    fun dp(c: Context, n: Int): Int = (n * c.resources.displayMetrics.density + 0.5f).toInt()

    fun lp(c: Context, top: Int = 0, bottom: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(c, top); bottomMargin = dp(c, bottom) }

    fun label(c: Context, text: String, sp: Float = 16f, color: Int = INK, bold: Boolean = false, center: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text; textSize = sp; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (center) gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.1f)
        }

    fun button(c: Context, text: String, bg: Int = BLUE, fg: Int = Color.WHITE, stroke: Int? = null, heightDp: Int = 56, sp: Float = 17f, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text; contentDescription = text; isAllCaps = false; textSize = sp
            setTypeface(typeface, Typeface.BOLD); setTextColor(fg)
            minHeight = dp(c, heightDp); minimumHeight = dp(c, heightDp)
            stateListAnimator = null
            val shape = GradientDrawable().apply {
                setColor(bg); cornerRadius = dp(c, 16).toFloat()
                if (stroke != null) setStroke(dp(c, 2), stroke)
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 0, 0, 0)), shape, null)
            setOnClickListener { onClick() }
        }

    fun card(c: Context, bg: Int = CARD): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(c, 16).toFloat() }
    }

    fun chip(c: Context, text: String, bg: Int, fg: Int = Color.WHITE): TextView = label(c, text, 14f, fg, bold = true).apply {
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(c, 20).toFloat() }
        contentDescription = "Status: $text"
    }

    fun row(c: Context, vararg views: View): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    fun weight(v: View, w: Float = 1f): View = v.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w) }
}
