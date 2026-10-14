package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlCompatibilityTest {

    @Test
    fun `TECNO KJ5 is kept off the custom curl`() {
        assertFalse(
            isPageCurlSupportedOnDevice(
                manufacturer = "TECNO",
                brand = "TECNO",
                model = "TECNO-KJ5",
                device = "KJ5",
            ),
        )
    }

    @Test
    fun `the TECNO Spark 20 sample is matched by its KJ5 device code`() {
        assertFalse(
            isPageCurlSupportedOnDevice(
                manufacturer = "TECNO",
                brand = "TECNO",
                model = "TECNO SPARK 20",
                device = "KJ5",
            ),
        )
    }

    @Test
    fun `IIIF150 Air1 Ultra is kept off the custom curl`() {
        assertFalse(
            isPageCurlSupportedOnDevice(
                manufacturer = "Unknown",
                brand = "Unknown",
                model = "Air1_Ultra",
                device = "Air1_Ultra",
            ),
        )
    }

    @Test
    fun `unreported models keep the custom curl`() {
        assertTrue(
            isPageCurlSupportedOnDevice(
                manufacturer = "TECNO",
                brand = "TECNO",
                model = "TECNO-KJ50",
                device = "KJ50",
            ),
        )
        assertTrue(
            isPageCurlSupportedOnDevice(
                manufacturer = "IIIF150",
                brand = "IIIF150",
                model = "Air1",
                device = "Air1",
            ),
        )
        assertTrue(
            isPageCurlSupportedOnDevice(
                manufacturer = "Google",
                brand = "Google",
                model = "Pixel 10",
                device = "generic",
            ),
        )
    }
}
