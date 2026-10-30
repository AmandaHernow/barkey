// Copyright 2026 Amanda Hernow. Licensed under the Apache License, Version 2.0.
package net.hernow.barkey

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/** Launcher setup guide. Android remains in control of enabling and selecting input methods. */
class SetupActivity : Activity() {
    private var controlsExpanded = false
    private var enabledStatus: TextView? = null
    private var selectedStatus: TextView? = null
    private var cameraStatus: TextView? = null
    private var cameraButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controlsExpanded = savedInstanceState?.getBoolean(STATE_CONTROLS_EXPANDED) ?: false
        setContentView(createContent())
        refreshSetupStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONTROLS_EXPANDED, controlsExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    private fun createContent(): View {
        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.keyboard_background))
            clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val centered = FrameLayout(this)
        val pageWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(dp(280))
            .coerceAtMost(dp(720))
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        centered.addView(page, FrameLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(18))
        }
        hero.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            contentDescription = getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginEnd = dp(14) })
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(text(getString(R.string.setup_welcome), 26f, bold = true))
        heroCopy.addView(text(getString(R.string.setup_intro), 15f))
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(hero)

        page.addView(stepCard(
            number = "1",
            iconResource = R.drawable.ic_setup_keyboard,
            title = getString(R.string.setup_enable_title),
            body = getString(R.string.setup_enable_body),
            status = getString(R.string.setup_keyboard_status_checking),
            onStatus = { enabledStatus = it },
            buttonLabel = getString(R.string.setup_enable_button),
            buttonAction = ::openInputMethodSettings,
        ), cardLayoutParams())
        page.addView(stepCard(
            number = "2",
            iconResource = R.drawable.ic_setup_keyboard,
            title = getString(R.string.setup_select_title),
            body = getString(R.string.setup_select_body),
            status = getString(R.string.setup_keyboard_status_checking),
            onStatus = { selectedStatus = it },
            buttonLabel = getString(R.string.setup_picker_button),
            buttonAction = ::showKeyboardPicker,
        ), cardLayoutParams())
        page.addView(stepCard(
            number = "3",
            iconResource = R.drawable.ic_setup_camera,
            title = getString(R.string.setup_camera_title),
            body = getString(R.string.setup_camera_body),
            status = getString(R.string.setup_camera_status_checking),
            onStatus = { cameraStatus = it },
            buttonLabel = getString(R.string.enable_camera),
            buttonAction = ::requestCameraPermission,
            onButton = { cameraButton = it },
        ), cardLayoutParams())
        page.addView(stepCard(
            number = "4",
            iconResource = R.drawable.ic_setup_barcode,
            title = getString(R.string.setup_start_title),
            body = getString(R.string.setup_start_body),
            buttonLabel = getString(R.string.setup_start_button),
            buttonAction = ::showKeyboardPicker,
        ), cardLayoutParams())

        page.addView(controlsGuide(), cardLayoutParams())

        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        listOf(
            getString(R.string.setup_link_about) to ABOUT_URL,
            getString(R.string.setup_link_privacy) to PRIVACY_URL,
            getString(R.string.setup_link_github) to GITHUB_URL,
            getString(R.string.setup_link_support) to SUPPORT_EMAIL_URL,
        ).forEachIndexed { index, (label, url) ->
            if (index > 0) links.addView(text(" · ", 14f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val link = text(label, 14f).apply {
                setTextColor(color(R.color.keyboard_text_primary))
                paint.isUnderlineText = true
                contentDescription = label
                isClickable = true
                isFocusable = true
                setOnClickListener { openExternal(url) }
            }
            links.addView(link)
        }
        page.addView(links)
        root.addView(centered, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun controlsGuide(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundResource(R.drawable.help_section_background)
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (controlsExpanded) View.VISIBLE else View.GONE
        }
        val chevron = text("⌄", 26f, bold = true).apply {
            text = if (controlsExpanded) "⌃" else "⌄"
            gravity = Gravity.CENTER
            setTextColor(color(R.color.setup_controls_accent))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(52)
            contentDescription = controlsGuideDescription()
            ViewCompat.setStateDescription(
                this,
                getString(if (controlsExpanded) R.string.setup_controls_expanded else R.string.setup_controls_collapsed),
            )
            setOnClickListener {
                controlsExpanded = !controlsExpanded
                details.visibility = if (controlsExpanded) View.VISIBLE else View.GONE
                chevron.text = if (controlsExpanded) "⌃" else "⌄"
                contentDescription = controlsGuideDescription()
                ViewCompat.setStateDescription(
                    this,
                    getString(if (controlsExpanded) R.string.setup_controls_expanded else R.string.setup_controls_collapsed),
                )
                announceForAccessibility(
                    getString(if (controlsExpanded) R.string.setup_controls_expanded else R.string.setup_controls_collapsed),
                )
            }
        }
        val headingCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headingCopy.addView(text(getString(R.string.setup_controls_title), 18f, bold = true).apply {
            setTextColor(color(R.color.setup_controls_accent))
        })
        headingCopy.addView(text(getString(R.string.setup_controls_subtitle), 13f).apply {
            setTextColor(color(R.color.keyboard_text_secondary))
        })
        heading.addView(headingCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(chevron, LinearLayout.LayoutParams(dp(40), dp(48)))
        card.addView(heading)

        addGuideGroup(details, R.string.setup_controls_scanning_heading, listOf(
            R.string.setup_controls_fast,
            R.string.setup_controls_accurate,
            R.string.setup_controls_preview,
            R.string.setup_controls_focus,
            R.string.setup_controls_single_scan,
            R.string.setup_controls_torch,
            R.string.setup_controls_tap_focus,
        ))
        addGuideGroup(details, R.string.setup_controls_editing_heading, listOf(
            R.string.setup_controls_undo,
            R.string.setup_controls_backspace,
            R.string.setup_controls_arrows,
            R.string.setup_controls_space,
            R.string.setup_controls_tab,
            R.string.setup_controls_enter,
        ))
        addGuideGroup(details, R.string.setup_controls_keyboard_heading, listOf(
            R.string.setup_controls_switch_keyboard,
            R.string.setup_controls_hide_keyboard,
            R.string.setup_controls_resize,
        ))
        details.addView(text(getString(R.string.setup_controls_example), 14f).apply {
            setTextColor(color(R.color.keyboard_text_primary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        details.addView(text(getString(R.string.setup_controls_help_note), 13f).apply {
            setTextColor(color(R.color.keyboard_text_secondary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        card.addView(details)
        return card
    }

    private fun addGuideGroup(parent: LinearLayout, headingResource: Int, entries: List<Int>) {
        parent.addView(text(getString(headingResource), 15f, bold = true).apply {
            setTextColor(color(R.color.setup_controls_accent))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(2)
        })
        entries.forEach { resource ->
            parent.addView(text(getText(resource), 14f), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) })
        }
    }

    private fun controlsGuideDescription(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getString(R.string.setup_controls_accessibility)
    } else {
        getString(
            R.string.setup_controls_accessibility_legacy,
            getString(if (controlsExpanded) R.string.setup_controls_expanded else R.string.setup_controls_collapsed),
        )
    }

    private fun stepCard(
        number: String,
        iconResource: Int,
        title: String,
        body: String,
        status: String? = null,
        onStatus: ((TextView) -> Unit)? = null,
        buttonLabel: String,
        buttonAction: () -> Unit,
        onButton: ((Button) -> Unit)? = null,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.help_section_background)
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = TextView(this).apply {
            text = number
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.keyboard_text_primary))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color(R.color.keyboard_surface_pressed))
            }
        }
        heading.addView(badge, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        heading.addView(ImageView(this).apply {
            setImageResource(iconResource)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.keyboard_text_secondary))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
        heading.addView(text(title, 18f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(heading)
        card.addView(text(body, 14f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        if (status != null) {
            val state = text(status, 13f).apply { setTextColor(color(R.color.keyboard_text_secondary)) }
            card.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            onStatus?.invoke(state)
        }
        val action = Button(this).apply {
            text = buttonLabel
            isAllCaps = false
            minHeight = dp(48)
            setBackgroundResource(R.drawable.keyboard_button_background)
            backgroundTintList = null
            setTextColor(ContextCompat.getColorStateList(this@SetupActivity, R.color.keyboard_button_text))
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { buttonAction() }
        }
        card.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        onButton?.invoke(action)
        return card
    }

    private fun cardLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(10) }

    private fun refreshSetupStatus() {
        val component = ComponentName(this, BarcodeInputService::class.java).flattenToString()
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val state = InputMethodSetupState.resolve(
            enabledIds = manager?.enabledInputMethodList.orEmpty().map { it.id },
            selectedId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD),
            componentId = component,
        )
        enabledStatus?.apply {
            setText(if (state.enabled) R.string.setup_keyboard_enabled else R.string.setup_keyboard_not_enabled)
            setTextColor(color(if (state.enabled) R.color.keyboard_text_primary else R.color.keyboard_text_secondary))
        }
        selectedStatus?.apply {
            setText(if (state.selected) R.string.setup_keyboard_selected else R.string.setup_keyboard_not_selected)
            setTextColor(color(if (state.selected) R.color.keyboard_text_primary else R.color.keyboard_text_secondary))
        }
        cameraStatus?.apply {
            val granted = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            setText(if (granted) R.string.setup_camera_allowed else R.string.setup_camera_not_allowed)
            setTextColor(color(if (granted) R.color.keyboard_text_primary else R.color.keyboard_text_secondary))
        }
        val cameraGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val settingsRequired = PermissionState.isSettingsRequired(this)
        cameraButton?.apply {
            isEnabled = !cameraGranted
            setText(when {
                cameraGranted -> R.string.setup_camera_button_allowed
                settingsRequired -> R.string.open_camera_settings
                else -> R.string.enable_camera
            })
        }
    }

    private fun openInputMethodSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            openSystemSettings()
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.setup_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showKeyboardPicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
            ?: Toast.makeText(this, R.string.setup_picker_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun requestCameraPermission() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.setup_camera_already_allowed, Toast.LENGTH_SHORT).show()
            return
        }
        if (PermissionState.isSettingsRequired(this)) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.setup_settings_unavailable, Toast.LENGTH_LONG).show()
            }
            return
        }
        startActivity(Intent(this, CameraPermissionActivity::class.java))
    }

    private fun openExternal(url: String) {
        try {
            val uri = Uri.parse(url)
            startActivity(Intent(if (uri.scheme == "mailto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.setup_browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun text(value: CharSequence, sizeSp: Float, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color(R.color.keyboard_text_primary))
        if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
    }

    private fun color(resourceId: Int): Int = ContextCompat.getColor(this, resourceId)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val STATE_CONTROLS_EXPANDED = "setup_controls_expanded"
        const val ABOUT_URL = "https://amandahernow.github.io/barkey/about.html"
        const val PRIVACY_URL = "https://amandahernow.github.io/barkey/privacy-policy.html"
        const val GITHUB_URL = "https://github.com/AmandaHernow/barkey"
        const val SUPPORT_EMAIL_URL = "mailto:barkey.support@hernow.net"
    }
}
