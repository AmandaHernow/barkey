package net.hernow.barkey

import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Decodes camera frames on CameraX's analysis executor; frame data is never persisted. */
internal class BarcodeFrameAnalyzer(
    private val reader: MultiFormatReader,
    private val sessionId: Long,
    private val onDecoded: (Long, String) -> Unit,
) : ImageAnalysis.Analyzer {
    private var hasLoggedFrameError = false

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
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
            val orientedSource = rotate(source, image.imageInfo.rotationDegrees)
            val value = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(orientedSource))).text
            } catch (_: NotFoundException) {
                null
            } catch (_: ChecksumException) {
                null
            } catch (_: FormatException) {
                null
            } finally {
                reader.reset()
            }

            if (!value.isNullOrEmpty()) onDecoded(sessionId, value)
        } catch (error: Exception) {
            // Avoid per-frame log noise while retaining diagnostics; frame data and decoded text are never logged.
            if (!hasLoggedFrameError) {
                hasLoggedFrameError = true
                Log.w(TAG, "Dropping camera frame after analyzer error", error)
            }
        } finally {
            image.close()
        }
    }

    private fun copyLumaPlane(
        source: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        crop: Rect,
    ): ByteArray {
        val buffer = source.duplicate()
        val base = buffer.position()
        val width = crop.width()
        val height = crop.height()
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

    private fun rotate(
        source: PlanarYUVLuminanceSource,
        rotationDegrees: Int,
    ): com.google.zxing.LuminanceSource = when (rotationDegrees) {
        90 -> source.rotateCounterClockwise().rotateCounterClockwise().rotateCounterClockwise()
        180 -> source.rotateCounterClockwise().rotateCounterClockwise()
        270 -> source.rotateCounterClockwise()
        else -> source
    }

    companion object {
        private const val TAG = "BarcodeFrameAnalyzer"

        fun create(sessionId: Long, onDecoded: (Long, String) -> Unit): BarcodeFrameAnalyzer {
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(
                            com.google.zxing.BarcodeFormat.QR_CODE,
                            com.google.zxing.BarcodeFormat.DATA_MATRIX,
                            com.google.zxing.BarcodeFormat.AZTEC,
                            com.google.zxing.BarcodeFormat.PDF_417,
                            com.google.zxing.BarcodeFormat.UPC_A,
                            com.google.zxing.BarcodeFormat.UPC_E,
                            com.google.zxing.BarcodeFormat.EAN_8,
                            com.google.zxing.BarcodeFormat.EAN_13,
                            com.google.zxing.BarcodeFormat.CODE_39,
                            com.google.zxing.BarcodeFormat.CODE_93,
                            com.google.zxing.BarcodeFormat.CODE_128,
                            com.google.zxing.BarcodeFormat.ITF,
                            com.google.zxing.BarcodeFormat.CODABAR,
                            com.google.zxing.BarcodeFormat.RSS_14,
                            com.google.zxing.BarcodeFormat.RSS_EXPANDED,
                            com.google.zxing.BarcodeFormat.MAXICODE,
                        ),
                        DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }
            return BarcodeFrameAnalyzer(reader, sessionId, onDecoded)
        }
    }
}
