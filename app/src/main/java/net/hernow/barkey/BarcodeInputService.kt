// Modified by Amanda Hernow for BarKey in 2026; based on the 2020 Android Barcode Keyboard project.
package net.hernow.barkey

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BarcodeInputService : InputMethodService() {
    private val sessionGate = ScanSessionGate()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val cameraLifecycle by lazy { ServiceCameraLifecycle() }
    private val analyzerExecutor by lazy { Executors.newSingleThreadExecutor() }
    private val inputEditor = InputConnectionEditor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var observedCameraState: LiveData<CameraState>? = null
    private var cameraStateObserver: Observer<CameraState>? = null
    private var previewView: PreviewView? = null
    private var statusView: TextView? = null
    private var keyboardRoot: LinearLayout? = null
    private var keyboardContainer: FrameLayout? = null
    private var aboutHelpPanel: AboutHelpPanel? = null
    @Volatile
    private var helpVisible = false
    private var helpButton: Button? = null
    private var cameraFrame: FrameLayout? = null
    private var developerSignature: TextView? = null
    private var themedButtons: List<Button> = emptyList()
    private var toolbarButtons: List<Button> = emptyList()
    private var keyboardScrollView: ScrollView? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var permissionButton: Button? = null
    private var undoButton: Button? = null
    private var permissionResultReceiver: BroadcastReceiver? = null
    @Volatile
    private var currentSessionId = 0L
    @Volatile
    private var resultCallbackToken: Any = Any()
    private var currentSuffix = ScanSuffix.NONE
    private var cameraRequestId = 0L

    override fun onCreate() {
        super.onCreate()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != PermissionState.ACTION_PERMISSION_RESULT) return
                refreshPermissionUi()
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    if (isInputViewShown && !helpVisible) startCamera(currentSessionId)
                }
                if (!isInputViewShown) requestShowScannerKeyboard()
            }
        }
        val filter = IntentFilter(PermissionState.ACTION_PERMISSION_RESULT)
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        permissionResultReceiver = receiver
    }

    override fun onCreateInputView(): View {
        removeKeyboardLayoutListener()
        val density = resources.displayMetrics.density
        fun toolbarButtonWidthDp() = (64f * resources.configuration.fontScale).toInt().coerceAtLeast(60)
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = getString(R.string.scanner_keyboard_description)
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val controlsScrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFocusable = false
            isFocusableInTouchMode = false
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val frame = FrameLayout(this).apply {
            contentDescription = getString(R.string.camera_preview_description)
            clipToOutline = true
        }
        // Keep the live camera surface outside the scrolling panel so compact controls scrolling
        // cannot move the scan target completely offscreen.
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = getString(R.string.camera_preview_description)
        }
        frame.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val status = TextView(this).apply {
            text = getString(R.string.scanner_ready)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minHeight = dp(52)
            contentDescription = getString(R.string.scanner_status_description)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusRow.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, dp(2), dp(4), dp(2))
            },
        )
        val aboutButton = Button(this).apply {
            text = getString(R.string.help_icon_label)
            contentDescription = getString(R.string.help_icon_description)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(0, 0, 0, 0)
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundResource(R.drawable.help_icon_background)
            setTextColor(ContextCompat.getColor(this@BarcodeInputService, R.color.keyboard_text_secondary))
            textSize = 19f
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { openHelpPanel() }
        }
        statusRow.addView(aboutButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        fun toolbarButton(label: String, description: String, action: () -> Unit) = Button(this).apply {
            text = label
            styleKeyboardButton(this, 13f)
            minWidth = 0
            minHeight = dp(48)
            setPadding(dp(1), 0, dp(1), 0)
            contentDescription = description
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { action() }
        }

        val undo = toolbarButton(getString(R.string.undo_short), getString(R.string.undo_scan)) { undoScan() }.apply {
            isEnabled = false
        }
        val editingButtons = listOf(
            undo,
            toolbarButton(getString(R.string.left_arrow), getString(R.string.move_cursor_left)) {
            editCurrentField { connection, _ -> inputEditor.moveCursorLeft(connection) }
            },
            toolbarButton(getString(R.string.right_arrow), getString(R.string.move_cursor_right)) {
            editCurrentField { connection, _ -> inputEditor.moveCursorRight(connection) }
            },
            toolbarButton(getString(R.string.backspace_symbol), getString(R.string.backspace)) {
            editCurrentField { connection, _ -> inputEditor.backspace(connection) }
            },
            toolbarButton(getString(R.string.tab_short), getString(R.string.next_field)) {
            editCurrentField { connection, editorInfo -> inputEditor.nextField(connection, editorInfo) }
            },
            toolbarButton(getString(R.string.enter_short), getString(R.string.enter_action)) {
            editCurrentField { connection, editorInfo -> inputEditor.enter(connection, editorInfo) }
            },
            toolbarButton(getString(R.string.space_key), getString(R.string.insert_space)) {
            editCurrentField { connection, _ -> inputEditor.insertSpace(connection) }
            },
        )
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val switchButton = Button(this).apply {
            text = getString(R.string.switch_keyboard)
            minHeight = dp(52)
            contentDescription = getString(R.string.switch_keyboard)
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { switchKeyboard() }
        }
        val hideButton = Button(this).apply {
            text = getString(R.string.hide_keyboard)
            minHeight = dp(52)
            contentDescription = getString(R.string.hide_keyboard)
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener {
                inputEditor.clearUndo()
                refreshUndoButton()
                requestHideSelf(0)
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(switchButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
            })
            addView(hideButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), dp(2), dp(3), dp(2))
            })
        }
        val developerSignature = TextView(this).apply {
            text = getString(R.string.developer_signature)
            textSize = 10f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(1), dp(4), dp(3))
            isFocusable = false
            isClickable = false
        }
        val enableCamera = Button(this).apply {
            text = getString(R.string.enable_camera)
            styleKeyboardButton(this, 14f)
            minHeight = dp(52)
            contentDescription = getString(R.string.enable_camera)
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { requestCameraPermissionOrOpenSettings() }
            visibility = View.GONE
        }
        val frameHeight = resources.getDimensionPixelSize(R.dimen.scanner_preview_max_height)
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, frameHeight))
        controls.addView(statusRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        controls.addView(enableCamera, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        controls.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        controls.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val signatureSpacer = View(this)
        controls.addView(signatureSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        controls.addView(developerSignature, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        controlsScrollView.addView(
            controls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            controlsScrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val container = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@BarcodeInputService, R.color.keyboard_background))
            isFocusable = false
            isFocusableInTouchMode = false
        }
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val helpPanel = AboutHelpPanel(this, applicationVersionName()) { closeHelpPanel() }
        container.addView(helpPanel.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        helpPanel.view.visibility = if (helpVisible) View.VISIBLE else View.GONE
        root.visibility = if (helpVisible) View.GONE else View.VISIBLE

        rebuildToolbarRows(
            toolbar,
            editingButtons,
            (root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - root.paddingLeft - root.paddingRight,
            density,
            toolbarButtonWidthDp(),
            dp(48),
        )
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val measuredWidth = (
                    root.width - root.paddingLeft - root.paddingRight
                ).coerceAtLeast(1)
                rebuildToolbarRows(toolbar, editingButtons, measuredWidth, density, toolbarButtonWidthDp(), dp(48))

                val viewportHeight = root.height
                // Measure fixed content independently of the preview and the fill-viewport spacer.
                // This makes each layout pass choose the same preview height for the same window.
                val fixedControlsHeight = root.paddingTop + root.paddingBottom +
                    statusRow.height + enableCamera.height + toolbar.height + actions.height + developerSignature.height
                val minPreview = resources.getDimensionPixelSize(R.dimen.scanner_preview_min_height)
                val maxPreview = resources.getDimensionPixelSize(R.dimen.scanner_preview_max_height)
                if (viewportHeight > 0 && fixedControlsHeight > 0) {
                    val targetHeight = ResponsiveKeyboardLayoutPolicy.previewHeight(
                        viewportHeightPx = viewportHeight,
                        fixedContentHeightPx = fixedControlsHeight,
                        minPreviewHeightPx = minPreview,
                        maxPreviewHeightPx = maxPreview,
                    )
                    if (frame.layoutParams.height != targetHeight) {
                        frame.layoutParams = frame.layoutParams.apply { height = targetHeight }
                    }
                }
            }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        layoutListener = listener

        previewView = preview
        statusView = status
        keyboardRoot = root
        keyboardContainer = container
        aboutHelpPanel = helpPanel
        helpButton = aboutButton
        cameraFrame = frame
        this.developerSignature = developerSignature
        permissionButton = enableCamera
        undoButton = undo
        keyboardScrollView = controlsScrollView
        toolbarButtons = editingButtons
        themedButtons = editingButtons + listOf(switchButton, hideButton, enableCamera)
        applyKeyboardTheme()
        refreshUndoButton()
        return container
    }

    /** Keep the scanner docked and prevent landscape extract/fullscreen mode. */
    override fun onEvaluateFullscreenMode(): Boolean {
        super.onEvaluateFullscreenMode()
        return false
    }

    override fun onConfigureWindow(window: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(window, false, isCandidatesOnly)
        applyDockedWindowLayout(window)
    }

    private fun applyDockedWindowLayout(window: Window) {
        val usableHeight = usableDisplayHeightPx(window)
        val heightCap = (usableHeight * MAX_IME_DISPLAY_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, heightCap)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        clearPendingResults()
        inputEditor.clearUndo()
        stopCamera()
        currentSessionId = sessionGate.beginSession()
        currentSuffix = ScanSettings.suffixFor(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshPermissionUi()
        refreshUndoButton()
        if (!helpVisible) startCamera(currentSessionId)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && helpVisible) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && helpVisible) {
            closeHelpPanel()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val connection = currentInputConnection ?: return
        inputEditor.onSelectionChanged(
            AndroidEditorConnection(connection),
            currentSessionId,
            newSelStart,
            newSelEnd,
        )
        refreshUndoButton()
    }

    /** Camera scanning needs an on-screen view even when an emulator/tablet has a hardware keyboard. */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (!isInputViewShown) return
        refreshPermissionUi()
        if (!helpVisible && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(currentSessionId)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        resetHelpPanelForHiddenWindow()
        inputEditor.clearUndo()
        refreshUndoButton()
        stopCamera()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopCamera()
        clearPendingResults()
        inputEditor.clearUndo()
        sessionGate.endSession()
        currentSessionId = 0L
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        resetHelpPanelForHiddenWindow()
        inputEditor.clearUndo()
        refreshUndoButton()
        stopCamera()
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyKeyboardTheme()
        updateFullscreenMode()
        getWindow()?.window?.let(::applyDockedWindowLayout)
        val rotation = previewView?.display?.rotation
        if (rotation == null) {
            keyboardScrollView?.requestLayout()
            return
        }
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
        keyboardScrollView?.requestLayout()
    }

    override fun onDestroy() {
        stopCamera()
        clearPendingResults()
        inputEditor.clearUndo()
        sessionGate.endSession()
        permissionResultReceiver?.let { unregisterReceiver(it) }
        permissionResultReceiver = null
        analyzerExecutor.shutdownNow()
        cameraLifecycle.destroy()
        removeKeyboardLayoutListener()
        super.onDestroy()
    }

    private fun rebuildToolbarRows(
        toolbar: LinearLayout,
        buttons: List<Button>,
        widthPx: Int,
        density: Float,
        buttonMinimumWidthDp: Int,
        buttonHeightPx: Int,
    ) {
        val columns = ResponsiveKeyboardLayoutPolicy.toolbarColumns(widthPx, density, buttonMinimumWidthDp)
        if (toolbar.tag == columns) return
        toolbar.tag = columns
        toolbar.removeAllViews()
        buttons.chunked(columns).forEach { rowButtons ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowButtons.forEach { button ->
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            if (rowButtons.size < columns) {
                row.addView(View(this), LinearLayout.LayoutParams(0, buttonHeightPx, (columns - rowButtons.size).toFloat()))
            }
            toolbar.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun removeKeyboardLayoutListener() {
        layoutListener?.let { listener ->
            keyboardRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        layoutListener = null
        keyboardScrollView = null
        keyboardRoot = null
        keyboardContainer = null
        aboutHelpPanel = null
        helpButton = null
    }

    private fun applyKeyboardTheme() {
        val primaryText = ContextCompat.getColor(this, R.color.keyboard_text_primary)
        val background = ContextCompat.getColor(this, R.color.keyboard_background)
        keyboardRoot?.setBackgroundColor(background)
        keyboardContainer?.setBackgroundColor(background)
        aboutHelpPanel?.applyTheme(this)
        helpButton?.apply {
            setBackgroundResource(R.drawable.help_icon_background)
            setTextColor(ContextCompat.getColor(this@BarcodeInputService, R.color.keyboard_text_secondary))
        }
        keyboardScrollView?.setBackgroundColor(background)
        cameraFrame?.setBackgroundResource(R.drawable.keyboard_camera_background)
        statusView?.apply {
            setTextColor(primaryText)
            setBackgroundResource(R.drawable.keyboard_status_background)
        }
        themedButtons.forEach { styleKeyboardButton(it, if (it in toolbarButtons) 13f else 14f) }
        developerSignature?.apply {
            setTextColor(ContextCompat.getColor(this@BarcodeInputService, R.color.keyboard_engraving_text))
            setShadowLayer(
                resources.displayMetrics.density,
                0f,
                resources.displayMetrics.density,
                ContextCompat.getColor(this@BarcodeInputService, R.color.keyboard_engraving_shadow),
            )
        }
    }

    private fun styleKeyboardButton(button: Button, textSizeSp: Float) {
        button.setBackgroundResource(R.drawable.keyboard_button_background)
        button.backgroundTintList = null
        button.setTextColor(ContextCompat.getColorStateList(this, R.color.keyboard_button_text))
        button.setAllCaps(false)
        button.textSize = textSizeSp
        button.elevation = 0f
        button.stateListAnimator = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun usableDisplayHeightPx(window: Window): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = window.windowManager.currentWindowMetrics
            val insetTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val insets = metrics.windowInsets.getInsets(insetTypes)
            return (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        }
        val metrics = DisplayMetrics()
        window.windowManager.defaultDisplay.getRealMetrics(metrics)
        val insets = window.decorView.rootWindowInsets
        val verticalInsets = if (insets != null) insets.stableInsetTop + insets.stableInsetBottom else 0
        return (metrics.heightPixels - verticalInsets).coerceAtLeast(1)
    }

    private fun openHelpPanel() {
        if (helpVisible) return
        helpVisible = true
        clearPendingResults()
        keyboardRoot?.visibility = View.GONE
        aboutHelpPanel?.view?.visibility = View.VISIBLE
        stopCamera()
    }

    private fun closeHelpPanel() {
        if (!helpVisible) return
        helpVisible = false
        aboutHelpPanel?.view?.visibility = View.GONE
        keyboardRoot?.visibility = View.VISIBLE
        refreshPermissionUi()
        if (isInputViewShown && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(currentSessionId)
        }
    }

    private fun resetHelpPanelForHiddenWindow() {
        helpVisible = false
        clearPendingResults()
        aboutHelpPanel?.view?.visibility = View.GONE
        keyboardRoot?.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun applicationVersionName(): String = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        packageInfo.versionName ?: getString(R.string.help_version_unavailable)
    } catch (error: PackageManager.NameNotFoundException) {
        Log.e(TAG, "Unable to read application version metadata", error)
        getString(R.string.help_version_unavailable)
    }

    private fun startCamera(sessionId: Long) {
        if (helpVisible || !sessionGate.isCurrent(sessionId)) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setPermissionRequiredStatus()
            return
        }

        val preview = previewView ?: return
        setStatus(R.string.camera_starting)
        val requestId = ++cameraRequestId
        cameraLifecycle.resume()

        try {
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                if (requestId != cameraRequestId || helpVisible || !sessionGate.isCurrent(sessionId) || !isInputViewShown) return@addListener
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        showCameraRetry(R.string.rear_camera_unavailable)
                        cameraLifecycle.pause()
                        return@addListener
                    }

                    val rotation = preview.display?.rotation ?: android.view.Surface.ROTATION_0
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build(),
                        )
                        .setTargetRotation(rotation)
                        .build()
                    imageAnalysis.setAnalyzer(analyzerExecutor, BarcodeFrameAnalyzer.create(sessionId, ::onDecoded))

                    val cameraPreview = Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                        .also { it.setSurfaceProvider(preview.surfaceProvider) }

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        cameraLifecycle,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        cameraPreview,
                        imageAnalysis,
                    )
                    previewUseCase = cameraPreview
                    analysisUseCase = imageAnalysis
                    val stateObserver = Observer<CameraState> { state ->
                        if (state.type == CameraState.Type.CLOSED && state.error != null &&
                            sessionGate.isCurrent(sessionId)
                        ) {
                            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                Log.e(TAG, "CameraX reported a closed camera with an error")
                                showCameraRetry(R.string.camera_start_failed)
                            } else {
                                setPermissionRequiredStatus()
                            }
                            stopCamera()
                        }
                    }
                    cameraStateObserver = stateObserver
                    observedCameraState = camera.cameraInfo.cameraState
                    observedCameraState?.observe(cameraLifecycle, stateObserver)
                    setStatus(R.string.scanner_ready)
                    permissionButton?.visibility = View.GONE
                    preview.post {
                        if (!sessionGate.isCurrent(sessionId) || cameraRequestId != requestId) return@post
                        val point = preview.meteringPointFactory.createPoint(preview.width / 2f, preview.height / 2f)
                        camera.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(
                                point,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                            ).setAutoCancelDuration(2, TimeUnit.SECONDS).build(),
                        )
                    }
                } catch (error: SecurityException) {
                    Log.e(TAG, "Camera permission was revoked during startup", error)
                    setPermissionRequiredStatus()
                    stopCamera()
                } catch (error: Exception) {
                    Log.e(TAG, "Camera startup failed", error)
                    showCameraRetry(R.string.camera_start_failed)
                    stopCamera()
                }
            }, mainExecutor)
        } catch (error: SecurityException) {
            Log.e(TAG, "Camera permission is unavailable", error)
            setPermissionRequiredStatus()
            stopCamera()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to initialise CameraX", error)
            showCameraRetry(R.string.camera_start_failed)
            stopCamera()
        }
    }

    private fun stopCamera() {
        cameraRequestId += 1
        cameraStateObserver?.let { observer -> observedCameraState?.removeObserver(observer) }
        cameraStateObserver = null
        observedCameraState = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        previewUseCase = null
        analysisUseCase = null
        cameraLifecycle.pause()
    }

    private fun clearPendingResults() {
        mainHandler.removeCallbacksAndMessages(resultCallbackToken)
        resultCallbackToken = Any()
    }

    private fun onDecoded(sessionId: Long, value: String) {
        if (helpVisible || sessionId != currentSessionId) return
        val callbackToken = resultCallbackToken
        mainHandler.postAtTime(resultCallback@{
            if (helpVisible || !sessionGate.isCurrent(sessionId) || !isInputViewShown) return@resultCallback
            val inputConnection: InputConnection = currentInputConnection ?: return@resultCallback
            if (!sessionGate.shouldAccept(sessionId, value, SystemClock.elapsedRealtime())) return@resultCallback

            val committed = try {
                inputEditor.commitScan(
                    connection = AndroidEditorConnection(inputConnection),
                    sessionId = sessionId,
                    value = value,
                    recordUndo = currentSuffix == ScanSuffix.NONE && canTrackUndo(currentInputEditorInfo),
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Input field rejected barcode insertion (${error.javaClass.simpleName})")
                false
            }
            if (!committed) {
                setStatus(R.string.scan_delivery_failed)
                return@resultCallback
            }
            currentSuffix.keyCode?.let { keyCode ->
                inputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                inputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
            }
            refreshUndoButton()
            setStatus(R.string.scan_inserted)
        }, callbackToken, SystemClock.uptimeMillis())
    }

    private fun setStatus(messageId: Int) {
        statusView?.setText(messageId)
    }

    private fun refreshPermissionUi() {
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        permissionButton?.apply {
            visibility = if (granted) View.GONE else View.VISIBLE
            setText(if (PermissionState.isSettingsRequired(this@BarcodeInputService)) {
                R.string.open_camera_settings
            } else {
                R.string.enable_camera
            })
            contentDescription = text
        }
        if (!granted) setPermissionRequiredStatus()
    }

    private fun setPermissionRequiredStatus() {
        val message = if (PermissionState.isSettingsRequired(this)) {
            R.string.camera_permission_settings_required
        } else {
            R.string.camera_permission_required
        }
        setStatus(message)
        refreshPermissionButtonLabel()
    }

    private fun showCameraRetry(messageId: Int) {
        setStatus(messageId)
        permissionButton?.apply {
            visibility = View.VISIBLE
            setText(R.string.retry_camera)
            contentDescription = getString(R.string.retry_camera)
            setOnClickListener { startCamera(currentSessionId) }
        }
    }

    private fun refreshPermissionButtonLabel() {
        permissionButton?.apply {
            visibility = View.VISIBLE
            setText(if (PermissionState.isSettingsRequired(this@BarcodeInputService)) {
                R.string.open_camera_settings
            } else {
                R.string.enable_camera
            })
            contentDescription = text
            setOnClickListener { requestCameraPermissionOrOpenSettings() }
        }
    }

    private fun requestCameraPermissionOrOpenSettings() {
        try {
            if (PermissionState.isSettingsRequired(this)) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } else {
                startActivity(Intent(this, CameraPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open camera permission controls", error)
            setStatus(R.string.camera_permission_controls_unavailable)
        }
    }

    private fun switchKeyboard() {
        inputEditor.clearUndo()
        refreshUndoButton()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (switchToPreviousInputMethod()) return
                if (switchToNextInputMethod(false)) return
            }
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to switch input methods", error)
            setStatus(R.string.keyboard_switch_unavailable)
        }
    }

    private fun editCurrentField(edit: (EditorConnection, EditorInfo?) -> Boolean) {
        val connection = currentInputConnection
        if (connection == null) {
            setStatus(R.string.editing_unavailable)
            refreshUndoButton()
            return
        }
        inputEditor.clearUndo()
        val succeeded = try {
            edit(AndroidEditorConnection(connection), currentInputEditorInfo)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Input field rejected a keyboard editing operation (${error.javaClass.simpleName})")
            false
        }
        if (!succeeded) setStatus(R.string.editing_unavailable)
        refreshUndoButton()
    }

    private fun undoScan() {
        val undone = try {
            currentInputConnection?.let { inputEditor.undo(AndroidEditorConnection(it), currentSessionId) } ?: false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Scanner undo was rejected (${error.javaClass.simpleName})")
            false
        }
        if (undone) setStatus(R.string.scan_undo_complete)
        else setStatus(R.string.scan_undo_unavailable)
        refreshUndoButton()
    }

    private fun refreshUndoButton() {
        val connection = currentInputConnection?.let(::AndroidEditorConnection)
        undoButton?.isEnabled = inputEditor.canUndo(connection, currentSessionId)
    }

    private fun canTrackUndo(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        ) return false

        val inputClass = editorInfo.inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and android.text.InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            android.text.InputType.TYPE_CLASS_TEXT -> variation !in setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            android.text.InputType.TYPE_CLASS_NUMBER -> variation != android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> true
        }
    }

    @Suppress("DEPRECATION")
    private fun requestShowScannerKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestShowSelf(0)
            return
        }

        val inputMethodToken = getWindow()?.window?.attributes?.token ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.showSoftInputFromInputMethod(inputMethodToken, 0)
    }

    private class ServiceCameraLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        override val lifecycle: Lifecycle
            get() = registry

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun pause() {
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val TAG = "BarcodeInputService"
        const val MAX_IME_DISPLAY_HEIGHT_FRACTION = 0.44f
    }
}
