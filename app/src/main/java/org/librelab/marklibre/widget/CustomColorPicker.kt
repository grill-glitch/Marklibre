package org.librelab.marklibre.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zt64.compose.pipette.HsvColor
import dev.zt64.compose.pipette.RingColorPicker
import dev.zt64.compose.pipette.SquareColorPicker
import kotlin.math.ceil
import org.librelab.marklibre.R

/**
 * Project wrapper around `compose-pipette`.
 *
 * Existing UI contract preserved:
 *  - caller hands in [initialColor] (colour the picker opened with) and
 *    [originalColor] (used by the Original swatch).
 *  - Cancel must NOT persist; Apply commits a single ARGB int.
 *  - hex input edits H/S/V only (alpha is a separate slider; pipette v2.0
 *    has no alpha channel).
 *  - ARGB Int stays the boundary model: no Compose types leak out.
 *
 * Colours come from `MaterialTheme.colorScheme`, which the caller sets to the
 * app's dynamic Material 3 scheme, so the popup matches the rest of the UI.
 *
 * Opts in to Material3's experimental slider `track` slot, which is the only
 * supported way to paint a background layer under the track without
 * reimplementing or resizing the control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomColorPicker(
    initialColor: Int,
    originalColor: Int,
    onPickColorFromImage: () -> Unit,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit,
    /**
     * Live color sampled by the eyedropper (set while the user holds the
     * finger on the image). When non-null and different from the picker
     * state, the Current swatch, hex field and active track/thumb mirror
     * this color instead of the internal HSV state. The hue ring / sat-val
     * square / alpha slider are NOT touched - the picker is a readout of
     * the sample, not a target, while the eyedropper is armed.
     */
    livePreviewColor: Int? = null,
    modifier: Modifier = Modifier,
) {
    // Re-key on initialColor: when the caller resets the picker (Cancel
    // followed by reopen, or setPickedColor), a fresh initialColor arrives
    // and the slider/HSV state snaps back.
    val initial = remember(initialColor) { argbToHsvPlusAlpha(initialColor) }
    val original = remember(originalColor) { argbToHsvPlusAlpha(originalColor) }

    var hue by remember(initialColor) { mutableStateOf(initial.hue) }
    var sat by remember(initialColor) { mutableStateOf(initial.saturation) }
    var value by remember(initialColor) { mutableStateOf(initial.value) }
    var alpha by remember(initialColor) { mutableStateOf(initial.alpha) }

    val argbNow = hsvPlusAlphaToArgb(hue, sat, value, alpha)

    // While the eyedropper is armed, the Current swatch / hex / track / thumb
    // mirror the live sample instead of the picker's own HSV state. We key
    // the override on the color itself (not a "isLive" bool) so a real
    // -null- transition does not re-key the picker at all - slider state is
    // preserved across the eyedropper gesture.
    val argbDisplay = livePreviewColor?.takeIf { it != argbNow } ?: argbNow

    val ringInteraction = remember { MutableInteractionSource() }
    val squareInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Row 1: Original swatch + Current swatch + hex text field
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwatchColumn(
                label = stringResource(R.string.palette_original),
                color = original.toArgbInt(),
            )
            SwatchColumn(
                label = stringResource(R.string.palette_current),
                color = argbDisplay,
            )
            Spacer(modifier = Modifier.width(2.dp))
            HexField(
                argb = argbDisplay,
                onHexChange = { rgb ->
                    val parsed = parseHexRgb(rgb) ?: return@HexField
                    val hsv = argbToHsvPlusAlpha(parsed)
                    hue = hsv.hue
                    sat = hsv.saturation
                    value = hsv.value
                },
                modifier = Modifier.weight(1f),
            )
        }

        // Row 2: large hue ring + smaller sat/value square, side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.palette_hue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                RingColorPicker(
                    color = { HsvColor(hue, sat, value) },
                    onColorChange = { new ->
                        hue = new.hue
                        // A black/gray source has sat = val = 0, so every hue
                        // still renders black. Restore them so dragging the
                        // ring from an achromatic colour produces colour
                        // (mirrors the previous picker's behaviour).
                        if (sat < 0.05f) sat = 1f
                        if (value < 0.05f) value = 1f
                    },
                    modifier = Modifier.size(RING_SIZE),
                    interactionSource = ringInteraction,
                )
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.palette_color),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SquareColorPicker(
                    color = { HsvColor(hue, sat, value) },
                    onColorChange = { new ->
                        sat = new.saturation
                        value = new.value
                    },
                    modifier = Modifier.size(SQUARE_SIZE),
                    shape = RoundedCornerShape(6.dp),
                    interactionSource = squareInteraction,
                )
            }
        }

        // Row 3: alpha slider (pipette has no alpha channel; the previous
        // View-based picker had one, so we keep the capability).
        Column {
            Text(
                text = stringResource(R.string.palette_opacity),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The slider itself is untouched - same value, range, thumb and
            // geometry. Only its `track` slot is replaced, so the
            // checkerboard lands *under* the track the framework already
            // draws rather than replacing the control.
            val sliderColors = SliderDefaults.colors(
                // The thumb stays fully opaque: it is the handle you grab,
                // not a preview of the colour's alpha. Only the track under
                // it is allowed to go see-through.
                thumbColor = Color(argbDisplay or 0xFF000000.toInt()),
                activeTrackColor = Color(argbDisplay),
                // Transparent, otherwise the opaque inactive track would
                // paint over the checkerboard drawn beneath it. At full
                // alpha the active track still covers the whole width, so
                // the slider looks exactly as it did before.
                inactiveTrackColor = Color.Transparent,
            )
            Slider(
                value = alpha.toFloat(),
                onValueChange = { alpha = it.toInt().coerceIn(0, 255) },
                valueRange = 0f..255f,
                colors = sliderColors,
                track = { state ->
                    // Pill-clipped so the pattern follows the track's
                    // rounded ends instead of showing square corners.
                    val trackBackground = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .checkerboard()
                    SliderDefaults.Track(
                        sliderState = state,
                        modifier = trackBackground,
                        colors = sliderColors,
                        enabled = true,
                    )
                },
            )
        }

        // Row 5: colorize + cancel + apply
        val colorizeLabel = stringResource(R.string.palette_colorize)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.palette_cancel),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onPickColorFromImage,
                modifier = Modifier
                    .size(40.dp)
                    .semantics {
                        contentDescription = colorizeLabel
                    },
            ) {
                Icon(
                    // Avoid the icons-extended dependency: a simple "dropper"
                    // glyph isn't critical here, the contentDescription above
                    // does the talking for TalkBack. Using a Material3 stock
                    // icon would pull in material-icons-extended (~20MB).
                    painter = androidx.compose.ui.res.painterResource(
                        id = org.librelab.marklibre.R.drawable.ic_colorize,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Button(
                onClick = { onApply(argbNow) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.palette_apply),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Checkerboard cell size used by the transparency backgrounds. */
private val CHECKER_CELL = 4.dp

private val CHECKER_LIGHT = Color(0xFFFFFFFF)
private val CHECKER_DARK = Color(0xFFCCCCCC)

/**
 * Draws a transparency checkerboard behind whatever is painted on top of it.
 *
 * Used under the Original/Current swatches and the opacity slider so a
 * partly transparent colour reads as *transparent* rather than as a dull
 * smudge against the popup background.
 *
 * Draws the light colour as one full-size rect and only the dark cells on
 * top, so a full-width track costs half the draw calls of a naive loop.
 */
private fun Modifier.checkerboard(
    cell: Dp = CHECKER_CELL,
    light: Color = CHECKER_LIGHT,
    dark: Color = CHECKER_DARK,
): Modifier = drawBehind {
    val cellPx = cell.toPx()
    if (cellPx <= 0f) return@drawBehind
    drawRect(color = light)
    val columns = ceil(size.width / cellPx).toInt()
    val rows = ceil(size.height / cellPx).toInt()
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            if ((row + column) % 2 != 0) {
                drawRect(
                    color = dark,
                    topLeft = Offset(column * cellPx, row * cellPx),
                    size = Size(cellPx, cellPx),
                )
            }
        }
    }
}

@Composable
private fun SwatchColumn(label: String, color: Int) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(width = 52.dp, height = 28.dp)
                .clip(RoundedCornerShape(4.dp))
                .checkerboard()
                .background(Color(color))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(4.dp),
                ),
        )
    }
}

/** Ring diameter. Deliberately large: it is the primary control. */
private val RING_SIZE = 140.dp

/** Sat/value square. Deliberately smaller than the ring. */
private val SQUARE_SIZE = 96.dp

@Composable
private fun HexField(
    argb: Int,
    onHexChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the RGB part only, so moving the opacity slider does not reset
    // the text/caret needlessly.
    val rgb = argb and 0x00FFFFFF
    var text by remember(rgb) { mutableStateOf("#" + formatHexRgb(rgb)) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw
                .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                .take(6)
                .uppercase()
            text = "#$digits"
            onHexChange(digits)
        },
        modifier = modifier,
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            textAlign = TextAlign.Start,
        ),
        placeholder = {
            Text(
                text = stringResource(R.string.palette_hex_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

// --- boundary conversions: ARGB Int <-> HsvColor + alpha -------------------

internal data class HsvPlusAlpha(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Int,
) {
    fun toArgbInt(): Int = hsvPlusAlphaToArgb(hue, saturation, value, alpha)
}

internal fun argbToHsvPlusAlpha(argb: Int): HsvPlusAlpha {
    val hsv = HsvColor(argb)
    return HsvPlusAlpha(
        hue = hsv.hue,
        saturation = hsv.saturation,
        value = hsv.value,
        alpha = (argb ushr 24) and 0xFF,
    )
}

internal fun hsvPlusAlphaToArgb(hue: Float, sat: Float, value: Float, alpha: Int): Int {
    val rgb = HsvColor(hue, sat, value).toColor().toArgb()
    return (alpha and 0xFF shl 24) or (rgb and 0x00FFFFFF)
}

internal fun formatHexRgb(argb: Int): String =
    "%02X%02X%02X".format(
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
    )

internal fun parseHexRgb(text: String): Int? {
    val cleaned = text.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    return cleaned.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
}