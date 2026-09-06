package com.phoneoperator.accessibility

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Deliberate last resort. `ScreenReader` (structured accessibility tree) is
 * the default and only path for 95% of tap/find requests — it's cheaper and
 * more reliable. This class exists purely for the rare case where a target
 * genuinely has no accessible text/description (custom-drawn canvas UI,
 * some games, certain camera viewfinders).
 *
 * Real capture requires a MediaProjection token obtained via
 * MediaProjectionManager.createScreenCaptureIntent(), which needs a one-time
 * user consent dialog — this class only exposes the capture step; wiring
 * the consent flow belongs in MainActivity when this is actually needed.
 */
@RequiresApi(Build.VERSION_CODES.O)
class ScreenshotCapture(
    private val takeSnapshot: suspend () -> Bitmap?
) {
    suspend fun capture(): Bitmap? = takeSnapshot()
}
