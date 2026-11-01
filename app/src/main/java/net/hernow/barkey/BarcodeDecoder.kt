package net.hernow.barkey

import android.graphics.ImageFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.BarcodeFormat as JavaBarcodeFormat
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.common.HybridBinarizer
import zxingcpp.BarcodeReader

/** Result of one frame decode, including every value found in Preview mode. */
internal sealed interface DecodeOutcome {
    data class Value(val text: String) : DecodeOutcome
    data class Values(val texts: List<String>) : DecodeOutcome
    data object NotFound : DecodeOutcome
    data object Failure : DecodeOutcome
}

/** Adapts a decoder library to the frame-based fallback router. */
internal fun interface FrameValueDecoder<F> {
    fun decode(frame: F): String?
}

/** Supports normal, multi-code and cropped frame decoding. */
internal fun interface BarcodeFrameDecoder {
    fun decode(frame: ImageProxy, mode: ScanMode): DecodeOutcome
    fun decodeCandidates(frame: ImageProxy, mode: ScanMode, crop: Rect? = null): DecodeOutcome = decode(frame, mode)
    fun decodeFocused(frame: ImageProxy, mode: ScanMode, crop: Rect): DecodeOutcome = decodeCandidates(frame, mode, crop)
}

/** The Java reader handles text the native wrapper cannot return safely. */
internal fun needsJavaTextFallback(rawBytes: ByteArray, text: String?): Boolean =
    rawBytes.any { it == 0.toByte() } || text.isNullOrEmpty()

/** Empty barcode contents are treated as a missed decode. */
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

    /** Uses native decoding first, then falls back when it cannot handle the frame. */
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

    /** Creates the Java reader only when fallback is needed. */
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
    // CameraX runs analysis on one serial executor, so each mode can reuse its readers.
    private val multiNativeReaders = mutableMapOf<ScanMode, BarcodeReader>()
    private val javaReaders = mutableMapOf<Pair<ScanMode, Boolean>, MultiFormatReader>()
    private val router = DecoderRouter(
        nativeFrameSupported = ::nativeCanRead,
        nativeFactory = ::createNativeDecoder,
        javaFactory = ::createJavaDecoder,
    )

    /** Decodes one value using the standard native-first route. */
    override fun decode(frame: ImageProxy, mode: ScanMode): DecodeOutcome = router.decode(frame, mode)

    /** Finds several values for Preview, using Java when a crop is requested. */
    override fun decodeCandidates(frame: ImageProxy, mode: ScanMode, crop: Rect?): DecodeOutcome {
        if (crop != null) return decodeJavaCrop(frame, mode, crop, multiple = true)
        if (!nativeCanRead(frame)) return decodeJavaCrop(frame, mode, null, multiple = true)
        return try {
            val reader = multiNativeReaders.getOrPut(mode) {
                BarcodeReader(BarcodeDecoderProfiles.nativeOptions(mode, MAX_PREVIEW_CANDIDATES))
            }
            val rawResults = reader.read(frame)
            if (rawResults.any { result -> result.format != BarcodeReader.Format.NONE && result.error == null &&
                    result.bytes?.isNotEmpty() == true && needsJavaTextFallback(result.bytes!!, result.text) }) {
                return decodeJavaCrop(frame, mode, null, multiple = true)
            }
            val results = rawResults.asSequence()
                .filter { it.format != BarcodeReader.Format.NONE && it.error == null && it.bytes?.isNotEmpty() == true }
                .mapNotNull { it.text?.takeIf(String::isNotEmpty) }.distinct().toList()
            if (results.isNotEmpty()) DecodeOutcome.Values(results) else DecodeOutcome.NotFound
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            // Native decoding remains primary. Use Java only when native is unavailable or errors.
            decodeJavaCrop(frame, mode, null, multiple = true)
        }
    }

    /** Keeps Focus to one value inside the selected crop. */
    override fun decodeFocused(frame: ImageProxy, mode: ScanMode, crop: Rect): DecodeOutcome =
        decodeJavaCrop(frame, mode, crop, multiple = false)

    /** Reuses the mode's Java reader and copies only the selected luma area. */
    private fun decodeJavaCrop(frame: ImageProxy, mode: ScanMode, crop: Rect?, multiple: Boolean): DecodeOutcome {
        return try {
            val oriented = javaLuminanceSource(frame, crop) ?: return DecodeOutcome.NotFound
            val reader = javaReader(mode, focusCrop = crop != null)
            try {
                val bitmap = BinaryBitmap(HybridBinarizer(oriented))
                if (multiple) {
                    val found = GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap)
                        .mapNotNull { it.text.takeIf(String::isNotEmpty) }
                        .distinct()
                        .take(MAX_PREVIEW_CANDIDATES)
                    if (found.isEmpty()) DecodeOutcome.NotFound else DecodeOutcome.Values(found)
                } else {
                    val text = decodeJavaValue(reader, bitmap)
                    text?.let(DecodeOutcome::Value) ?: DecodeOutcome.NotFound
                }
            } catch (_: NotFoundException) {
                DecodeOutcome.NotFound
            } catch (_: ChecksumException) {
                DecodeOutcome.NotFound
            } catch (_: FormatException) {
                DecodeOutcome.NotFound
            } finally {
                reader.reset()
            }
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            DecodeOutcome.Failure
        }
    }

    /** Creates each Java reader once and reuses it on CameraX's single analysis thread. */
    private fun javaReader(mode: ScanMode, focusCrop: Boolean = false): MultiFormatReader =
        javaReaders.getOrPut(mode to focusCrop) {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to BarcodeDecoderProfiles.javaFormats,
                    // Cropping removes context, so Focus always gives ZXing the slower thorough pass.
                    DecodeHintType.TRY_HARDER to (focusCrop || BarcodeDecoderProfiles.javaTryHarder(mode)),
                ),
            )
        }
    }

    /** Builds the rotated Java luminance source from a safe frame crop. */
    private fun javaLuminanceSource(frame: ImageProxy, crop: Rect?): LuminanceSource? {
        val plane = frame.planes.firstOrNull() ?: return null
        val safeCrop = Rect(crop ?: frame.cropRect).apply { intersect(frame.cropRect) }
        if (safeCrop.isEmpty) return null
        val source = PlanarYUVLuminanceSource(
            copyLumaPlane(plane.buffer, plane.rowStride, plane.pixelStride, safeCrop),
            safeCrop.width(),
            safeCrop.height(),
            0,
            0,
            safeCrop.width(),
            safeCrop.height(),
            false,
        )
        return rotate(source, frame.imageInfo.rotationDegrees)
    }

    /** Returns a decoded value or null when ZXing does not find a valid barcode. */
    private fun decodeJavaValue(reader: MultiFormatReader, bitmap: BinaryBitmap): String? = try {
        reader.decodeWithState(bitmap).text.takeIf(String::isNotEmpty)
    } catch (_: NotFoundException) {
        null
    } catch (_: ChecksumException) {
        null
    } catch (_: FormatException) {
        null
    }

    /** Builds the primary ZXing-C++ decoder for the selected scan mode. */
    private fun createNativeDecoder(mode: ScanMode): FrameValueDecoder<ImageProxy> {
        val reader = BarcodeReader(BarcodeDecoderProfiles.nativeOptions(mode))
        return FrameValueDecoder { image ->
            val result = reader.read(image).firstOrNull { candidate ->
                candidate.format != BarcodeReader.Format.NONE &&
                    candidate.error == null && candidate.bytes?.isNotEmpty() == true
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

    /** Builds the Java fallback for frames the native reader cannot handle. */
    private fun createJavaDecoder(mode: ScanMode): FrameValueDecoder<ImageProxy> {
        val reader = javaReader(mode)
        return FrameValueDecoder { image ->
            val oriented = javaLuminanceSource(image, crop = null) ?: return@FrameValueDecoder null
            try {
                decodeJavaValue(reader, BinaryBitmap(HybridBinarizer(oriented)))
            } finally {
                reader.reset()
            }
        }
    }

    /** Checks that the native reader can safely use this YUV plane and crop. */
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

    /** Copies a strided crop into the packed byte array ZXing expects. */
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

    /** Rotates the cropped image when CameraX leaves rotation in its metadata. */
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

/** Keeps the supported formats and decoder options in one place. */
internal object BarcodeDecoderProfiles {
    /** Keeps supported formats aligned between the native and Java readers. */
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

    /** Uses the selected mode and result limit to configure ZXing-C++. */
    fun nativeOptions(mode: ScanMode, maxSymbols: Int = 1) = BarcodeReader.Options(
        formats = nativeFormats,
        tryHarder = mode == ScanMode.ACCURATE,
        tryRotate = true,
        tryInvert = mode == ScanMode.ACCURATE,
        tryDownscale = mode == ScanMode.ACCURATE,
        tryDenoise = false,
        binarizer = BarcodeReader.Binarizer.LOCAL_AVERAGE,
        maxNumberOfSymbols = maxSymbols,
        // PLAIN keeps decoded data and GS1 separators rather than HRI presentation markup.
        textMode = BarcodeReader.TextMode.PLAIN,
    )

    /** Enables ZXing's slower search only in Accurate mode. */
    fun javaTryHarder(mode: ScanMode): Boolean = mode == ScanMode.ACCURATE
}
