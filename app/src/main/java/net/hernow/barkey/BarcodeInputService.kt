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
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private val scanModes = ScanModeController()
    private val barcodeDecoder = HybridBarcodeDecoder()
    private val decodeTiming = DecodeTimingRecorder()
    private val torchState = TorchStateController()

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
    private var scanModeButton: Button? = null
    private var cameraFrame: FrameLayout? = null
    private var torchButton: ImageButton? = null
    private var activeCamera: Camera? = null
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
    @Volatile
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
        fun toolbarButtonMinWidthDp() = (56f * resources.configuration.fontScale).toInt().coerceAtLeast(56)
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
            contentDescription = getString(R.string.camera_preview_tap_to_focus)
            isFocusable = false
            isFocusableInTouchMode = false
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
        val modeButton = Button(this).apply {
            minWidth = dp(96)
            minHeight = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = getString(scanModes.snapshot().mode.descriptionResId)
            setOnClickListener { renderScanModeButton(this, scanModes.toggle()) }
        }
        statusRow.addView(modeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            setMargins(0, 0, dp(4), 0)
        })
        statusRow.addView(aboutButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        var pendingFocusPoint: Pair<Float, Float>? = null
        var touchStartX = 0f
        var touchStartY = 0f
        var focusGestureMoved = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        preview.setOnClickListener {
            val point = pendingFocusPoint ?: (preview.width / 2f to preview.height / 2f)
            pendingFocusPoint = null
            requestFocusAt(preview, point.first, point.second)
        }
        preview.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    focusGestureMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - touchStartX) > touchSlop ||
                        kotlin.math.abs(event.y - touchStartY) > touchSlop
                    ) focusGestureMoved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!focusGestureMoved) {
                        pendingFocusPoint = event.x to event.y
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pendingFocusPoint = null
                    true
                }
                else -> true
            }
        }

        val torchControl = ImageButton(this).apply {
            setImageResource(R.drawable.ic_torch)
            contentDescription = getString(R.string.torch_turn_on)
            minWidth = dp(48)
            minHeight = dp(48)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setBackgroundResource(R.drawable.torch_button_background)
            backgroundTintList = null
            imageTintList = ContextCompat.getColorStateList(this@BarcodeInputService, R.color.torch_icon_tint)
            isFocusable = false
            isFocusableInTouchMode = false
            elevation = 0f
            stateListAnimator = null
            visibility = View.GONE
            setOnClickListener { toggleTorch() }
        }
        frame.addView(
            torchControl,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(8), dp(8), 0, 0)
            },
        )
        renderScanModeButton(modeButton, scanModes.snapshot())

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
        )
        val spaceButton = toolbarButton(getString(R.string.space_key), getString(R.string.insert_space)) {
            editCurrentField { connection, _ -> inputEditor.insertSpace(connection) }
        }
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
            text = getString(R.string.hide_keyboard_label)
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
            setPadding(dp(4), dp(2), dp(4), dp(4))
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
        controlsScrollView.addView(
            controls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            controlsScrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            developerSignature,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        val baseLeft = dp(8)
        val baseTop = dp(4)
        val baseRight = dp(8)
        val baseBottom = dp(4)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                baseLeft + safeInsets.left,
                baseTop,
                baseRight + safeInsets.right,
                baseBottom + safeInsets.bottom,
            )
            windowInsets
        }

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
            spaceButton,
            (root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - root.paddingLeft - root.paddingRight,
            density,
            toolbarButtonMinWidthDp(),
        )
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val measuredWidth = (
                root.width - root.paddingLeft - root.paddingRight
            ).coerceAtLeast(1)
            rebuildToolbarRows(toolbar, editingButtons, spaceButton, measuredWidth, density, toolbarButtonMinWidthDp())

            val viewportHeight = root.height
            // Measure controls independently of the preview and scrolling viewport to keep preview sizing stable.
            val minPreview = resources.getDimensionPixelSize(R.dimen.scanner_preview_min_height)
            val signatureHeight = developerSignature.height.coerceAtLeast(dp(24))
            val requiredHeightWithoutSignature = root.paddingTop + root.paddingBottom +
                statusRow.height + enableCamera.height + toolbar.height + actions.height + minPreview
            val signatureShouldShow = ResponsiveKeyboardLayoutPolicy.shouldShowDeveloperSignature(
                viewportHeightPx = viewportHeight,
                requiredHeightWithoutSignaturePx = requiredHeightWithoutSignature,
                signatureHeightPx = signatureHeight,
            )
            val signatureVisibility = if (signatureShouldShow) View.VISIBLE else View.GONE
            if (developerSignature.visibility != signatureVisibility) {
                developerSignature.visibility = signatureVisibility
            }
            val fixedControlsHeight = root.paddingTop + root.paddingBottom +
                statusRow.height + enableCamera.height + toolbar.height + actions.height + developerSignature.height
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
        ViewCompat.requestApplyInsets(root)

        previewView = preview
        statusView = status
        keyboardRoot = root
        keyboardContainer = container
        aboutHelpPanel = helpPanel
        helpButton = aboutButton
        scanModeButton = modeButton
        cameraFrame = frame
        torchButton = torchControl
        renderTorchButton()
        this.developerSignature = developerSignature
        permissionButton = enableCamera
        undoButton = undo
        keyboardScrollView = controlsScrollView
        toolbarButtons = editingButtons + spaceButton
        themedButtons = toolbarButtons + listOf(modeButton, switchButton, hideButton, enableCamera)
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
        spaceButton: Button,
        widthPx: Int,
        density: Float,
        buttonMinimumWidthDp: Int,
    ) {
        val columns = ResponsiveKeyboardLayoutPolicy.toolbarColumns(widthPx, density, buttonMinimumWidthDp)
        if (toolbar.tag == columns) return
        toolbar.tag = columns
        toolbar.removeAllViews()
        buttons.chunked(columns).forEach { rowButtons ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val emptySlots = columns - rowButtons.size
            if (emptySlots > 0) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, emptySlots / 2f))
            }
            rowButtons.forEach { button ->
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            if (emptySlots > 0) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, emptySlots / 2f))
            }
            toolbar.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        toolbar.addView(spaceButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        })
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
        scanModeButton?.let(::styleScanModeButton)
        torchButton?.apply {
            setBackgroundResource(R.drawable.torch_button_background)
            backgroundTintList = null
            imageTintList = ContextCompat.getColorStateList(this@BarcodeInputService, R.color.torch_icon_tint)
        }
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

    private fun styleScanModeButton(button: Button) {
        button.setBackgroundResource(R.drawable.scan_mode_button_background)
        button.backgroundTintList = null
        button.setTextColor(ContextCompat.getColorStateList(this, R.color.keyboard_button_text))
        button.textSize = 12.5f
        button.setPadding(dp(8), 0, dp(8), 0)
        button.elevation = 0f
        button.stateListAnimator = null
        renderScanModeButton(button, scanModes.snapshot())
    }

    private fun renderScanModeButton(button: Button, snapshot: ScanModeSnapshot) {
        button.text = getString(snapshot.mode.labelResId)
        button.contentDescription = getString(snapshot.mode.descriptionResId)
        button.isActivated = snapshot.mode == ScanMode.ACCURATE
        button.isSelected = button.isActivated
    }

    private fun renderTorchButton() {
        torchButton?.apply {
            isActivated = torchState.isEnabled
            isSelected = torchState.isEnabled
            isEnabled = !torchState.isPending
            contentDescription = getString(
                if (torchState.isEnabled) R.string.torch_turn_off else R.string.torch_turn_on,
            )
        }
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
        if (activeCamera != null && analysisUseCase != null) return

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
                    imageAnalysis.setAnalyzer(
                        analyzerExecutor,
                        BarcodeFrameAnalyzer(
                            sessionId = sessionId,
                            cameraGeneration = requestId,
                            modes = scanModes,
                            decoder = barcodeDecoder,
                            timing = decodeTiming,
                            onDecoded = ::onDecoded,
                            onFailure = ::onDecoderFailure,
                        ),
                    )

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
                    activeCamera = camera
                    torchState.reset()
                    torchButton?.visibility = if (camera.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
                    renderTorchButton()
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
                        requestFocusAt(preview, preview.width / 2f, preview.height / 2f)
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
        activeCamera?.let { camera ->
            try {
                if (camera.cameraInfo.hasFlashUnit()) camera.cameraControl.enableTorch(false)
            } catch (_: Exception) {
                // Unbinding the camera also releases the torch if the device is already closing.
            } catch (_: LinkageError) {
                // Keep camera implementation failures isolated from keyboard teardown.
            }
        }
        activeCamera = null
        torchState.reset()
        torchButton?.visibility = View.GONE
        renderTorchButton()
        cameraStateObserver?.let { observer -> observedCameraState?.removeObserver(observer) }
        cameraStateObserver = null
        observedCameraState = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        previewUseCase = null
        analysisUseCase = null
        cameraLifecycle.pause()
    }

    private fun toggleTorch() {
        val camera = activeCamera ?: return
        if (helpVisible || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val hasFlashUnit = try {
            camera.cameraInfo.hasFlashUnit()
        } catch (_: Exception) {
            return
        } catch (_: LinkageError) {
            return
        }
        val request = torchState.beginToggle(hasFlashUnit) ?: return
        val cameraGeneration = cameraRequestId
        renderTorchButton()
        try {
            val future = camera.cameraControl.enableTorch(request.enabled)
            future.addListener({
                val succeeded = try {
                    future.get()
                    true
                } catch (_: Exception) {
                    false
                } catch (_: LinkageError) {
                    false
                }
                if (cameraGeneration != cameraRequestId || activeCamera !== camera) return@addListener
                if (torchState.complete(request, succeeded)) {
                    renderTorchButton()
                    if (!succeeded) setStatus(R.string.torch_activation_failed)
                }
            }, mainExecutor)
        } catch (_: Exception) {
            if (torchState.complete(request, succeeded = false)) {
                renderTorchButton()
                setStatus(R.string.torch_activation_failed)
            }
        } catch (_: LinkageError) {
            if (torchState.complete(request, succeeded = false)) {
                renderTorchButton()
                setStatus(R.string.torch_activation_failed)
            }
        }
    }

    private fun requestFocusAt(preview: PreviewView, x: Float, y: Float) {
        if (helpVisible || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val camera = activeCamera ?: return
        try {
            val point = preview.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            ).setAutoCancelDuration(2, TimeUnit.SECONDS).build()
            if (!camera.cameraInfo.isFocusMeteringSupported(action)) return
            val future = camera.cameraControl.startFocusAndMetering(action)
            future.addListener({
                try {
                    future.get()
                } catch (_: Exception) {
                    // Unsupported metering or a camera closing during focus is harmless.
                } catch (_: LinkageError) {
                    // Keep focus failures isolated from the input method.
                }
            }, mainExecutor)
        } catch (_: Exception) {
            // Some cameras do not support the requested AF/AE combination.
        } catch (_: LinkageError) {
            // Keep camera implementation failures from disrupting keyboard input.
        }
    }

    private fun clearPendingResults() {
        mainHandler.removeCallbacksAndMessages(resultCallbackToken)
        resultCallbackToken = Any()
    }

    private fun onDecoded(sessionId: Long, cameraGeneration: Long, modeGeneration: Long, value: String) {
        if (helpVisible || sessionId != currentSessionId || cameraGeneration != cameraRequestId ||
            !scanModes.isCurrent(modeGeneration)
        ) return
        val callbackToken = resultCallbackToken
        mainHandler.postAtTime(resultCallback@{
            if (helpVisible || !sessionGate.isCurrent(sessionId) || !isInputViewShown ||
                cameraGeneration != cameraRequestId || !scanModes.isCurrent(modeGeneration)
            ) return@resultCallback
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

    private fun onDecoderFailure(sessionId: Long, cameraGeneration: Long, modeGeneration: Long) {
        mainHandler.post {
            if (!helpVisible && sessionGate.isCurrent(sessionId) && cameraGeneration == cameraRequestId &&
                scanModes.isCurrent(modeGeneration)
            ) setStatus(R.string.scanner_decoder_unavailable)
        }
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
        if (!granted && activeCamera != null) stopCamera()
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
