// Copyright 2026 Amanda Hernow. Licensed under the Apache License, Version 2.0.
package net.hernow.barkey

import android.content.Context
import android.widget.FrameLayout
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Informational IME panel; it does not read from or write to the active editor. */
internal class AboutHelpPanel(
    context: Context,
    versionName: String,
    private val onClose: () -> Unit,
) {
    val view: View
    private val root: LinearLayout
    private val content: LinearLayout
    private val closeButton: Button
    private val textViews = mutableListOf<TextView>()
    private val sectionViews = mutableListOf<View>()

    init {
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 4))
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = text(context, R.string.app_name, 20f, bold = true).apply { tag = TITLE_TAG }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        closeButton = Button(context).apply {
            setText(R.string.help_close)
            minWidth = dp(context, 72)
            minHeight = dp(context, 48)
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = context.getString(R.string.help_close_description)
            setOnClickListener { onClose() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isFocusable = false
            isFocusableInTouchMode = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 2), 0, dp(context, 6))
            isFocusable = false
            isFocusableInTouchMode = false
        }
        content.addView(
            text(context, R.string.help_subtitle, 14f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        content.addView(
            text(context, R.string.help_about_body, 14f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 8)
            },
        )
        addSection(context, R.string.help_privacy_heading) { section ->
            addPrivacyItem(context, section, R.string.help_privacy_free_title, R.string.help_privacy_free_body)
            addPrivacyItem(context, section, R.string.help_privacy_ads_title, R.string.help_privacy_ads_body)
            addPrivacyItem(context, section, R.string.help_privacy_tracking_title, R.string.help_privacy_tracking_body)
            addPrivacyItem(context, section, R.string.help_privacy_accounts_title, R.string.help_privacy_accounts_body)
            addPrivacyItem(context, section, R.string.help_privacy_offline_title, R.string.help_privacy_offline_body)
            addPrivacyItem(context, section, R.string.help_privacy_data_title, R.string.help_privacy_data_body)
            addBody(context, section, R.string.help_privacy_memory_note)
        }
        addSection(context, R.string.help_how_to_heading) { section ->
            listOf(
                R.string.help_step_1,
                R.string.help_step_2,
                R.string.help_step_3,
                R.string.help_step_4,
                R.string.help_step_5,
                R.string.help_step_6,
            ).forEach { addBody(context, section, it) }
            listOf(
                R.string.help_control_undo,
                R.string.help_control_cursor,
                R.string.help_control_backspace,
                R.string.help_control_space,
                R.string.help_control_tab,
                R.string.help_control_enter,
                R.string.help_control_switch,
            ).forEach { addBody(context, section, it) }
            addBody(context, section, R.string.help_navigation_note)
        }
        addSection(context, R.string.help_permissions_heading) { section ->
            addBody(context, section, R.string.help_permissions_body)
            addBody(context, section, R.string.help_keyboard_warning)
            addBody(context, section, R.string.help_permissions_scope)
        }
        addSection(context, R.string.help_open_source_heading) { section ->
            addBody(context, section, R.string.help_open_source_body)
            addBody(context, section, R.string.help_developer)
            addBody(context, section, R.string.help_original_project)
            addBody(context, section, R.string.help_repository)
            addBody(context, section, R.string.help_license)
            addBody(context, section, R.string.help_version, versionName)
        }
        scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        view = root
        applyTheme(context)
    }

    fun applyTheme(context: Context) {
        root.setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_background))
        val primary = ContextCompat.getColor(context, R.color.keyboard_text_primary)
        val secondary = ContextCompat.getColor(context, R.color.keyboard_text_secondary)
        textViews.forEach { view ->
            view.setTextColor(if (view.tag == TITLE_TAG) primary else secondary)
        }
        sectionViews.forEach { it.setBackgroundResource(R.drawable.help_section_background) }
        closeButton.apply {
            setBackgroundResource(R.drawable.keyboard_button_background)
            backgroundTintList = null
            setTextColor(ContextCompat.getColorStateList(context, R.color.keyboard_button_text))
            elevation = 0f
            stateListAnimator = null
        }
    }

    private fun addSection(context: Context, titleId: Int, addContent: (LinearLayout) -> Unit) {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        }
        val title = text(context, titleId, 16f, bold = true).apply { tag = TITLE_TAG }
        section.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 5)
        })
        addContent(section)
        content.addView(section, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 7)
        })
        sectionViews += section
    }

    private fun addPrivacyItem(context: Context, section: LinearLayout, titleId: Int, bodyId: Int) {
        val title = text(context, titleId, 14f, bold = true).apply { tag = TITLE_TAG }
        section.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addBody(context, section, bodyId)
    }

    private fun addBody(context: Context, section: LinearLayout, stringId: Int, value: String? = null) {
        val body = text(context, stringId, 14f, value)
        section.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 4)
        })
    }

    private fun text(context: Context, stringId: Int, sizeSp: Float, value: String? = null, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = if (value == null) context.getString(stringId) else context.getString(stringId, value)
            textSize = sizeSp
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
            isFocusable = false
            isClickable = false
            if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
            textViews += this
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TITLE_TAG = "about_help_title"
    }
}
