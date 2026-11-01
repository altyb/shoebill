package dev.altayeb.shoebill.vault

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.altayeb.shoebill.R
import androidx.compose.ui.res.stringResource
import dev.altayeb.shoebill.ui.BrandLoader
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp

private const val PIN_MIN = 4
private const val PIN_MAX = 16

private val ShakeSpec = keyframes<Float> {
    durationMillis = 400
    0f at 0
    -14f at 60
    14f at 140
    -10f at 220
    8f at 300
    0f at 400
}

/**
 * Full-screen numeric PIN entry for the vault. Digits live in a CharArray: zeroed on submit, on error, and on leave.
 * [lockedUntil] (epoch ms) disables the keypad and shows a countdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinPad(
    title: String,
    error: String?,
    busy: Boolean,
    lockedUntil: Long?,
    onSubmit: (CharArray) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val buf = remember { CharArray(PIN_MAX) }
    var length by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(buf) { onDispose { buf.fill('\u0000') } }

    fun clearEntry() {
        buf.fill('\u0000')
        length = 0
    }

    LaunchedEffect(error) {
        if (error == null) return@LaunchedEffect
        clearEntry()
        shake.animateTo(0f, ShakeSpec)
    }

    LaunchedEffect(lockedUntil) {
        val until = lockedUntil ?: return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= until) break
            delay(1000)
        }
    }
    val waitSec = lockedUntil?.let { ((it - nowMs + 999) / 1000).coerceAtLeast(0) }
    val locked = waitSec != null && waitSec > 0
    val enabled = !busy && !locked

    fun press(d: Char) {
        if (!enabled || length >= PIN_MAX) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        buf[length] = d
        length++
    }

    fun backspace() {
        if (!enabled || length == 0) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        length--
        buf[length] = '\u0000'
    }

    fun submit() {
        if (!enabled || length < PIN_MIN) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val pin = buf.copyOf(length)
        clearEntry()
        onSubmit(pin)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onBack != null) {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        }
        // Portrait: everything stacked. Landscape (e.g. after a video): header left, keypad right; keys shrink to fit.
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            val wide = maxWidth > maxHeight
            val keySize = minOf(72.dp, ((if (wide) maxHeight else maxHeight * 0.55f) - 12.dp * 3) / 4)
            val header: @Composable () -> Unit = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_monochrome),
                            contentDescription = null,
                            modifier = Modifier.requiredSize(72.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Box(
                        Modifier.height(48.dp).offset { IntOffset(shake.value.roundToInt(), 0) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) {
                            BrandLoader(size = 44.dp)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(maxOf(PIN_MIN, length)) { PinDot(filled = it < length) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (locked) stringResource(R.string.vault_try_again_in, waitSec ?: 0L) else error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.defaultMinSize(minHeight = 24.dp),
                    )
                }
            }
            val keypad: @Composable () -> Unit = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("123", "456", "789").forEach { digits ->
                            Row(horizontalArrangement = Arrangement.spacedBy(keySize / 4)) {
                                digits.forEach { d ->
                                    PinKey(keySize, enabled = enabled, onClick = { press(d) }) {
                                        Text(d.toString(), style = MaterialTheme.typography.headlineSmall)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(keySize / 4)) {
                            PinKey(keySize, enabled = enabled && length > 0, onClick = { backspace() }) {
                                Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.delete))
                            }
                            PinKey(keySize, enabled = enabled, onClick = { press('0') }) {
                                Text("0", style = MaterialTheme.typography.headlineSmall)
                            }
                            PinKey(keySize, enabled = enabled && length >= PIN_MIN, onClick = { submit() }) {
                                Icon(Icons.Rounded.Check, stringResource(R.string.confirm))
                            }
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    header()
                    keypad()
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    header()
                    Spacer(Modifier.height(20.dp))
                    keypad()
                }
            }
        }
    }
}

@Composable
private fun PinDot(filled: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (filled) 1f else 0.6f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow),
        label = "pinDot",
    )
    Box(
        Modifier
            .size(14.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(
                if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
    )
}

@Composable
private fun PinKey(size: Dp, enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
