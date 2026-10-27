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
    // Warm-white + coral-red system (no navy). Coral #D2402E gives >= 4.5:1 with white text.
    val INK = Color.parseColor("#26232B")          // charcoal text
    val MUTED = Color.parseColor("#6A6570")
    val PAGE = Color.parseColor("#F5F3F0")         // warm off-white page
    val CARD = Color.WHITE
    val BORDER = Color.parseColor("#EFE6E1")
    val CORAL = Color.parseColor("#D2402E")        // primary actions
    val CORAL_SOFT = Color.parseColor("#FDECE8")   // tinted surfaces
    val RED = CORAL
    val DARK_RED = Color.parseColor("#A8321F")     // urgent full-screen reminder
    val GREEN = Color.parseColor("#1F7A46")        // success only
    val GREEN_SOFT = Color.parseColor("#E5F4EB")
    val AMBER = Color.parseColor("#9A5B00")
    val AMBER_SOFT = Color.parseColor("#FFF1D6")
    val BLUE = CORAL                                // legacy alias: secondary accent is coral too

    fun dp(c: Context, n: Int): Int = (n * c.resources.displayMetrics.density + 0.5f).toInt()

    fun lp(c: Context, top: Int = 0, bottom: Int = 0, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(c, top); bottomMargin = dp(c, bottom) }

    fun label(c: Context, text: String, sp: Float = 16f, color: Int = INK, bold: Boolean = false, center: Boolean = false): TextView =
        TextView(c).apply {
            this.text = text; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (center) gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.1f)
        }

    fun button(c: Context, text: String, bg: Int = BLUE, fg: Int = Color.WHITE, stroke: Int? = null, heightDp: Int = 56, sp: Float = 17f, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text; contentDescription = text; isAllCaps = false; textSize = sp
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); setTextColor(fg)
            minHeight = dp(c, heightDp); minimumHeight = dp(c, heightDp)
            stateListAnimator = null
            val shape = GradientDrawable().apply {
                setColor(bg); cornerRadius = dp(c, 28).toFloat()      // pill buttons
                if (stroke != null) setStroke(dp(c, 2), stroke)
            }
            if (bg != Color.TRANSPARENT) elevation = dp(c, 3).toFloat()
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 0, 0, 0)), shape, null)
            setOnClickListener { onClick() }
        }

    fun card(c: Context, bg: Int = CARD): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16))
        background = GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(c, 24).toFloat(); setStroke(dp(c, 1), BORDER)
        }
        elevation = dp(c, 4).toFloat()          // soft lift on the warm page
    }

    fun chip(c: Context, text: String, bg: Int, fg: Int = Color.WHITE): TextView = label(c, text, 14f, fg, bold = true).apply {
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(c, 20).toFloat() }
        contentDescription = "Status: $text"
    }

    /** A tinted round-rect surface (no elevation), e.g. for step lists and status banners. */
    fun tint(c: Context, bg: Int): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(c, 18).toFloat() }
    }

    fun icon(c: Context, res: Int, tint: Int, sizeDp: Int = 24): android.widget.ImageView = android.widget.ImageView(c).apply {
        setImageResource(res); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun circle(c: Context, bg: Int, sizeDp: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg); setSize(dp(c, sizeDp), dp(c, sizeDp)) }

    fun row(c: Context, vararg views: View): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    fun weight(v: View, w: Float = 1f): View = v.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w) }
}
