package net.hernow.barkey

import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat as JavaBarcodeFormat
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import zxingcpp.BarcodeReader

internal sealed interface DecodeOutcome {
    data class Value(val text: String) : DecodeOutcome
    data object NotFound : DecodeOutcome
    data object Failure : DecodeOutcome
}

internal fun interface FrameValueDecoder<F> {
    fun decode(frame: F): String?
}

internal fun interface BarcodeFrameDecoder {
    fun decode(frame: ImageProxy, mode: ScanMode): DecodeOutcome
}

internal fun needsJavaTextFallback(rawBytes: ByteArray, text: String?): Boolean =
    rawBytes.any { it == 0.toByte() } || text.isNullOrEmpty()

internal fun validDecodedText(text: String?): String? = text?.takeIf { it.isNotEmpty() }

/** Selects Java only when native use is unavailable or fails, never after an ordinary miss. */
internal class DecoderRouter<F>(
    private val nativeFrameSupported: (F) -> Boolean,
    private val nativeFactory: (ScanMode) -> FrameValueDecoder<F>,
    private val javaFactory: (ScanMode) -> FrameValueDecoder<F>,
) {
    private val nativeDecoders = mutableMapOf<ScanMode, FrameValueDecoder<F>>()
    private val javaDecoders = mutableMapOf<ScanMode, FrameValueDecoder<F>>()
    private var nativeUnavailable = false
    var nativeInitializationAttempts: Int = 0
        private set

    fun decode(frame: F, mode: ScanMode): DecodeOutcome {
        if (!nativeUnavailable && nativeFrameSupported(frame)) {
            val nativeDecoder = nativeDecoderOrDisable(mode)
            if (nativeDecoder != null) {
                try {
                    return outcome(nativeDecoder.decode(frame))
                } catch (_: NativeFallbackForFrame) {
                    return decodeJava(frame, mode)
                } catch (error: Throwable) {
                    if (error is VirtualMachineError || error is ThreadDeath) throw error
                    disableNative()
                    return decodeJava(frame, mode)
                }
            }
        }
        return decodeJava(frame, mode)
    }

    private fun nativeDecoderOrDisable(mode: ScanMode): FrameValueDecoder<F>? =
        nativeDecoders[mode] ?: try {
            nativeInitializationAttempts += 1
            nativeFactory(mode).also { nativeDecoders[mode] = it }
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            disableNative()
            null
        }

    private fun decodeJava(frame: F, mode: ScanMode): DecodeOutcome = try {
        val javaDecoder = javaDecoders[mode] ?: javaFactory(mode).also { javaDecoders[mode] = it }
        outcome(javaDecoder.decode(frame))
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        DecodeOutcome.Failure
    }

    private fun disableNative() {
        nativeUnavailable = true
        nativeDecoders.clear()
    }

    private fun outcome(text: String?): DecodeOutcome =
        validDecodedText(text)?.let(DecodeOutcome::Value) ?: DecodeOutcome.NotFound
}

/** Signals a result that needs Java text conversion without disabling native decoding. */
private class NativeFallbackForFrame : RuntimeException()

/** Native ZXing-C++ first, with Java ZXing retained for unavailable or incompatible frames. */
internal class HybridBarcodeDecoder : BarcodeFrameDecoder {
    private val router = DecoderRouter(
        nativeFrameSupported = ::nativeCanRead,
        nativeFactory = ::createNativeDecoder,
        javaFactory = ::createJavaDecoder,
    )

    override fun decode(frame: ImageProxy, mode: ScanMode): DecodeOutcome = router.decode(frame, mode)

    private fun createNativeDecoder(mode: ScanMode): FrameValueDecoder<ImageProxy> {
        val reader = BarcodeReader(BarcodeDecoderProfiles.nativeOptions(mode))
        return FrameValueDecoder { image ->
            val result = reader.read(image).firstOrNull { candidate ->
                candidate.format != BarcodeReader.Format.NONE &&
                    candidate.error == null &&
                    !candidate.bytes.isNullOrEmpty()
            } ?: return@FrameValueDecoder null

            val rawBytes = result.bytes ?: return@FrameValueDecoder null
            // The wrapper marshals text with NewStringUTF; a raw NUL would be truncated there.
            // Use the Java reader for that frame to preserve its decoded value.
            if (needsJavaTextFallback(rawBytes, result.text)) {
                throw NativeFallbackForFrame()
            }
            result.text
        }
    }

    private fun createJavaDecoder(mode: ScanMode): FrameValueDecoder<ImageProxy> {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to BarcodeDecoderProfiles.javaFormats,
                    DecodeHintType.TRY_HARDER to BarcodeDecoderProfiles.javaTryHarder(mode),
                ),
            )
        }
        return FrameValueDecoder { image ->
            try {
                val plane = image.planes.firstOrNull() ?: return@FrameValueDecoder null
                val crop = image.cropRect
                val source = PlanarYUVLuminanceSource(
                    copyLumaPlane(plane.buffer, plane.rowStride, plane.pixelStride, crop),
                    crop.width(),
                    crop.height(),
                    0,
                    0,
                    crop.width(),
                    crop.height(),
                    false,
                )
                val oriented = rotate(source, image.imageInfo.rotationDegrees)
                val text = try {
                    reader.decodeWithState(BinaryBitmap(HybridBinarizer(oriented))).text
                } catch (_: NotFoundException) {
                    null
                } catch (_: ChecksumException) {
                    null
                } catch (_: FormatException) {
                    null
                }
                text
            } finally {
                reader.reset()
            }
        }
    }

    private fun nativeCanRead(image: ImageProxy): Boolean {
        return try {
            if (image.format !in NATIVE_YUV_FORMATS) return false
            val plane = image.planes.firstOrNull() ?: return false
            val crop = image.cropRect
            val buffer = plane.buffer
            if (plane.pixelStride != 1 || !buffer.isDirect || buffer.position() != 0) return false
            if (plane.rowStride < crop.right || crop.left < 0 || crop.top < 0 ||
                crop.right > image.width || crop.bottom > image.height || crop.width() <= 0 || crop.height() <= 0
            ) return false
            val requiredBytes = (crop.bottom - 1).toLong() * plane.rowStride + crop.right
            requiredBytes <= buffer.capacity()
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun copyLumaPlane(
        source: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        crop: Rect,
    ): ByteArray {
        require(rowStride > 0 && pixelStride > 0 && crop.width() > 0 && crop.height() > 0)
        val buffer = source.duplicate()
        val base = buffer.position()
        val width = crop.width()
        val height = crop.height()
        val lastIndex = base + (crop.bottom - 1) * rowStride + (crop.right - 1) * pixelStride
        require(lastIndex < buffer.limit())
        val luma = ByteArray(width * height)

        for (row in 0 until height) {
            val sourceRow = base + (crop.top + row) * rowStride + crop.left * pixelStride
            val targetRow = row * width
            if (pixelStride == 1) {
                buffer.position(sourceRow)
                buffer.get(luma, targetRow, width)
            } else {
                for (column in 0 until width) {
                    luma[targetRow + column] = buffer.get(sourceRow + column * pixelStride)
                }
            }
        }
        return luma
    }

    private fun rotate(source: PlanarYUVLuminanceSource, rotationDegrees: Int) = when (rotationDegrees) {
        90 -> source.rotateCounterClockwise().rotateCounterClockwise().rotateCounterClockwise()
        180 -> source.rotateCounterClockwise().rotateCounterClockwise()
        270 -> source.rotateCounterClockwise()
        else -> source
    }

    private companion object {
        val NATIVE_YUV_FORMATS = setOf(
            ImageFormat.YUV_420_888,
            ImageFormat.YUV_422_888,
            ImageFormat.YUV_444_888,
        )
    }
}

internal object BarcodeDecoderProfiles {
    val nativeFormats: Set<BarcodeReader.Format> = setOf(
        BarcodeReader.Format.QR_CODE,
        BarcodeReader.Format.DATA_MATRIX,
        BarcodeReader.Format.AZTEC,
        BarcodeReader.Format.PDF_417,
        BarcodeReader.Format.UPC_A,
        BarcodeReader.Format.UPC_E,
        BarcodeReader.Format.EAN_8,
        BarcodeReader.Format.EAN_13,
        BarcodeReader.Format.CODE_39,
        BarcodeReader.Format.CODE_93,
        BarcodeReader.Format.CODE_128,
        BarcodeReader.Format.ITF,
        BarcodeReader.Format.CODABAR,
        BarcodeReader.Format.MAXI_CODE,
        BarcodeReader.Format.DATA_BAR,
        BarcodeReader.Format.DATA_BAR_OMNI,
        BarcodeReader.Format.DATA_BAR_STK,
        BarcodeReader.Format.DATA_BAR_STK_OMNI,
        BarcodeReader.Format.DATA_BAR_LTD,
        BarcodeReader.Format.DATA_BAR_EXP,
        BarcodeReader.Format.DATA_BAR_EXP_STK,
        BarcodeReader.Format.MICRO_QR_CODE,
        BarcodeReader.Format.RMQR_CODE,
        BarcodeReader.Format.MICRO_PDF_417,
    )

    val javaFormats: List<JavaBarcodeFormat> = listOf(
        JavaBarcodeFormat.QR_CODE,
        JavaBarcodeFormat.DATA_MATRIX,
        JavaBarcodeFormat.AZTEC,
        JavaBarcodeFormat.PDF_417,
        JavaBarcodeFormat.UPC_A,
        JavaBarcodeFormat.UPC_E,
        JavaBarcodeFormat.EAN_8,
        JavaBarcodeFormat.EAN_13,
        JavaBarcodeFormat.CODE_39,
        JavaBarcodeFormat.CODE_93,
        JavaBarcodeFormat.CODE_128,
        JavaBarcodeFormat.ITF,
        JavaBarcodeFormat.CODABAR,
        JavaBarcodeFormat.RSS_14,
        JavaBarcodeFormat.RSS_EXPANDED,
        JavaBarcodeFormat.MAXICODE,
    )

    fun nativeOptions(mode: ScanMode) = BarcodeReader.Options(
        formats = nativeFormats,
        tryHarder = mode == ScanMode.ACCURATE,
        tryRotate = true,
        tryInvert = mode == ScanMode.ACCURATE,
        tryDownscale = mode == ScanMode.ACCURATE,
        tryDenoise = false,
        binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE,
        maxNumberOfSymbols = 1,
        // PLAIN keeps decoded data and GS1 separators rather than HRI presentation markup.
        textMode = BarcodeReader.TextMode.PLAIN,
    )

    fun javaTryHarder(mode: ScanMode): Boolean = mode == ScanMode.ACCURATE
}
