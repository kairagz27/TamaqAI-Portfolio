package com.tamaqai.ui

import com.tamaqai.ui.theme.WellnessGlass
import com.tamaqai.ui.theme.supportsWellnessBlur
import org.junit.Assert.*
import org.junit.Test

class WellnessBlurPolicyTest {
    private val enough=WellnessGlass.minimumBlurMemoryBytes
    @Test fun supportedHardwareUsesBlur() {assertTrue(supportsWellnessBlur(36,true,false,enough*4))}
    @Test fun oldAndroidUsesFallback() {assertFalse(supportsWellnessBlur(30,true,false,enough*4))}
    @Test fun android12Api31UsesLibraryFallback() {assertFalse(supportsWellnessBlur(31,true,false,enough*4))}
    @Test fun softwareCanvasUsesFallback() {assertFalse(supportsWellnessBlur(36,false,false,enough*4))}
    @Test fun lowRamFlagUsesFallback() {assertFalse(supportsWellnessBlur(36,true,true,enough*4))}
    @Test fun smallMemoryUsesFallbackEvenWithoutLowRamFlag() {assertFalse(supportsWellnessBlur(36,true,false,enough-1))}
    @Test fun api32MemoryBoundaryIsInclusive() {assertTrue(supportsWellnessBlur(32,true,false,enough))}
}
