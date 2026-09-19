package com.hereliesaz.aive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reusable Aive loading treatment.
 *
 * The static frame is painted immediately. The transparent GIF is decoded off the UI thread and
 * replaces it only after decoding, so there is no blank frame while the animation becomes ready.
 */
@Composable
fun AiveLoadingAnimation(
    modifier: Modifier = Modifier,
    onAnimationStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val movie by produceState<Movie?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.drawable.haive_loader).use(Movie::decodeStream)
        }
    }

    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.haive_loader_frame0),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        val decodedMovie = movie
        if (decodedMovie != null) {
            AndroidView(
                factory = { viewContext ->
                    GifMovieView(viewContext).apply {
                        setMovie(decodedMovie, onAnimationStarted)
                    }
                },
                update = { view ->
                    view.setMovie(decodedMovie, onAnimationStarted)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private class GifMovieView(context: Context) : View(context) {
    private var movie: Movie? = null
    private var startedAtMs: Long = 0L
    private var firstFrameReported = false
    private var onAnimationStarted: (() -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    fun setMovie(movie: Movie, onAnimationStarted: () -> Unit) {
        if (this.movie !== movie) {
            this.movie = movie
            startedAtMs = 0L
            firstFrameReported = false
        }
        this.onAnimationStarted = onAnimationStarted
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animation = movie ?: return
        if (width == 0 || height == 0 || animation.width() == 0 || animation.height() == 0) return

        if (startedAtMs == 0L) startedAtMs = SystemClock.uptimeMillis()
        val duration = animation.duration().takeIf { it > 0 } ?: 1000
        val elapsed = ((SystemClock.uptimeMillis() - startedAtMs) % duration).toInt()
        animation.setTime(elapsed)

        val scale = minOf(
            width.toFloat() / animation.width().toFloat(),
            height.toFloat() / animation.height().toFloat(),
        )
        val drawWidth = animation.width() * scale
        val drawHeight = animation.height() * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        animation.draw(canvas, 0f, 0f)
        canvas.restore()

        if (!firstFrameReported) {
            firstFrameReported = true
            post { onAnimationStarted?.invoke() }
        }
        postInvalidateOnAnimation()
    }
}
