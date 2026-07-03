package org.fossify.phone.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.helpers.letterBackgroundColors
import kotlin.math.abs

// draws rounded-square contact avatars with up to two initials instead of the commons single-letter circle
class AvatarHelper(private val context: Context) {

    private val iconSize = context.resources.getDimension(org.fossify.commons.R.dimen.normal_icon_size).toInt()
    private val cornerRadius = iconSize * 0.28f

    // tileText, when set, is drawn on the placeholder instead of initials (used by the grid view).
    // tileWrapThreshold controls how long a name may be before it wraps to two lines: the small
    // Combined tiles wrap sooner, the large pure-grid tiles keep more letters on one line.
    fun loadContactAvatar(photoUri: String, imageView: ImageView, name: String, tileText: String? = null, tileWrapThreshold: Int = 8) {
        val placeholder = BitmapDrawable(context.resources, getLetterIcon(name, tileText, tileWrapThreshold))
        if (photoUri.isEmpty()) {
            imageView.setImageDrawable(placeholder)
            return
        }

        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .error(placeholder)
            .placeholder(placeholder)
            .centerCrop()

        Glide.with(context)
            .load(photoUri)
            .transition(withCrossFade())
            .apply(options)
            .transform(CenterCrop(), RoundedCorners(cornerRadius.toInt()))
            .into(imageView)
    }

    fun getLetterIcon(name: String, tileText: String? = null, tileWrapThreshold: Int = 8): Bitmap {
        val text = tileText?.trim()?.takeIf { it.isNotEmpty() } ?: getInitials(name)
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundColors = letterBackgroundColors
        val color = backgroundColors[abs(name.hashCode()) % backgroundColors.size].toInt()

        val backgroundPaint = Paint().apply {
            this.color = color
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, iconSize.toFloat(), iconSize.toFloat()),
            cornerRadius,
            cornerRadius,
            backgroundPaint
        )

        val textPaint = Paint().apply {
            this.color = color.getContrastColor()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        if (tileText != null) {
            // keep the name on a single line while it fits the tile; longer names wrap to two lines
            val lines = if (text.length > tileWrapThreshold) splitBalanced(text) else listOf(text)
            fitLinesTextSize(textPaint, lines)
            drawCenteredLines(canvas, textPaint, lines)
            return bitmap
        }

        textPaint.textSize = iconSize * if (text.length > 1) 0.42f else 0.5f

        val xPos = iconSize / 2f
        val yPos = iconSize / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, xPos, yPos, textPaint)

        return bitmap
    }

    // splits a word into two lines near the middle (rounding the first line up), e.g. NEMANJA -> NEMA / NJA
    private fun splitBalanced(text: String): List<String> {
        val mid = (text.length + 1) / 2
        return listOf(text.substring(0, mid), text.substring(mid))
    }

    private fun fitLinesTextSize(paint: Paint, lines: List<String>) {
        val maxWidth = iconSize * 0.82f
        val maxHeight = iconSize * 0.78f
        var size = iconSize * 0.42f
        paint.textSize = size
        while (size > iconSize * 0.16f) {
            val widest = lines.maxOf { paint.measureText(it) }
            val totalHeight = (paint.descent() - paint.ascent()) * lines.size
            if (widest <= maxWidth && totalHeight <= maxHeight) {
                break
            }
            size -= 1f
            paint.textSize = size
        }
    }

    private fun drawCenteredLines(canvas: Canvas, paint: Paint, lines: List<String>) {
        val lineHeight = paint.descent() - paint.ascent()
        val totalHeight = lineHeight * lines.size
        val xPos = iconSize / 2f
        var baseline = iconSize / 2f - totalHeight / 2f - paint.ascent()
        for (line in lines) {
            canvas.drawText(line, xPos, baseline, paint)
            baseline += lineHeight
        }
    }

    private fun getInitials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.size >= 2) {
            "${words.first().first()}${words.last().first()}".uppercase()
        } else {
            words.first().take(2).uppercase()
        }
    }

    companion object {
        // the first word of the display name (used as the grid tile text)
        fun firstNamePart(name: String): String {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return words.firstOrNull() ?: name.trim()
        }

        // everything after the first word (shown as normal text under the grid tile)
        fun surnamePart(name: String): String {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return if (words.size >= 2) words.drop(1).joinToString(" ") else ""
        }
    }
}
