// Copyright 2026 Amanda Hernow. Licensed under the Apache License, Version 2.0.
package net.hernow.barkey

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
        addSection(context, R.string.help_about_heading) { section ->
            addBody(context, section, R.string.help_about_details)
            val developer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(context, 6), 0, dp(context, 6))
            }
            developer.addView(ImageView(context).apply {
                setImageResource(R.drawable.developer_photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = context.getString(R.string.help_developer)
            }, LinearLayout.LayoutParams(dp(context, 72), dp(context, 72)).apply { marginEnd = dp(context, 12) })
            developer.addView(
                text(context, R.string.help_developer, 15f, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            section.addView(developer)
            addBody(context, section, R.string.help_developer_why)
            addExternalLink(context, section, R.string.help_about_link, ABOUT_URL)
            addExternalLink(context, section, R.string.help_privacy_link, PRIVACY_URL)
        }
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
            addBody(context, section, R.string.help_resize_note)
            listOf(
                R.string.help_control_undo,
                R.string.help_control_cursor,
                R.string.help_control_backspace,
                R.string.help_control_space,
                R.string.help_control_tab,
                R.string.help_control_enter,
                R.string.help_control_switch,
            ).forEach { addBody(context, section, it) }
            listOf(
                R.string.help_scan_mode_fast,
                R.string.help_scan_mode_accurate,
                R.string.help_tap_to_focus,
                R.string.help_torch,
                R.string.help_preview,
                R.string.help_focus_region,
                R.string.help_single_scan,
                R.string.help_extended_combinations,
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
            addBody(context, section, R.string.help_zxing_cpp_credit)
            addExternalLink(context, section, R.string.help_zxing_cpp_link, ZXING_CPP_URL)
            addBody(context, section, R.string.help_zxing_java_credit)
            addExternalLink(context, section, R.string.help_zxing_java_link, ZXING_JAVA_URL)
            addBody(context, section, R.string.help_androidx_credit)
            addExternalLink(context, section, R.string.help_androidx_link, ANDROIDX_URL)
            addBody(context, section, R.string.help_guava_credit)
            addExternalLink(context, section, R.string.help_guava_link, GUAVA_URL)
            addExternalLink(context, section, R.string.help_apache_license_link, APACHE_LICENSE_URL)
            addExternalLink(context, section, R.string.help_bsd_license_link, BSD_LICENSE_URL)
            addExternalLink(context, section, R.string.help_repository, GITHUB_URL)
            addBody(context, section, R.string.help_license)
            addExternalLink(context, section, R.string.help_license, LICENSE_URL)
            addBody(context, section, R.string.help_version, versionName)
        }
        addSection(context, R.string.help_related_heading) { section ->
            addBody(context, section, R.string.help_project_blackjack)
            addExternalLink(context, section, R.string.help_project_blackjack_link, BLACKJACK_URL)
            addBody(context, section, R.string.help_project_encrypt)
            addExternalLink(context, section, R.string.help_project_encrypt_link, ENCRYPTION_URL)
            addBody(context, section, R.string.help_project_hernownet)
            addExternalLink(context, section, R.string.help_project_hernownet_link, HERNOWNET_URL)
        }
        addSection(context, R.string.help_support_heading) { section ->
            addBody(context, section, R.string.help_support_body)
            section.addView(ImageView(context).apply {
                setImageResource(R.drawable.paypal_qr)
                contentDescription = context.getString(R.string.help_support_qr_description)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(context, 112), dp(context, 112)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 6)
            })
            val supportButton = Button(context).apply {
                setText(R.string.help_support_button)
                isAllCaps = false
                minHeight = dp(context, 48)
                isFocusable = false
                isFocusableInTouchMode = false
                setBackgroundResource(R.drawable.keyboard_button_background)
                backgroundTintList = null
                setTextColor(ContextCompat.getColorStateList(context, R.color.keyboard_button_text))
                elevation = 0f
                stateListAnimator = null
                setOnClickListener { openExternal(context, PAYPAL_URL) }
            }
            section.addView(supportButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addSection(context, R.string.help_contact_heading) { section ->
            addBody(context, section, R.string.help_contact_body)
            addExternalLink(context, section, R.string.help_contact_email, SUPPORT_EMAIL_URL)
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

    private fun addExternalLink(context: Context, section: LinearLayout, labelId: Int, url: String) {
        val link = text(context, labelId, 14f).apply {
            paint.isUnderlineText = true
            isClickable = true
            isFocusable = true
            setOnClickListener { openExternal(context, url) }
        }
        section.addView(link, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 6)
        })
    }

    private fun openExternal(context: Context, url: String) {
        try {
            val uri = Uri.parse(url)
            context.startActivity(
                Intent(if (uri.scheme == "mailto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.help_open_link_failed, Toast.LENGTH_LONG).show()
        }
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
        const val ABOUT_URL = "https://amandahernow.github.io/barkey/about.html"
        const val PRIVACY_URL = "https://amandahernow.github.io/barkey/privacy-policy.html"
        const val GITHUB_URL = "https://github.com/AmandaHernow/barkey"
        const val LICENSE_URL = "https://github.com/AmandaHernow/barkey/blob/main/LICENSE"
        const val ZXING_CPP_URL = "https://github.com/zxing-cpp/zxing-cpp"
        const val ZXING_JAVA_URL = "https://github.com/zxing/zxing"
        const val ANDROIDX_URL = "https://github.com/androidx/androidx"
        const val GUAVA_URL = "https://github.com/google/guava"
        const val APACHE_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
        const val BSD_LICENSE_URL = "https://opensource.org/license/bsd-3-clause/"
        const val BLACKJACK_URL = "https://github.com/AmandaHernow/BlackJackCounterTool"
        const val ENCRYPTION_URL = "https://github.com/AmandaHernow/EncryptionDecryptionUtilityTool"
        const val HERNOWNET_URL = "https://hernow.net"
        const val PAYPAL_URL = "https://www.paypal.me/AmandaHernow"
        const val SUPPORT_EMAIL_URL = "mailto:barkey.support@hernow.net"
    }
}
