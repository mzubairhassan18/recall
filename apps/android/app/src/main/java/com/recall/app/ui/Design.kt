package com.recall.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val GlassShape = RoundedCornerShape(26.dp)
private val ControlShape = RoundedCornerShape(16.dp)

@Composable
fun AuroraBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(RecallInk950, RecallInk900, RecallInk950),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val violetCenter = Offset(size.width * 0.12f, size.height * 0.02f)
            val cyanCenter = Offset(size.width * 0.96f, size.height * 0.06f)
            val mintCenter = Offset(size.width * 0.5f, size.height * 1.02f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(RecallViolet.copy(alpha = 0.30f), Color.Transparent),
                    center = violetCenter,
                    radius = size.width * 0.82f,
                ),
                radius = size.width * 0.82f,
                center = violetCenter,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(RecallCyan.copy(alpha = 0.17f), Color.Transparent),
                    center = cyanCenter,
                    radius = size.width * 0.70f,
                ),
                radius = size.width * 0.70f,
                center = cyanCenter,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(RecallMint.copy(alpha = 0.12f), Color.Transparent),
                    center = mintCenter,
                    radius = size.width * 0.90f,
                ),
                radius = size.width * 0.90f,
                center = mintCenter,
            )

            val step = 48.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(
                    color = Color.White.copy(alpha = 0.028f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height * 0.78f),
                    strokeWidth = 1f,
                )
                x += step
            }
            var y = 0f
            while (y <= size.height * 0.78f) {
                drawLine(
                    color = Color.White.copy(alpha = 0.028f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += step
            }
        }
        content()
    }
}

private fun recallRPath(): Path = Path().apply {
    moveTo(26f, 12f)
    lineTo(60f, 12f)
    quadraticTo(76f, 12f, 85f, 22.5f)
    quadraticTo(90f, 30f, 90f, 39f)
    quadraticTo(90f, 49f, 84.5f, 55.5f)
    quadraticTo(79f, 62f, 66f, 63.5f)
    lineTo(92f, 90f)
    lineTo(71.5f, 90f)
    lineTo(52f, 65f)
    lineTo(41f, 65f)
    lineTo(41f, 90f)
    lineTo(26f, 90f)
    lineTo(26f, 12f)
    close()
}

private fun recallWavePath(): Path = Path().apply {
    moveTo(-20f, 30f)
    quadraticTo(10f, 5f, 40f, 30f)
    quadraticTo(70f, 55f, 100f, 30f)
    quadraticTo(130f, 5f, 160f, 30f)
    lineTo(160f, 130f)
    lineTo(-20f, 130f)
    close()
}

@Composable
fun RecallMark(
    size: Dp,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "recall-mark")
    val tide by transition.animateFloat(
        initialValue = 110f,
        targetValue = 110f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5000
                110f at 0
                -15f at 1750
                -15f at 3000
                110f at 5000
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "mark-tide",
    )
    val shiver by transition.animateFloat(
        initialValue = -35f,
        targetValue = 35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mark-shiver",
    )
    val wash by transition.animateFloat(
        initialValue = 115f,
        targetValue = 115f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5000
                115f at 0
                -30f at 2000
                -30f at 3250
                115f at 5000
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "mark-wash",
    )
    val washShiver by transition.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mark-wash-shiver",
    )

    val rPath = remember { recallRPath() }
    val wavePath = remember { recallWavePath() }
    val markGradient = remember {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF6E56FF),
                Color(0xFF3B82F6),
                Color(0xFF06B6D4),
                Color(0xFFEC4899),
            ),
            start = Offset(0f, 0f),
            end = Offset(100f, 100f),
        )
    }
    val tideOffset = if (animated) tide else -15f
    val shiverOffset = if (animated) shiver else 0f
    val washOffset = if (animated) wash else 115f
    val washShiverOffset = if (animated) washShiver else 0f

    Canvas(modifier = modifier.size(size)) {
        val factor = this.size.minDimension / 100f
        scale(factor, factor, pivot = Offset.Zero) {
            drawPath(rPath, color = Color(0xFF3B2F86))
            clipPath(rPath) {
                translate(left = 0f, top = tideOffset) {
                    translate(left = shiverOffset, top = 0f) {
                        drawPath(wavePath, brush = markGradient)
                    }
                }
                translate(left = washOffset, top = 0f) {
                    translate(left = washShiverOffset, top = 0f) {
                        rotate(degrees = 90f, pivot = Offset(50f, 50f)) {
                            drawPath(wavePath, color = Color(0xFFEC4899).copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(GlassShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        RecallInk700.copy(alpha = 0.92f),
                        RecallInk900.copy(alpha = 0.90f),
                    ),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), GlassShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionEyebrow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = RecallCyan,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = RecallCyan,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
fun RecallTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    placeholder: String? = null,
    password: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        label = label?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        placeholder = placeholder?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RecallFaint,
                )
            }
        },
        leadingIcon = icon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = RecallFaint,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingIcon = trailingIcon,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = ControlShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RecallText,
            unfocusedTextColor = RecallText,
            disabledTextColor = RecallMuted,
            focusedContainerColor = RecallInk950.copy(alpha = 0.42f),
            unfocusedContainerColor = RecallInk950.copy(alpha = 0.32f),
            disabledContainerColor = RecallInk900.copy(alpha = 0.30f),
            focusedBorderColor = RecallCyan.copy(alpha = 0.60f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            disabledBorderColor = Color.White.copy(alpha = 0.06f),
            cursorColor = RecallCyan,
            focusedLabelColor = RecallCyan,
            unfocusedLabelColor = RecallMuted,
            focusedLeadingIconColor = RecallCyan,
            unfocusedLeadingIconColor = RecallFaint,
            focusedTrailingIconColor = RecallCyan,
            unfocusedTrailingIconColor = RecallFaint,
            focusedPlaceholderColor = RecallFaint,
            unfocusedPlaceholderColor = RecallFaint,
        ),
    )
}

@Composable
fun RecallPrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 54.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(RecallViolet, RecallCyan, RecallMint),
                ),
            )
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = RecallInk950,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = RecallInk950,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = RecallInk950,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
fun RecallIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = RecallMuted,
    size: Dp = 42.dp,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

@Composable
fun StatusChip(
    text: String,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(
                1.dp,
                if (online) RecallMint.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.10f),
                CircleShape,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(if (online) RecallMint else RecallFaint, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (online) Color(0xFFB7F7D5) else RecallMuted,
        )
    }
}

@Composable
fun InfoRow(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(RecallViolet.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = RecallCyan,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = RecallMuted,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

@Composable
fun LabelValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = RecallMuted)
        Text(value, style = MaterialTheme.typography.labelMedium, color = RecallText)
    }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color = RecallCyan,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
fun TinySpacer(width: Dp = 0.dp, height: Dp = 0.dp) {
    Spacer(Modifier.width(width).height(height))
}
