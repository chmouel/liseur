package com.chmouel.liseur.reader.chrome

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class PageCurlUniforms(
    val width: Float,
    val height: Float,
    val foldX: Float,
    val foldY: Float,
    val normalX: Float,
    val normalY: Float,
    val radius: Float,
    val edgeDistance: Float,
    val lift: Float,
    val silhouette: Float,
    val paperR: Float,
    val paperG: Float,
    val paperB: Float,
    val curled: Boolean,
)

fun computePageCurlUniforms(
    width: Float,
    height: Float,
    travel: Float,
    grabY: Float,
    tilt: Float,
    movesLeft: Boolean,
    radius: Float,
    paper: Int,
): PageCurlUniforms {
    val t = max(0f, travel)
    val bendRadius = PageCurl.bendRadius(t, width, radius)
    val ramp = if (width <= 0f) 0f else min(1f, t / (TILT_RAMP_FRACTION * width))
    val phi = tilt * ramp
    val cosP = cos(phi)
    val sinP = sin(phi)
    val edgeDistance = PageCurl.edgeDistance(t / cosP, bendRadius)
    val foldU = width - edgeDistance / cosP
    val edgeTheta = edgeDistance / bendRadius
    val lift = min(1f, edgeTheta / (PI / 2).toFloat())
    val silhouette = bendRadius * sin(min(edgeTheta, (PI / 2).toFloat()))
    val foldX = if (movesLeft) foldU else width - foldU
    val normalX = if (movesLeft) cosP else -cosP
    val paperR = ((paper ushr 16) and 0xFF) / 255f
    val paperG = ((paper ushr 8) and 0xFF) / 255f
    val paperB = (paper and 0xFF) / 255f

    return PageCurlUniforms(
        width = width,
        height = height,
        foldX = foldX,
        foldY = grabY,
        normalX = normalX,
        normalY = sinP,
        radius = bendRadius,
        edgeDistance = edgeDistance,
        lift = lift,
        silhouette = silhouette,
        paperR = paperR,
        paperG = paperG,
        paperB = paperB,
        curled = t > 0f,
    )
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
fun shouldUseAgslPageCurl(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU

const val PAGE_CURL_AGSL_SOURCE: String = """
uniform shader page;
uniform float2 resolution;
uniform float2 foldPoint;
uniform float2 foldNormal;
uniform float radius;
uniform float edgeDistance;
uniform float lift;
uniform float3 paperColor;

const float PI = 3.14159265;
const float HALF_PI = 1.57079632;
const float PAPER_ALPHA = 0.88;
const float BACK_SHADE = 0.78;
const float SHADOW_REACH = 0.6;

bool inPageBounds(float2 p, float2 res) {
    return p.x >= 0.0 && p.x <= res.x && p.y >= 0.0 && p.y <= res.y;
}

half4 shadeBack(float2 coord, float theta) {
    half4 sampleColor = page.eval(coord);
    float over = clamp((theta - HALF_PI) / HALF_PI, 0.0, 1.0);
    float shade = BACK_SHADE + (1.0 - BACK_SHADE) * over;
    half3 tintedPaper = half3(paperColor * shade);
    half3 rgb = mix(sampleColor.rgb, tintedPaper, half(PAPER_ALPHA));
    return half4(rgb, sampleColor.a);
}

half4 main(float2 fragCoord) {
    float d = dot(fragCoord - foldPoint, foldNormal);

    if (d > radius) {
        float edgeTheta = edgeDistance / max(radius, 0.0001);
        float silhouette = radius * sin(min(edgeTheta, HALF_PI));
        float reach = silhouette + radius * SHADOW_REACH;
        float distPastFold = d - silhouette;
        if (reach > 0.0 && distPastFold > 0.0 && distPastFold < reach) {
            float alpha = 0.28 * lift * (1.0 - smoothstep(0.0, reach, distPastFold));
            return half4(0.0, 0.0, 0.0, half(alpha));
        }
        return half4(0.0);
    }

    if (d >= 0.0) {
        float theta = asin(clamp(d / max(radius, 0.0001), 0.0, 1.0));
        float dFront = radius * theta;
        float2 frontSourceCoord = fragCoord + (dFront - d) * foldNormal;
        half4 result = half4(0.0);

        if (dFront <= edgeDistance && inPageBounds(frontSourceCoord, resolution)) {
            half4 frontSample = page.eval(frontSourceCoord);
            float crease = 1.0 - 0.30 * (1.0 - cos(theta));
            float s = sin(theta);
            float specular = 0.12 * s * s;
            half3 frontRgb = clamp(frontSample.rgb * half(crease) + half3(specular), half3(0.0), half3(1.0));
            result = half4(frontRgb, frontSample.a);
        }

        float thetaBack = PI - theta;
        float dBack = radius * thetaBack;
        float2 backSourceCoord = fragCoord + (dBack - d) * foldNormal;
        if (dBack <= edgeDistance && inPageBounds(backSourceCoord, resolution)) {
            half4 backColor = shadeBack(backSourceCoord, thetaBack);
            float s = sin(thetaBack);
            float specular = 0.12 * s * s;
            half3 backRgb = clamp(backColor.rgb + half3(specular), half3(0.0), half3(1.0));
            result = half4(backRgb, backColor.a);
        }

        if (result.a == half(0.0)) {
            float edgeTheta = edgeDistance / max(radius, 0.0001);
            float silhouette = radius * sin(min(edgeTheta, HALF_PI));
            float reach = silhouette + radius * SHADOW_REACH;
            float distPastSilhouette = d - silhouette;
            if (reach > 0.0 && distPastSilhouette > 0.0 && distPastSilhouette < reach) {
                float alpha = 0.28 * lift * (1.0 - smoothstep(0.0, reach, distPastSilhouette));
                return half4(0.0, 0.0, 0.0, half(alpha));
            }
        }

        return result;
    }

    half4 base = page.eval(fragCoord);
    float dTail = PI * radius - d;
    float2 tailSourceCoord = fragCoord + (dTail - d) * foldNormal;
    if (dTail <= edgeDistance && inPageBounds(tailSourceCoord, resolution)) {
        return shadeBack(tailSourceCoord, PI);
    }

    float tailDist = dTail - edgeDistance;
    float tailReach = radius * 0.35;
    if (dTail > edgeDistance && tailDist < tailReach && edgeDistance > HALF_PI * radius) {
        float tailShadow = 0.14 * lift * (1.0 - smoothstep(0.0, tailReach, tailDist));
        return half4(base.rgb * half(1.0 - tailShadow), base.a);
    }

    return base;
}
"""

private const val TILT_RAMP_FRACTION = 0.25f

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AgslPageCurlRenderer {
    private var runtimeShader: RuntimeShader? = null
    private var shaderFailed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedBitmap: Bitmap? = null
    private var cachedBitmapShader: BitmapShader? = null

    private fun obtainShader(): RuntimeShader? {
        if (shaderFailed) return null
        runtimeShader?.let { return it }
        return try {
            RuntimeShader(PAGE_CURL_AGSL_SOURCE).also {
                runtimeShader = it
                paint.shader = it
            }
        } catch (_: Throwable) {
            shaderFailed = true
            null
        }
    }

    private fun obtainBitmapShader(bitmap: Bitmap): BitmapShader {
        val currentShader = cachedBitmapShader
        if (cachedBitmap === bitmap && currentShader != null) {
            return currentShader
        }
        return BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            cachedBitmap = bitmap
            cachedBitmapShader = it
        }
    }

    fun draw(canvas: Canvas, bitmap: Bitmap, uniforms: PageCurlUniforms): Boolean {
        val shader = obtainShader() ?: return false
        return try {
            shader.setInputShader("page", obtainBitmapShader(bitmap))
            shader.setFloatUniform("resolution", uniforms.width, uniforms.height)
            shader.setFloatUniform("foldPoint", uniforms.foldX, uniforms.foldY)
            shader.setFloatUniform("foldNormal", uniforms.normalX, uniforms.normalY)
            shader.setFloatUniform("radius", uniforms.radius)
            shader.setFloatUniform("edgeDistance", uniforms.edgeDistance)
            shader.setFloatUniform("lift", uniforms.lift)
            shader.setFloatUniform(
                "paperColor",
                uniforms.paperR,
                uniforms.paperG,
                uniforms.paperB,
            )
            canvas.drawRect(0f, 0f, uniforms.width, uniforms.height, paint)
            true
        } catch (_: Throwable) {
            shaderFailed = true
            false
        }
    }
}
