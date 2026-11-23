package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixOff
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.imagegen.MaskStroke
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Interactive touch mask editor (like Local Dream's inpainting brush canvas).
 * Captures normalized strokes (0f..1f) over the reference image with live preview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InpaintMaskCanvas(
    imagePath: String,
    strokes: List<MaskStroke>,
    invertMask: Boolean,
    featherRadiusPx: Int,
    onAddStroke: (MaskStroke) -> Unit,
    onUndoStroke: () -> Unit,
    onClearMask: () -> Unit,
    onToggleInvertMask: () -> Unit,
    onUpdateFeather: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var brushRadiusFraction by remember { mutableFloatStateOf(0.065f) }
    var isEraser by remember { mutableStateOf(false) }
    var activePoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    val maskTintColor = if (invertMask) Color(0x9900D1FF) else Color(0x99FF2E63)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp)
                )
                .testTag("inpaint_mask_canvas")
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = stringResource(R.string.image_studio_inpaint_hint),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Offscreen compositing layer allows real eraser BlendMode.Clear over painted mask strokes
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .pointerInput(brushRadiusFraction, isEraser) {
                        detectTapGestures { pos ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val nx = (pos.x / w).coerceIn(0f, 1f)
                            val ny = (pos.y / h).coerceIn(0f, 1f)
                            onAddStroke(
                                MaskStroke(
                                    points = listOf(nx to ny),
                                    brushRadiusFraction = brushRadiusFraction,
                                    isEraser = isEraser
                                )
                            )
                        }
                    }
                    .pointerInput(brushRadiusFraction, isEraser) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                activePoints = listOf(
                                    (start.x / w).coerceIn(0f, 1f) to (start.y / h).coerceIn(0f, 1f)
                                )
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nx = (change.position.x / w).coerceIn(0f, 1f)
                                val ny = (change.position.y / h).coerceIn(0f, 1f)
                                activePoints = activePoints + (nx to ny)
                            },
                            onDragEnd = {
                                if (activePoints.isNotEmpty()) {
                                    onAddStroke(
                                        MaskStroke(
                                            points = activePoints,
                                            brushRadiusFraction = brushRadiusFraction,
                                            isEraser = isEraser
                                        )
                                    )
                                }
                                activePoints = emptyList()
                            },
                            onDragCancel = {
                                activePoints = emptyList()
                            }
                        )
                    }
            ) {
                val minDim = min(size.width, size.height)

                fun drawMaskStroke(stroke: MaskStroke) {
                    if (stroke.points.isEmpty()) return
                    val strokePx = (stroke.brushRadiusFraction * minDim * 2f).coerceAtLeast(6f)
                    val blend = if (stroke.isEraser) BlendMode.Clear else BlendMode.SrcOver
                    val color = if (stroke.isEraser) Color.Transparent else maskTintColor

                    if (stroke.points.size == 1) {
                        val (nx, ny) = stroke.points.first()
                        drawCircle(
                            color = color,
                            radius = strokePx / 2f,
                            center = Offset(nx * size.width, ny * size.height),
                            blendMode = blend
                        )
                    } else {
                        val path = Path().apply {
                            val (sx, sy) = stroke.points.first()
                            moveTo(sx * size.width, sy * size.height)
                            for (i in 1 until stroke.points.size) {
                                val (px, py) = stroke.points[i]
                                lineTo(px * size.width, py * size.height)
                            }
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(
                                width = strokePx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            ),
                            blendMode = blend
                        )
                    }
                }

                strokes.forEach { drawMaskStroke(it) }
                if (activePoints.isNotEmpty()) {
                    drawMaskStroke(
                        MaskStroke(
                            points = activePoints,
                            brushRadiusFraction = brushRadiusFraction,
                            isEraser = isEraser
                        )
                    )
                }
            }

            // Floating stroke count badge
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = if (invertMask) {
                        stringResource(R.string.inpaint_mask_inverted_badge, strokes.size)
                    } else {
                        stringResource(R.string.inpaint_mask_strokes_badge, strokes.size)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Brush toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                FilterChip(
                    selected = !isEraser,
                    onClick = { isEraser = false },
                    leadingIcon = {
                        Icon(Icons.Rounded.Brush, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    label = { Text(stringResource(R.string.inpaint_tool_brush), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("inpaint_brush_chip")
                )
                FilterChip(
                    selected = isEraser,
                    onClick = { isEraser = true },
                    leadingIcon = {
                        Icon(Icons.Rounded.AutoFixOff, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    label = { Text(stringResource(R.string.inpaint_tool_eraser), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("inpaint_eraser_chip")
                )
                FilterChip(
                    selected = invertMask,
                    onClick = onToggleInvertMask,
                    leadingIcon = {
                        Icon(Icons.Rounded.InvertColors, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    label = { Text(stringResource(R.string.inpaint_tool_invert), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("inpaint_invert_chip")
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onUndoStroke,
                    enabled = strokes.isNotEmpty(),
                    modifier = Modifier.testTag("inpaint_undo_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Undo,
                        contentDescription = stringResource(R.string.inpaint_tool_undo)
                    )
                }
                IconButton(
                    onClick = onClearMask,
                    enabled = strokes.isNotEmpty(),
                    modifier = Modifier.testTag("inpaint_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = stringResource(R.string.inpaint_tool_clear)
                    )
                }
            }
        }

        // Brush size slider + live diameter preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                val dotDp = (6f + brushRadiusFraction * 120f).coerceIn(6f, 24f).dp
                Box(
                    modifier = Modifier
                        .size(dotDp)
                        .clip(CircleShape)
                        .background(if (isEraser) MaterialTheme.colorScheme.outline else maskTintColor)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.inpaint_brush_size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${(brushRadiusFraction * 512f).roundToInt()} px",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = brushRadiusFraction,
                    onValueChange = { brushRadiusFraction = it },
                    valueRange = 0.02f..0.16f
                )
            }
        }

        // Feather blur slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.inpaint_feather_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${featherRadiusPx} px",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = featherRadiusPx.toFloat(),
                onValueChange = { onUpdateFeather(it.roundToInt()) },
                valueRange = 0f..20f
            )
        }
    }
}
