// Copyright 2026 Amanda Hernow. Modified for BarKey.
package net.hernow.barkey

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import android.util.Rational
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
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
    // This handler is separate from resultCallbackToken so scanner callbacks remain untouched.
    private val backspaceRepeat = BackspaceRepeatController(
        scheduler = object : BackspaceRepeatController.Scheduler {
            override fun postDelayed(task: Runnable, delayMillis: Long) { mainHandler.postDelayed(task, delayMillis) }
            override fun remove(task: Runnable) { mainHandler.removeCallbacks(task) }
        },
        timeoutMillis = ViewConfiguration.getKeyRepeatTimeout().toLong(),
        intervalMillis = ViewConfiguration.getKeyRepeatDelay().toLong(),
    )
    private val scanModes = ScanModeController()
    private val barcodeDecoder = HybridBarcodeDecoder()
    private val decodeTiming = DecodeTimingRecorder()
    private val torchState = TorchStateController()
    private val keyboardHeight = KeyboardHeightState()
    private val previewCandidateState = PreviewCandidateState()
    private var renderedPreviewValues: List<String> = emptyList()
    private val singleScanGate = SingleScanGate()
    private var activeEditorField: EditorFieldKey? = null
    private val settingsCallbackGeneration = CallbackGeneration()
    private val focusRegionState = FocusRegionState(settingsCallbackGeneration, previewCandidateState)
    private val imageProxyTransformFactory = ImageProxyTransformFactory().apply {
        setUsingCropRect(true)
        setUsingRotationDegrees(false)
    }

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
    private var previewCandidateRow: LinearLayout? = null
    private var focusOverlay: View? = null
    private var previewToggleButton: Button? = null
    private var focusToggleButton: Button? = null
    private var singleScanToggleButton: Button? = null
    @Volatile private var previewEnabled = false
    @Volatile private var focusEnabled = false
    @Volatile private var singleScanEnabled = false
    private val extendedGeneration: Long get() = settingsCallbackGeneration.current()
    @Volatile private var previewOutputTransform: OutputTransform? = null
    @Volatile private var previewOutputTransformSize: Pair<Int, Int>? = null
    private var transformRefreshRunnable: Runnable? = null
    private var torchButton: ImageButton? = null
    private var activeCamera: Camera? = null
    private var developerSignature: TextView? = null
    private var themedButtons: List<Button> = emptyList()
    private var toolbarButtons: List<Button> = emptyList()
    private var resizeHandleView: View? = null
    private var measuredMinimumImeHeightPx: Int? = null
    private var measuredInputViewContentHeights: ResponsiveKeyboardLayoutPolicy.InputViewContentHeights? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var permissionButton: Button? = null
    private var undoButton: Button? = null
    private var permissionResultReceiver: BroadcastReceiver? = null
    @Volatile
    private var currentSessionId = 0L
    @Volatile
    private var resultCallbackToken: Any = Any()
    private var currentSuffix = ScanSuffix.NONE
    private var suppressBackspaceClick = false
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
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val controlsScrollView = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription = getString(R.string.keyboard_controls_description)
        }
        val resizeHandle = FrameLayout(this).apply {
            contentDescription = getString(R.string.resize_keyboard)
            minimumHeight = dp(48)
            isFocusable = false
            isFocusableInTouchMode = false
            val grip = View(this@BarcodeInputService).apply { updateResizeGripBackground(this) }
            addView(grip, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER))
        }
        var activeDragPointerId = MotionEvent.INVALID_POINTER_ID
        var dragStartRawY = 0f
        var dragStartHeightPx = 0
        resizeHandle.setOnTouchListener { handle, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeDragPointerId = event.getPointerId(0)
                    dragStartRawY = pointerRawY(handle, event, 0)
                    dragStartHeightPx = keyboardHeight.height(
                        currentHeightBounds(currentMinimumInputHeightPx()),
                    )
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = event.findPointerIndex(activeDragPointerId)
                    if (pointerIndex < 0) {
                        activeDragPointerId = MotionEvent.INVALID_POINTER_ID
                        return@setOnTouchListener true
                    }
                    val currentRawY = pointerRawY(handle, event, pointerIndex)
                    resizeKeyboardByDrag(dragStartHeightPx, (currentRawY - dragStartRawY).toInt())
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activeDragPointerId) {
                        val replacementIndex = (0 until event.pointerCount)
                            .firstOrNull { it != event.actionIndex }
                        if (replacementIndex == null) {
                            activeDragPointerId = MotionEvent.INVALID_POINTER_ID
                        } else {
                            activeDragPointerId = event.getPointerId(replacementIndex)
                            dragStartRawY = pointerRawY(handle, event, replacementIndex)
                            dragStartHeightPx = keyboardHeight.height(
                                currentHeightBounds(currentMinimumInputHeightPx()),
                            )
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeDragPointerId = MotionEvent.INVALID_POINTER_ID
                    true
                }
                else -> false
            }
        }
        val frame = FrameLayout(this).apply {
            contentDescription = getString(R.string.camera_preview_description)
            clipToOutline = true
        }
        // Keep the live camera surface above all editing controls; height changes only resize this frame.
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
            setOnClickListener { toggleScanMode(this) }
        }
        statusRow.addView(modeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            setMargins(0, 0, dp(4), 0)
        })
        statusRow.addView(aboutButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        fun modeToggle(label: String, description: String, action: () -> Unit) = Button(this).apply {
            text = label
            contentDescription = description
            minHeight = dp(44)
            minWidth = dp(76)
            isAllCaps = false
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { action() }
        }
        val modeControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previewButton = modeToggle(getString(R.string.preview_toggle), getString(R.string.preview_accessibility)) { togglePreview() }
        val focusButton = modeToggle(getString(R.string.focus_toggle), getString(R.string.focus_accessibility)) { toggleFocus() }
        val singleButton = modeToggle(getString(R.string.single_scan_toggle), getString(R.string.single_scan_accessibility)) { toggleSingleScan() }
        listOf(previewButton, focusButton, singleButton).forEach { button ->
            modeControls.addView(button, LinearLayout.LayoutParams(dp(96), dp(44)).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
        val candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            contentDescription = getString(R.string.preview_toggle)
        }
        val candidatesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidates, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val modeControlsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(modeControls, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        }

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

        val focusRegion = View(this).apply {
            contentDescription = getString(R.string.focus_accessibility)
            background = GradientDrawable().apply {
                setColor(0x120B777C)
                setStroke(dp(2), ContextCompat.getColor(this@BarcodeInputService, R.color.keyboard_text_secondary))
            }
            visibility = View.GONE
            isClickable = true
        }
        var regionDragX = 0f
        var regionDragY = 0f
        var regionStartX = 0f
        var regionStartY = 0f
        var regionMoved = false
        focusRegion.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    regionDragX = event.rawX; regionDragY = event.rawY
                    regionStartX = event.rawX; regionStartY = event.rawY
                    regionMoved = false
                    (view.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = view.left
                        lp.topMargin = view.top
                        view.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - regionStartX) > touchSlop ||
                        kotlin.math.abs(event.rawY - regionStartY) > touchSlop) regionMoved = true
                    val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener true
                    val clamped = FocusRegionGeometry.clampSquare(
                        lp.leftMargin + (event.rawX - regionDragX),
                        lp.topMargin + (event.rawY - regionDragY),
                        view.width.toFloat(), frame.width.toFloat(), frame.height.toFloat(),
                    )
                    lp.leftMargin = clamped.left.toInt()
                    lp.topMargin = clamped.top.toInt()
                    regionDragX = event.rawX; regionDragY = event.rawY
                    view.layoutParams = lp
                    // Publish the selected bounds and invalidate old frames before another
                    // analyzer callback can be accepted. The view's eventual layout pass is a no-op.
                    updateFocusSquare(view, preview, lp.leftMargin, lp.topMargin, view.width, view.height)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!regionMoved) {
                        requestFocusAt(preview, view.left + event.x, view.top + event.y)
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        frame.addView(focusRegion, FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER))
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val regionChanged = layoutFocusSquare(focusRegion, preview)
            previewOutputTransform = null
            previewOutputTransformSize = null
            preview.post { refreshPreviewOutputTransform(preview, cameraRequestId) }
            if (focusEnabled && !regionChanged) {
                settingsCallbackGeneration.advance()
                clearPendingResults()
                clearPreviewCandidates()
            }
        }

        val torchControl = ImageButton(this).apply {
            setImageResource(R.drawable.ic_torch)
            contentDescription = getString(R.string.torch_turn_on)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
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
        val backspaceButton = toolbarButton(getString(R.string.backspace_symbol), getString(R.string.backspace)) {
            if (suppressBackspaceClick) {
                suppressBackspaceClick = false
            } else {
                val connection = currentInputConnection
                if (connection == null) {
                    setStatus(R.string.editing_unavailable)
                    refreshUndoButton()
                } else {
                    performBackspace(connection, currentSessionId, refreshUndo = true)
                }
            }
        }
        installBackspaceRepeat(backspaceButton)
        val editingButtons = listOf(
            undo,
            toolbarButton(getString(R.string.left_arrow), getString(R.string.move_cursor_left)) {
                editCurrentField { connection, _ -> inputEditor.moveCursorLeft(connection) }
            },
            toolbarButton(getString(R.string.right_arrow), getString(R.string.move_cursor_right)) {
                editCurrentField { connection, _ -> inputEditor.moveCursorRight(connection) }
            },
            backspaceButton,
            toolbarButton(getString(R.string.tab_short), getString(R.string.next_field)) {
                requestNextField()
            },
            toolbarButton(getString(R.string.enter_short), getString(R.string.enter_action)) {
                editCurrentField { connection, editorInfo ->
                    inputEditor.enter(connection, editorInfo)
                }
            },
        )
        val spaceButton = toolbarButton(getString(R.string.space_key), getString(R.string.insert_space)) {
            editCurrentField { connection, _ ->
                inputEditor.insertSpace(connection, currentSessionId, canTrackUndo(currentInputEditorInfo))
            }
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
                stopBackspaceRepeat()
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
            visibility = View.VISIBLE
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
        val frameHeight = resources.getDimensionPixelSize(R.dimen.scanner_preview_min_height)
        root.addView(resizeHandle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, frameHeight))
        controls.addView(statusRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        controls.addView(modeControlsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
        controls.addView(candidatesScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
            // InputMethodService configures its system-owned window to fit top/side system
            // insets while excluding the bottom side. Only reserve the bottom safe inset here;
            // applying top/side values again would double-count the framework fitting.
            val navBottom = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val cutoutBottom = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout()).bottom
            val left = baseLeft
            val top = baseTop
            val right = baseRight
            val bottom = baseBottom + maxOf(navBottom, cutoutBottom)
            if (view.paddingLeft != left || view.paddingTop != top ||
                view.paddingRight != right || view.paddingBottom != bottom
            ) {
                view.setPadding(left, top, right, bottom)
            }
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

        val initialContentWidth = (
            root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        ) - root.paddingLeft - root.paddingRight
        val initialAvailableWidth = initialContentWidth.coerceAtLeast(1)
        rebuildToolbarRows(toolbar, editingButtons, spaceButton, initialAvailableWidth, density, toolbarButtonMinWidthDp())
        val initialContentHeights = measureInputViewContentHeights(
            root, resizeHandle, statusRow, modeControlsScroll, candidatesScroll, enableCamera, toolbar, actions, developerSignature,
            initialAvailableWidth,
        )
        measuredInputViewContentHeights = initialContentHeights
        val minimumPreview = minimumScannerFrameHeightPx()
        val initialMinimumHeight = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(
            initialContentHeights,
            minimumPreview,
        )
        measuredMinimumImeHeightPx = initialMinimumHeight
        val initialBounds = currentHeightBounds(initialMinimumHeight)
        val initialInputHeight = if (keyboardHeight.isManuallyResized()) {
            keyboardHeight.height(initialBounds)
        } else {
            keyboardHeight.useMeasuredDefault(initialBounds.minimumPx, initialBounds)
        }
        // setInputView() replaces the returned view's LayoutParams with WRAP_CONTENT. Keep the
        // first intrinsic measurement tall enough until the attached input view gets explicit LP.
        container.minimumHeight = initialInputHeight

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val measuredWidth = (
                root.width - root.paddingLeft - root.paddingRight
            ).coerceAtLeast(1)
            rebuildToolbarRows(toolbar, editingButtons, spaceButton, measuredWidth, density, toolbarButtonMinWidthDp())
            measuredInputViewContentHeights = measureInputViewContentHeights(
                root, resizeHandle, statusRow, modeControlsScroll, candidatesScroll, enableCamera, toolbar, actions, developerSignature, measuredWidth,
            )
            updateInputViewSizing()
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
        previewCandidateRow = candidates
        focusOverlay = focusRegion
        previewToggleButton = previewButton
        focusToggleButton = focusButton
        singleScanToggleButton = singleButton
        preview.visibility = View.VISIBLE
        focusRegion.visibility = if (focusEnabled) View.VISIBLE else View.GONE
        clearPreviewCandidates()
        focusRegion.post {
            layoutFocusSquare(focusRegion, preview)
        }
        torchButton = torchControl
        renderTorchButton()
        this.developerSignature = developerSignature
        permissionButton = enableCamera
        undoButton = undo
        resizeHandleView = resizeHandle
        toolbarButtons = editingButtons + spaceButton
        themedButtons = toolbarButtons + listOf(modeButton, switchButton, hideButton, enableCamera,
            previewButton, focusButton, singleButton)
        applyKeyboardTheme()
        renderExtendedControls()
        refreshUndoButton()
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                // InputMethodService has attached this view with its own WRAP_CONTENT LP by now.
                // Replace that height before the first window measurement so the IME wraps the
                // same explicit height as BarKey's opaque input view.
                updateInputViewSizing()
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        container.post { updateInputViewSizing() }
        return container
    }

    /** Keep the scanner docked and prevent landscape extract/fullscreen mode. */
    override fun onEvaluateFullscreenMode(): Boolean {
        super.onEvaluateFullscreenMode()
        return false
    }

    override fun onConfigureWindow(window: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        // AOSP owns the IME window and configures it MATCH_PARENT x WRAP_CONTENT at Gravity.BOTTOM.
        // The returned input view owns the selected height; its explicit child LP makes the
        // framework's WRAP_CONTENT inputArea and window wrap the same visible content height.
        super.onConfigureWindow(window, false, isCandidatesOnly)
        keyboardContainer?.post { updateInputViewSizing() }
    }

    private fun measureInputViewContentHeights(
        root: LinearLayout,
        resizeHandle: View,
        statusRow: View,
        modeControls: View,
        candidates: View,
        permissionButton: View,
        toolbar: View,
        actions: View,
        signature: View,
        contentWidthPx: Int,
    ): ResponsiveKeyboardLayoutPolicy.InputViewContentHeights {
        fun measuredHeight(view: View): Int {
            if (view.visibility != View.VISIBLE) return 0
            view.measure(
                View.MeasureSpec.makeMeasureSpec(contentWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val margins = (view.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.let { it.topMargin + it.bottomMargin } ?: 0
            return view.measuredHeight + margins
        }

        val permanent = root.paddingTop + root.paddingBottom + measuredHeight(resizeHandle) + measuredHeight(signature)
        val controls = measuredHeight(statusRow) + measuredHeight(modeControls) + measuredHeight(candidates) + measuredHeight(permissionButton) +
            measuredHeight(toolbar) + measuredHeight(actions)
        return ResponsiveKeyboardLayoutPolicy.InputViewContentHeights(permanent, controls)
    }

    private fun updateInputViewSizing() {
        val content = measuredInputViewContentHeights ?: return
        val minimumPreview = minimumScannerFrameHeightPx()
        val minimumHeight = ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(content, minimumPreview)
        measuredMinimumImeHeightPx = minimumHeight
        val bounds = currentHeightBounds(minimumHeight)
        val requestedHeight = if (keyboardHeight.isManuallyResized()) {
            keyboardHeight.height(bounds)
        } else {
            keyboardHeight.useMeasuredDefault(bounds.minimumPx, bounds)
        }
        val plan = ResponsiveKeyboardLayoutPolicy.inputViewLayoutPlan(
            requestedInputViewHeightPx = requestedHeight,
            bounds = bounds,
            content = content,
            minimumPreviewHeightPx = minimumPreview,
            automaticPreviewMaximumPx = maxOf(minimumPreview, resources.getDimensionPixelSize(R.dimen.scanner_preview_max_height)),
            manuallyResized = keyboardHeight.isManuallyResized(),
        )
        keyboardContainer?.let { applyInputViewHeight(it, plan.inputViewHeightPx) }
        cameraFrame?.let { frame ->
            val params = frame.layoutParams
            if (params != null && params.height != plan.previewHeightPx) {
                params.height = plan.previewHeightPx
                frame.layoutParams = params
            }
        }
    }

    private fun applyInputViewHeight(container: FrameLayout, heightPx: Int) {
        // setInputView() has now attached the view and replaced its original LP with WRAP_CONTENT.
        // Drop the temporary intrinsic floor, then set the real height on the attached input view.
        if (container.parent == null) return
        if (container.minimumHeight != 0) container.minimumHeight = 0
        val params = container.layoutParams ?: return
        if (params.height != heightPx) {
            params.height = heightPx
            container.layoutParams = params
        }
    }

    private fun currentHeightBounds(minimumHeightPx: Int): ResponsiveKeyboardLayoutPolicy.HeightBounds {
        val window = getWindow()?.window
        // AOSP fits the top and side IME insets but leaves the bottom edge at the navigation area.
        // The root applies the delivered bottom inset once as content padding; do not subtract it
        // again from the docked window's available height.
        val usable = if (window != null) usableDisplayHeightPx(window) else resources.displayMetrics.heightPixels
        return ResponsiveKeyboardLayoutPolicy.heightBounds(
            usableDisplayHeightPx = usable,
            measuredFixedContentHeightPx = minimumHeightPx - minimumScannerFrameHeightPx(),
            minimumPreviewHeightPx = minimumScannerFrameHeightPx(),
            leaveApplicationVisiblePx = dp(120),
        )
    }

    private fun currentMinimumInputHeightPx(): Int = measuredMinimumImeHeightPx
        ?: measuredInputViewContentHeights?.let {
            ResponsiveKeyboardLayoutPolicy.minimumInputViewHeight(
                it,
                minimumScannerFrameHeightPx(),
            )
        }
        ?: minimumScannerFrameHeightPx()

    private fun minimumScannerFrameHeightPx(): Int = resources.getDimensionPixelSize(R.dimen.scanner_preview_min_height)

    private fun resizeKeyboardByDrag(dragStartHeightPx: Int, totalDragDistanceYpx: Int) {
        val bounds = currentHeightBounds(currentMinimumInputHeightPx())
        val oldHeight = keyboardContainer?.layoutParams?.height?.takeIf { it > 0 } ?: keyboardHeight.height(bounds)
        val resized = keyboardHeight.resizeFromDrag(dragStartHeightPx, totalDragDistanceYpx, bounds)
        if (resized == oldHeight) return
        updateInputViewSizing()
    }

    private fun pointerRawY(view: View, event: MotionEvent, pointerIndex: Int): Float {
        val screenPosition = IntArray(2)
        view.getLocationOnScreen(screenPosition)
        return screenPosition[1] + event.getY(pointerIndex)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        stopBackspaceRepeat()
        super.onStartInput(info, restarting)
        clearPendingResults()
        clearPreviewCandidates()
        inputEditor.clearUndo()
        stopCamera()
        currentSessionId = sessionGate.beginSession()
        activeEditorField = null
        syncActiveEditorField()
        currentSuffix = ScanSettings.suffixFor(this)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardContainer?.post { updateInputViewSizing() }
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
        syncActiveEditorField()
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
        keyboardContainer?.post { updateInputViewSizing() }
        refreshPermissionUi()
        if (!helpVisible && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera(currentSessionId)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopBackspaceRepeat()
        resetHelpPanelForHiddenWindow()
        inputEditor.clearUndo()
        clearPreviewCandidates()
        refreshUndoButton()
        stopCamera()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopBackspaceRepeat()
        stopCamera()
        clearPendingResults()
        inputEditor.clearUndo()
        sessionGate.endSession()
        // Preserve a recognisable field's allowance through an editor handoff while dropping its
        // old InputConnection; onStartInput releases it if Android reports a different field.
        // ID-less editors cannot be matched safely across finished input sessions.
        singleScanGate.finishInput()
        activeEditorField = null
        currentSessionId = 0L
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        stopBackspaceRepeat()
        resetHelpPanelForHiddenWindow()
        inputEditor.clearUndo()
        clearPreviewCandidates()
        refreshUndoButton()
        stopCamera()
        super.onWindowHidden()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyKeyboardTheme()
        updateFullscreenMode()
        keyboardRoot?.requestLayout()
        keyboardContainer?.post { updateInputViewSizing() }
        val rotation = previewView?.display?.rotation
        if (rotation == null) {
            keyboardRoot?.requestLayout()
            return
        }
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
        keyboardRoot?.requestLayout()
        if (activeCamera != null && !helpVisible) {
            clearPendingResults()
            clearPreviewCandidates()
            stopCamera()
            previewView?.post {
                if (isInputViewShown && sessionGate.isCurrent(currentSessionId) && !helpVisible) {
                    startCamera(currentSessionId)
                }
            }
        }
    }

    override fun onDestroy() {
        stopBackspaceRepeat()
        stopCamera()
        clearPendingResults()
        inputEditor.clearUndo()
        sessionGate.endSession()
        singleScanGate.reset()
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
        measuredMinimumImeHeightPx = null
        measuredInputViewContentHeights = null
        resizeHandleView = null
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
        resizeHandleView?.let { handle ->
            (handle as? ViewGroup)?.getChildAt(0)?.let(::updateResizeGripBackground)
        }
        cameraFrame?.setBackgroundResource(R.drawable.keyboard_camera_background)
        statusView?.apply {
            setTextColor(primaryText)
            setBackgroundResource(R.drawable.keyboard_status_background)
        }
        themedButtons.forEach { styleKeyboardButton(it, if (it in toolbarButtons) 13f else 14f) }
        scanModeButton?.let(::styleScanModeButton)
        renderExtendedControls()
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

    private fun toggleScanMode(button: Button) {
        val snapshot = scanModes.toggle()
        clearPendingResults()
        clearPreviewCandidates()
        renderScanModeButton(button, snapshot)
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

    private fun updateResizeGripBackground(view: View) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(2).toFloat()
            setColor(ContextCompat.getColor(this@BarcodeInputService, R.color.keyboard_text_secondary))
            alpha = 100
        }
    }

    @Suppress("DEPRECATION")
    private fun usableDisplayHeightPx(window: Window): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = window.windowManager.currentWindowMetrics
            val insetTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            val insets = metrics.windowInsets.getInsets(insetTypes)
            // InputMethodService's IME window fits the top/side insets but intentionally does not
            // fit away its bottom edge. Keep the nav-bar area in the window bounds; root padding
            // below accounts for the nav inset once so content stays above the system controls.
            return ResponsiveKeyboardLayoutPolicy.imeAvailableHeight(metrics.bounds.height(), insets.top)
        }
        val metrics = DisplayMetrics()
        window.windowManager.defaultDisplay.getRealMetrics(metrics)
        val compatInsets = ViewCompat.getRootWindowInsets(window.decorView)
        val safeInsets = compatInsets?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val topInset = if (safeInsets != null) safeInsets.top else {
            @Suppress("DEPRECATION")
            val legacyInsets = window.decorView.rootWindowInsets
            if (legacyInsets != null) legacyInsets.stableInsetTop else 0
        }
        return ResponsiveKeyboardLayoutPolicy.imeAvailableHeight(metrics.heightPixels, topInset)
    }

    private fun openHelpPanel() {
        if (helpVisible) return
        stopBackspaceRepeat()
        helpVisible = true
        clearPendingResults()
        clearPreviewCandidates()
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

    private fun togglePreview() {
        previewEnabled = !previewEnabled
        previewCandidateState.clear()
        renderedPreviewValues = emptyList()
        previewCandidateRow?.removeAllViews()
        clearPendingResults()
        settingsCallbackGeneration.advance()
        setStatus(if (previewEnabled) R.string.preview_empty else R.string.scanner_ready)
        renderExtendedControls()
    }

    private fun toggleFocus() {
        focusEnabled = !focusEnabled
        clearPreviewCandidates()
        clearPendingResults()
        settingsCallbackGeneration.advance()
        if (focusEnabled) {
            previewOutputTransform = null
            previewOutputTransformSize = null
            previewView?.let { preview -> preview.post { refreshPreviewOutputTransform(preview, cameraRequestId) } }
        }
        focusOverlay?.visibility = if (focusEnabled) View.VISIBLE else View.GONE
        if (focusEnabled) focusOverlay?.post { focusOverlay?.let { updateFocusSquare(it, previewView) } }
        renderExtendedControls()
    }

    private fun toggleSingleScan() {
        singleScanEnabled = !singleScanEnabled
        if (!singleScanEnabled) singleScanGate.reset() else currentFieldKey()?.let(singleScanGate::moveTo)
        settingsCallbackGeneration.advance()
        clearPendingResults()
        clearPreviewCandidates()
        renderExtendedControls()
    }

    private fun renderExtendedControls() {
        fun render(button: Button?, enabled: Boolean, selected: Boolean) {
            button ?: return
            button.isActivated = selected
            button.setBackgroundResource(if (selected) R.drawable.scan_mode_button_background else R.drawable.keyboard_button_background)
            button.backgroundTintList = null
            button.setTextColor(ContextCompat.getColorStateList(this, R.color.keyboard_button_text))
            button.contentDescription = getString(when (button) {
                previewToggleButton -> R.string.preview_accessibility
                focusToggleButton -> R.string.focus_accessibility
                singleScanToggleButton -> if (singleScanGate.isConsumed()) R.string.single_scan_used else R.string.single_scan_accessibility
                else -> R.string.single_scan_accessibility
            })
            button.isEnabled = enabled
        }
        render(previewToggleButton, true, previewEnabled)
        render(focusToggleButton, true, focusEnabled)
        render(singleScanToggleButton, true, singleScanEnabled)
    }

    private fun updateFocusSquare(
        view: View,
        preview: PreviewView?,
        left: Int = view.left,
        top: Int = view.top,
        width: Int = view.width,
        height: Int = view.height,
    ): Boolean {
        val p = preview ?: return false
        if (p.width <= 0 || p.height <= 0 || width <= 0 || height <= 0) return false
        val next = FocusRegionGeometry.RectF(
            left / p.width.toFloat(), top / p.height.toFloat(),
            (left + width) / p.width.toFloat(), (top + height) / p.height.toFloat(),
        )
        val changed = focusRegionState.update(next, focusEnabled)
        if (changed && focusEnabled) {
            clearPendingResults()
            // FocusRegionState already cleared the candidate values and invalidated selection tokens.
            clearPreviewCandidateViews()
        }
        return changed
    }

    private fun layoutFocusSquare(view: View, preview: PreviewView): Boolean {
        if (preview.width <= 0 || preview.height <= 0) return false
        val side = minOf(dp(160), preview.width, preview.height).coerceAtLeast(1)
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return false
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = side
        lp.height = side
        val stored = focusRegionState.snapshot().region
        val hasStoredPosition = stored.right > stored.left && stored.bottom > stored.top
        val left = if (hasStoredPosition) (stored.left * preview.width).toInt() else (preview.width - side) / 2
        val top = if (hasStoredPosition) (stored.top * preview.height).toInt() else (preview.height - side) / 2
        lp.leftMargin = left.coerceIn(0, (preview.width - side).coerceAtLeast(0))
        lp.topMargin = top.coerceIn(0, (preview.height - side).coerceAtLeast(0))
        view.layoutParams = lp
        return updateFocusSquare(view, preview, lp.leftMargin, lp.topMargin, side, side)
    }

    private fun mappedScanCrop(image: androidx.camera.core.ImageProxy, expectedSettingsGeneration: Long): Rect? {
        if (!focusEnabled || !settingsCallbackGeneration.accepts(expectedSettingsGeneration)) return null
        val regionSnapshot = focusRegionState.snapshotFor(expectedSettingsGeneration) ?: return null
        val preview = previewView ?: return null
        val previewTransform = previewOutputTransform ?: return null
        if (previewOutputTransformSize != (preview.width to preview.height)) return null
        val square = regionSnapshot.region
        if (preview.width <= 0 || preview.height <= 0 || square.right <= square.left || square.bottom <= square.top) return null
        val mapped = android.graphics.RectF(
            square.left * preview.width, square.top * preview.height,
            square.right * preview.width, square.bottom * preview.height,
        )
        val crop = image.cropRect
        val validCrop = android.graphics.Rect(crop).apply { intersect(0, 0, image.width, image.height) }
        if (validCrop.isEmpty) return null
        val mappedCrop = FocusRegionGeometry.mapPreviewRectToImageCrop(
            previewRect = FocusRegionGeometry.RectF(mapped.left, mapped.top, mapped.right, mapped.bottom),
            crop = FocusRegionGeometry.ImageRect(validCrop.left, validCrop.top, validCrop.right, validCrop.bottom),
        ) { cropLocalRect ->
            val cameraXRect = android.graphics.RectF(
                cropLocalRect.left, cropLocalRect.top, cropLocalRect.right, cropLocalRect.bottom,
            )
            // setUsingCropRect(true) makes CameraX's destination crop-local: its origin is
            // (0,0), even when ImageProxy.cropRect has non-zero buffer offsets. Rotation,
            // ViewPort and PreviewView scaling are encoded by the two current OutputTransforms.
            CoordinateTransform(previewTransform, imageProxyTransformFactory.getOutputTransform(image))
                .mapRect(cameraXRect)
            FocusRegionGeometry.RectF(
                cameraXRect.left, cameraXRect.top, cameraXRect.right, cameraXRect.bottom,
            )
        } ?: return null
        if (!focusEnabled || !settingsCallbackGeneration.accepts(expectedSettingsGeneration) ||
            focusRegionState.snapshotFor(expectedSettingsGeneration)?.region != regionSnapshot.region
        ) return null
        return Rect(mappedCrop.left, mappedCrop.top, mappedCrop.right, mappedCrop.bottom)
    }

    private fun currentFieldKey(): EditorFieldKey? {
        val connection = currentInputConnection ?: return null
        val info = currentInputEditorInfo
        return EditorFieldKey(connection, info?.packageName, info?.fieldId ?: 0)
    }

    private fun syncActiveEditorField() {
        val next = currentFieldKey() ?: return
        val previous = activeEditorField
        val changed = previous?.identifiesSameField(next) != true
        activeEditorField = next
        if (changed) {
            settingsCallbackGeneration.advance()
            clearPendingResults()
            clearPreviewCandidates()
        }
        if (!singleScanEnabled) return
        val wasConsumed = singleScanGate.isConsumed()
        singleScanGate.moveTo(next)
        if (wasConsumed && !singleScanGate.isConsumed()) {
            setStatus(R.string.scanner_ready)
            renderExtendedControls()
        }
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
                            previewEnabled = { previewEnabled },
                            focusEnabled = { focusEnabled },
                            scanCrop = ::mappedScanCrop,
                            settingsGeneration = { extendedGeneration },
                            shouldDecode = {
                                SingleScanDecodePolicy.shouldDecode(singleScanEnabled, singleScanGate.isConsumed())
                            },
                            onDecoded = ::onDecoded,
                            onFailure = ::onDecoderFailure,
                        ),
                    )

                    val cameraPreview = Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                        .also { it.setSurfaceProvider(preview.surfaceProvider) }

                    // The shared ViewPort makes the analysis crop refer to the same camera
                    // sensor rectangle that PreviewView displays, despite different stream sizes.
                    if (preview.width <= 0 || preview.height <= 0) {
                        stopCamera()
                        preview.post {
                            if (sessionGate.isCurrent(sessionId) && isInputViewShown) {
                                startCamera(sessionId)
                            }
                        }
                        return@addListener
                    }
                    // ImageAnalysis requests 1280×720. Keep a matching viewport while the user
                    // resizes the docked preview; PreviewView's output transform accounts for
                    // its changing FILL_CENTER crop without restarting the camera.
                    val viewPort = ViewPort.Builder(Rational(16, 9), rotation)
                        .setScaleType(ViewPort.FILL_CENTER)
                        .build()
                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(cameraPreview)
                        .addUseCase(imageAnalysis)
                        .build()

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        cameraLifecycle,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        useCaseGroup,
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
                        refreshPreviewOutputTransform(preview, requestId)
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
        previewView?.let { preview -> transformRefreshRunnable?.let(preview::removeCallbacks) }
        transformRefreshRunnable = null
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
        previewOutputTransform = null
        previewOutputTransformSize = null
        clearPendingResults()
        clearPreviewCandidates()
        cameraLifecycle.pause()
    }

    private fun refreshPreviewOutputTransform(preview: PreviewView, requestId: Long) {
        if (requestId != cameraRequestId || helpVisible) return
        val transform = preview.outputTransform
        if (transform != null) {
            previewOutputTransform = transform
            previewOutputTransformSize = preview.width to preview.height
            transformRefreshRunnable = null
            return
        }
        val retry = Runnable { refreshPreviewOutputTransform(preview, requestId) }
        transformRefreshRunnable = retry
        preview.postDelayed(retry, 50L)
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

    private fun clearPreviewCandidates() {
        previewCandidateState.clear()
        clearPreviewCandidateViews()
    }

    private fun clearPreviewCandidateViews() {
        renderedPreviewValues = emptyList()
        previewCandidateRow?.removeAllViews()
        previewCandidateRow?.visibility = View.GONE
    }

    private fun onDecoded(sessionId: Long, cameraGeneration: Long, modeGeneration: Long, settingsGeneration: Long, values: List<String>) {
        if (helpVisible || sessionId != currentSessionId || cameraGeneration != cameraRequestId ||
            !scanModes.isCurrent(modeGeneration) || settingsGeneration != extendedGeneration
        ) return
        val callbackToken = resultCallbackToken
        mainHandler.postAtTime(resultCallback@{
            if (helpVisible || !sessionGate.isCurrent(sessionId) || !isInputViewShown ||
                cameraGeneration != cameraRequestId || !scanModes.isCurrent(modeGeneration) ||
                settingsGeneration != extendedGeneration
            ) return@resultCallback
            syncActiveEditorField()
            if (settingsGeneration != extendedGeneration) return@resultCallback
            if (!PreviewDeliveryPolicy.mayInsertAutomatically(previewEnabled)) {
                val current = previewCandidateState.update(values, SystemClock.elapsedRealtime())
                showPreviewCandidates(current, sessionId, cameraGeneration, modeGeneration, settingsGeneration)
                return@resultCallback
            }
            val value = values.firstOrNull() ?: return@resultCallback
            if (singleScanEnabled) {
                currentFieldKey()?.let(singleScanGate::moveTo)
                if (singleScanGate.isConsumed()) {
                    setStatus(R.string.single_scan_used)
                    renderExtendedControls()
                    return@resultCallback
                }
            }
            insertDecodedValue(value, sessionId, cameraGeneration, modeGeneration, settingsGeneration)
        }, callbackToken, SystemClock.uptimeMillis())
    }

    private fun insertDecodedValue(value: String, sessionId: Long, cameraGeneration: Long, modeGeneration: Long, settingsGeneration: Long) {
        syncActiveEditorField()
        if (helpVisible || !sessionGate.isCurrent(sessionId) || cameraGeneration != cameraRequestId ||
            !scanModes.isCurrent(modeGeneration) || settingsGeneration != extendedGeneration
        ) return
        if (singleScanEnabled) {
            currentFieldKey()?.let(singleScanGate::moveTo)
            if (singleScanGate.isConsumed()) {
                setStatus(R.string.single_scan_used)
                renderExtendedControls()
                return
            }
        }
        val inputConnection: InputConnection = currentInputConnection ?: return
        if (!sessionGate.shouldAccept(sessionId, value, SystemClock.elapsedRealtime())) return

        val committed = try {
            inputEditor.commitScan(
                connection = AndroidEditorConnection(inputConnection),
                sessionId = sessionId,
                value = value,
                recordUndo = canTrackUndo(currentInputEditorInfo),
            )
        } catch (error: RuntimeException) {
            Log.w(TAG, "Input field rejected barcode insertion (${error.javaClass.simpleName})")
            false
        }
        if (!committed) {
            setStatus(R.string.scan_delivery_failed)
            return
        }
        if (singleScanEnabled) singleScanGate.consume()
        currentSuffix.keyCode?.let { keyCode ->
            inputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            inputConnection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        }
        refreshUndoButton()
        setStatus(if (singleScanEnabled) R.string.single_scan_used else R.string.scan_inserted)
        renderExtendedControls()
    }

    private fun showPreviewCandidates(
        values: List<String>, sessionId: Long, cameraGeneration: Long, modeGeneration: Long, settingsGeneration: Long,
    ) {
        val row = previewCandidateRow ?: return
        if (values == renderedPreviewValues) return
        val selectionToken = previewCandidateState.selectionToken()
        renderedPreviewValues = values
        row.removeAllViews()
        if (!previewEnabled || values.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        values.forEach { value ->
            val suggestion = Button(this).apply {
                text = if (value.length > 48) value.take(48) + "…" else value
                contentDescription = value
                maxLines = 2
                maxWidth = dp(220)
                ellipsize = android.text.TextUtils.TruncateAt.END
                minHeight = (44 * resources.displayMetrics.density).toInt()
                minWidth = (72 * resources.displayMetrics.density).toInt()
                isAllCaps = false
                setOnClickListener {
                    syncActiveEditorField()
                    if (!PreviewDeliveryPolicy.maySelect(
                            previewEnabled, previewCandidateState, value, SystemClock.elapsedRealtime(), selectionToken,
                        )
                    ) return@setOnClickListener
                    insertDecodedValue(value, sessionId, cameraGeneration, modeGeneration, settingsGeneration)
                }
            }
            styleKeyboardButton(suggestion, 13f)
            row.addView(suggestion, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, (4 * resources.displayMetrics.density).toInt(), 0)
            })
        }
    }

    private fun onDecoderFailure(sessionId: Long, cameraGeneration: Long, modeGeneration: Long, settingsGeneration: Long) {
        mainHandler.post {
            if (!helpVisible && sessionGate.isCurrent(sessionId) && cameraGeneration == cameraRequestId &&
                scanModes.isCurrent(modeGeneration) && settingsGeneration == extendedGeneration
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
        stopBackspaceRepeat()
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

    private fun installBackspaceRepeat(button: Button) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val connection = currentInputConnection ?: return@setOnTouchListener false
                    val sessionId = currentSessionId
                    view.isPressed = true
                    var refreshUndo = true
                    backspaceRepeat.press {
                        val result = if (helpVisible || !isInputViewShown || currentSessionId != sessionId ||
                            currentInputConnection !== connection
                        ) BackspaceResult.FAILED else performBackspace(connection, sessionId, refreshUndo)
                        refreshUndo = false
                        result.shouldRepeat
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val inside = event.x >= 0f && event.y >= 0f && event.x < view.width && event.y < view.height
                    if (!inside) {
                        view.isPressed = false
                        stopBackspaceRepeat()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    backspaceRepeat.stop()
                    suppressBackspaceClick = true
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                    view.isPressed = false
                    stopBackspaceRepeat()
                    true
                }
                else -> true
            }
        }
    }

    private fun performBackspace(
        connection: InputConnection,
        sessionId: Long,
        refreshUndo: Boolean,
    ): BackspaceResult {
        if (helpVisible || !isInputViewShown || currentSessionId != sessionId || currentInputConnection !== connection) {
            return BackspaceResult.FAILED
        }
        val undoWasRecorded = inputEditor.hasUndoRecord()
        if (undoWasRecorded) inputEditor.clearUndo()
        return try {
            inputEditor.backspace(AndroidEditorConnection(connection)).also { result ->
                if (result == BackspaceResult.FAILED) setStatus(R.string.editing_unavailable)
                if (refreshUndo || undoWasRecorded) refreshUndoButton()
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Input field rejected Backspace (${error.javaClass.simpleName})")
            setStatus(R.string.editing_unavailable)
            if (refreshUndo || undoWasRecorded) refreshUndoButton()
            BackspaceResult.FAILED
        }
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeat.stop()
    }

    private fun requestNextField() {
        stopBackspaceRepeat()
        val editorInfo = currentInputEditorInfo
        if (EditorCommandPolicy.nextField(editorInfo) == NextFieldCommand.UNSUPPORTED) return
        editCurrentField(clearUndo = false) { connection, currentInfo ->
            inputEditor.nextField(connection, currentInfo)
        }
    }

    private fun editCurrentField(
        clearUndo: Boolean = true,
        edit: (EditorConnection, EditorInfo?) -> Boolean,
    ) {
        val connection = currentInputConnection
        if (connection == null) {
            setStatus(R.string.editing_unavailable)
            refreshUndoButton()
            return
        }
        if (clearUndo) inputEditor.clearUndo()
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
    }
}
