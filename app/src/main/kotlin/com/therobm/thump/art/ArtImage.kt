package com.therobm.thump.art

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.therobm.thump.ThumpColors
import com.therobm.thump.data.ThumpData
import com.therobm.thump.data.ThumpDataNotConfigured
import java.io.IOException

/**
 * Single cover-art tile backed by ThumpData. Asks ThumpData for the bitmap and renders it via
 * `Image(bitmap = ...)` when ready, falling back to a flat surface-coloured placeholder when
 * the art id is null or the load fails. Decode happens inside ThumpData on Dispatchers.IO.
 *
 * The composable owns no clipping or shape — pass that on the `modifier` (e.g.
 * `Modifier.clip(CircleShape)` for artist tiles, `Modifier.clip(RoundedCornerShape(10.dp))` for
 * rounded squares). Both the placeholder Box and the rendered Image use the same modifier so
 * the shape stays consistent before and after the bitmap loads.
 */
@Composable
fun ArtImage(
    thumpData: ThumpData,
    artId: String?,
    sizePx: Int,
    contentDescription: String?,
    modifier: Modifier,
) {
    val bitmapState: MutableState<Bitmap?> = remember(artId, sizePx) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(artId, sizePx) {
        if (artId == null) {
            bitmapState.value = null
            return@LaunchedEffect
        }
        try {
            val loaded: Bitmap = thumpData.getCoverArt(artId, sizePx)
            bitmapState.value = loaded
        } catch (loadFailure: IOException) {
            bitmapState.value = null
        } catch (notConfigured: ThumpDataNotConfigured) {
            // First-boot race against MainActivity's setServerConfig LaunchedEffect; the
            // placeholder stays visible and recomposition picks up the bitmap once the bind
            // arrives.
            bitmapState.value = null
        }
    }

    val currentBitmap: Bitmap? = bitmapState.value
    if (currentBitmap == null) {
        Box(modifier = modifier.background(ThumpColors.Surface))
    } else {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}
