package com.henryuide.pruebacoffe.objectdetection

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader.TileMode
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.henryuide.pruebacoffe.camera.GraphicOverlay
import com.henryuide.pruebacoffe.camera.GraphicOverlay.Graphic
import com.henryuide.pruebacoffe.R
import com.google.mlkit.vision.objects.DetectedObject

/**
 * Draws the detected visionObject info over the camera preview for prominent visionObject detection mode.
 */
internal class ObjectGraphicInProminentMode(
    overlay: GraphicOverlay,
    private val visionObject: DetectedObject,
    private val confirmationController: ObjectConfirmationController
) : Graphic(overlay) {

    private val scrimPaint: Paint = Paint()
    private val eraserPaint: Paint
    private val boxPaint: Paint

    @ColorInt
    private val boxGradientStartColor: Int

    @ColorInt
    private val boxGradientEndColor: Int
    private val boxCornerRadius: Int

    init {
        // Sets up a gradient background color at vertical.
        scrimPaint.shader = if (confirmationController.isConfirmed) {
            LinearGradient(
                0f,
                0f,
                overlay.width.toFloat(),
                overlay.height.toFloat(),
                ContextCompat.getColor(context, R.color.object_confirmed_bg_gradient_start),
                ContextCompat.getColor(context, R.color.object_confirmed_bg_gradient_end),
                TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f,
                0f,
                overlay.width.toFloat(),
                overlay.height.toFloat(),
                ContextCompat.getColor(context, R.color.object_detected_bg_gradient_start),
                ContextCompat.getColor(context, R.color.object_detected_bg_gradient_end),
                TileMode.CLAMP
            )
        }

        eraserPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        boxPaint = Paint().apply {
            style = Style.STROKE
            strokeWidth = context
                .resources
                .getDimensionPixelOffset(
                    if (confirmationController.isConfirmed) {
                        R.dimen.bounding_box_confirmed_stroke_width
                    } else {
                        R.dimen.bounding_box_stroke_width
                    }
                ).toFloat()
            color = Color.WHITE
        }

        boxGradientStartColor = ContextCompat.getColor(context, R.color.bounding_box_gradient_start)
        boxGradientEndColor = ContextCompat.getColor(context, R.color.bounding_box_gradient_end)
        boxCornerRadius = context.resources.getDimensionPixelOffset(R.dimen.bounding_box_corner_radius)
    }

    override fun draw(canvas: Canvas) {
        val rect = overlay.translateRect(visionObject.boundingBox)

        // Draws the dark background scrim and leaves the visionObject area clear.
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), scrimPaint)
        canvas.drawRoundRect(rect, boxCornerRadius.toFloat(), boxCornerRadius.toFloat(), eraserPaint)

        // Draws the bounding box with a gradient border color at vertical.
        boxPaint.shader = if (confirmationController.isConfirmed) {
            null
        } else {
            LinearGradient(
                rect.left,
                rect.top,
                rect.left,
                rect.bottom,
                boxGradientStartColor,
                boxGradientEndColor,
                TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, boxCornerRadius.toFloat(), boxCornerRadius.toFloat(), boxPaint)
    }
}
