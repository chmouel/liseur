package com.chmouel.liseur.reader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCurlShaderTest {

    private val width = 1000f
    private val height = 1800f
    private val radius = 100f
    private val paper = 0xFFFAF3E0.toInt()

    @Test
    fun `computePageCurlUniforms matches PageCurl mesh geometry across turns and tilts`() {
        val mesh = PageCurl.Mesh(cols = 8, rows = 8)
        for (movesLeft in listOf(true, false)) {
            for (tilt in listOf(-0.3f, 0f, 0.25f)) {
                for (grabY in listOf(200f, height / 2f, height - 150f)) {
                    for (travel in listOf(0f, 40f, 250f, 600f, 950f)) {
                        PageCurl.mesh(
                            width = width,
                            height = height,
                            travel = travel,
                            grabY = grabY,
                            tilt = tilt,
                            movesLeft = movesLeft,
                            radius = radius,
                            paper = paper,
                            out = mesh,
                        )
                        val uniforms = computePageCurlUniforms(
                            width = width,
                            height = height,
                            travel = travel,
                            grabY = grabY,
                            tilt = tilt,
                            movesLeft = movesLeft,
                            radius = radius,
                            paper = paper,
                        )
                        val label = "movesLeft=$movesLeft tilt=$tilt grabY=$grabY travel=$travel"
                        assertEquals(label, mesh.foldX, uniforms.foldX, 1e-4f)
                        assertEquals(label, mesh.foldY, uniforms.foldY, 1e-4f)
                        assertEquals(label, mesh.normalX, uniforms.normalX, 1e-4f)
                        assertEquals(label, mesh.normalY, uniforms.normalY, 1e-4f)
                        assertEquals(label, mesh.radius, uniforms.radius, 1e-4f)
                        assertEquals(label, mesh.lift, uniforms.lift, 1e-4f)
                        assertEquals(label, mesh.silhouette, uniforms.silhouette, 1e-4f)
                        assertEquals(label, mesh.curled, uniforms.curled)
                    }
                }
            }
        }
    }

    @Test
    fun `computePageCurlUniforms handles zero and negative travel and extracts RGB`() {
        val zero = computePageCurlUniforms(
            width = width,
            height = height,
            travel = 0f,
            grabY = height / 2f,
            tilt = 0.4f,
            movesLeft = true,
            radius = radius,
            paper = paper,
        )
        assertFalse(zero.curled)
        assertEquals(0f, zero.edgeDistance, 0f)
        assertEquals(0f, zero.lift, 0f)
        assertEquals(0f, zero.silhouette, 0f)
        assertEquals(width, zero.foldX, 1e-4f)
        assertEquals(1f, zero.normalX, 1e-6f)
        assertEquals(0f, zero.normalY, 1e-6f)
        assertEquals(0xFA / 255f, zero.paperR, 1e-6f)
        assertEquals(0xF3 / 255f, zero.paperG, 1e-6f)
        assertEquals(0xE0 / 255f, zero.paperB, 1e-6f)

        val negative = computePageCurlUniforms(
            width = width,
            height = height,
            travel = -50f,
            grabY = height / 2f,
            tilt = 0.2f,
            movesLeft = false,
            radius = radius,
            paper = paper,
        )
        assertFalse(negative.curled)
        assertEquals(0f, negative.foldX, 1e-4f)
        assertEquals(-1f, negative.normalX, 1e-6f)
    }

    @Test
    fun `PAGE_CURL_AGSL_SOURCE declares required uniforms and entrypoint`() {
        val expectedDeclarations = listOf(
            "uniform shader page;",
            "uniform float2 resolution;",
            "uniform float2 foldPoint;",
            "uniform float2 foldNormal;",
            "uniform float radius;",
            "uniform float edgeDistance;",
            "uniform float lift;",
            "uniform float3 paperColor;",
            "half4 main(float2 fragCoord)",
        )
        for (declaration in expectedDeclarations) {
            assertTrue(
                "Missing declaration: $declaration",
                PAGE_CURL_AGSL_SOURCE.contains(declaration),
            )
        }
    }

    @Test
    fun `shouldUseAgslPageCurl requires API 33 or newer`() {
        assertFalse(shouldUseAgslPageCurl(26))
        assertFalse(shouldUseAgslPageCurl(32))
        assertTrue(shouldUseAgslPageCurl(33))
        assertTrue(shouldUseAgslPageCurl(35))
    }

    @Test
    fun `isPageCurlSupportedOnDevice allows blocklisted models on API 33 and above`() {
        assertFalse(
            isPageCurlSupportedOnDevice(
                manufacturer = "TECNO",
                brand = "TECNO",
                model = "TECNO-KJ5",
                device = "KJ5",
                sdkInt = 32,
            ),
        )
        assertTrue(
            isPageCurlSupportedOnDevice(
                manufacturer = "TECNO",
                brand = "TECNO",
                model = "TECNO-KJ5",
                device = "KJ5",
                sdkInt = 33,
            ),
        )
        assertTrue(
            isPageCurlSupportedOnDevice(
                manufacturer = "Unknown",
                brand = "Unknown",
                model = "Air1_Ultra",
                device = "Air1_Ultra",
                sdkInt = 35,
            ),
        )
    }
}
