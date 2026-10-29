package net.hernow.barkey

import com.google.zxing.BarcodeFormat as JavaBarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zxingcpp.BarcodeReader

class BarcodeDecoderTest {
    @Test fun fastAndAccurateNativeProfilesHaveSameFormatsAndRequestedTradeoffs() {
        val fast = BarcodeDecoderProfiles.nativeOptions(ScanMode.FAST)
        val accurate = BarcodeDecoderProfiles.nativeOptions(ScanMode.ACCURATE)

        assertEquals(BarcodeDecoderProfiles.nativeFormats, fast.formats)
        assertEquals(fast.formats, accurate.formats)
        assertFalse(fast.tryHarder)
        assertTrue(fast.tryRotate)
        assertFalse(fast.tryInvert)
        assertFalse(fast.tryDownscale)
        assertFalse(fast.tryDenoise)
        assertEquals(BarcodeReader.Binarizer.LOCAL_AVERAGE, fast.binarizer)
        assertTrue(accurate.tryHarder)
        assertTrue(accurate.tryRotate)
        assertTrue(accurate.tryInvert)
        assertTrue(accurate.tryDownscale)
        assertFalse(accurate.tryDenoise)
        assertEquals(1, accurate.maxNumberOfSymbols)
        assertEquals(BarcodeReader.TextMode.PLAIN, accurate.textMode)
        assertFalse(BarcodeDecoderProfiles.javaTryHarder(ScanMode.FAST))
        assertTrue(BarcodeDecoderProfiles.javaTryHarder(ScanMode.ACCURATE))
    }

    @Test fun configuredFormatsPreserveLegacySetAndAddPinnedNativeFormats() {
        assertTrue(BarcodeReader.Format.MAXI_CODE in BarcodeDecoderProfiles.nativeFormats)
        assertTrue(BarcodeReader.Format.MICRO_QR_CODE in BarcodeDecoderProfiles.nativeFormats)
        assertTrue(BarcodeReader.Format.RMQR_CODE in BarcodeDecoderProfiles.nativeFormats)
        assertTrue(BarcodeReader.Format.MICRO_PDF_417 in BarcodeDecoderProfiles.nativeFormats)
        assertTrue(BarcodeReader.Format.DATA_BAR_EXP_STK in BarcodeDecoderProfiles.nativeFormats)
        assertEquals(24, BarcodeDecoderProfiles.nativeFormats.size)
        assertEquals(16, BarcodeDecoderProfiles.javaFormats.size)
        assertTrue(JavaBarcodeFormat.RSS_14 in BarcodeDecoderProfiles.javaFormats)
        assertTrue(JavaBarcodeFormat.RSS_EXPANDED in BarcodeDecoderProfiles.javaFormats)
    }

    @Test fun routerUsesJavaWhenNativeCannotLoadAndDoesNotRetryNative() {
        var nativeAttempts = 0
        val router = DecoderRouter<Int>(
            nativeFrameSupported = { true },
            nativeFactory = {
                nativeAttempts += 1
                throw UnsatisfiedLinkError("native library unavailable")
            },
            javaFactory = { FrameValueDecoder { "java-value" } },
        )

        assertEquals(DecodeOutcome.Value("java-value"), router.decode(1, ScanMode.FAST))
        assertEquals(DecodeOutcome.Value("java-value"), router.decode(2, ScanMode.ACCURATE))
        assertEquals(1, nativeAttempts)
        assertEquals(1, router.nativeInitializationAttempts)
    }

    @Test fun ordinaryNativeMissDoesNotRunJavaFallback() {
        var javaCalls = 0
        val router = DecoderRouter<Int>(
            nativeFrameSupported = { true },
            nativeFactory = { FrameValueDecoder { null } },
            javaFactory = { FrameValueDecoder { javaCalls += 1; "java-value" } },
        )

        assertEquals(DecodeOutcome.NotFound, router.decode(1, ScanMode.FAST))
        assertEquals(0, javaCalls)
    }

    @Test fun incompatibleFrameUsesJavaWithoutTryingNative() {
        var nativeCalls = 0
        val router = DecoderRouter<Int>(
            nativeFrameSupported = { false },
            nativeFactory = { nativeCalls += 1; FrameValueDecoder { "native" } },
            javaFactory = { FrameValueDecoder { "java" } },
        )

        assertEquals(DecodeOutcome.Value("java"), router.decode(1, ScanMode.FAST))
        assertEquals(0, nativeCalls)
    }

    @Test fun nativeReadFailureDisablesNativeAndFallsBackToJava() {
        var nativeCalls = 0
        val router = DecoderRouter<Int>(
            nativeFrameSupported = { true },
            nativeFactory = { nativeCalls += 1; FrameValueDecoder { error("native failure") } },
            javaFactory = { FrameValueDecoder { "java" } },
        )

        assertEquals(DecodeOutcome.Value("java"), router.decode(1, ScanMode.FAST))
        assertEquals(DecodeOutcome.Value("java"), router.decode(2, ScanMode.ACCURATE))
        assertEquals(1, nativeCalls)
    }

    @Test fun valuesAreNotTrimmedAndEmptyResultsAreRejected() {
        assertNull(validDecodedText(null))
        assertNull(validDecodedText(""))
        assertEquals(" 01\u001dABC ", validDecodedText(" 01\u001dABC "))
        assertEquals(DecodeOutcome.NotFound, DecoderRouter<Int>(
            nativeFrameSupported = { true },
            nativeFactory = { FrameValueDecoder { "" } },
            javaFactory = { FrameValueDecoder { "" } },
        ).decode(1, ScanMode.FAST))
    }

    @Test fun nativeWrapperFallsBackForNulAndRejectsEmptyText() {
        assertTrue(needsJavaTextFallback(byteArrayOf(0x41, 0x00, 0x42), "A"))
        assertTrue(needsJavaTextFallback(byteArrayOf(0x41), null))
        assertTrue(needsJavaTextFallback(byteArrayOf(0x41), ""))
        assertFalse(needsJavaTextFallback(byteArrayOf(0x41, 0x1d, 0x42), "A\u001dB"))
    }
}
