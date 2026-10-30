package com.droidlink.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// From the logo: violet and magenta accents over a deep purple→blue backdrop.
internal val Indigo = Color(0xFF895AFF)
internal val Cyan = Color(0xFFF36BFF)
internal val Green = Color(0xFF33D67A)
/** The soft violet glow behind the hero icon. */
internal val Glow = Color(0xFFC981FF)
/** The "Connected" pill, as on the Mac. */
internal val ConnectedTint = Color(0xFFEE90FF)
internal val DeepBlue = Color(0xFF084B7B)
internal val AccentBrush = Brush.linearGradient(listOf(Indigo, Cyan))
/** Night versions of the logo's purple and blue behind the UI, so text stays readable. */
internal val BackdropBrush = Brush.verticalGradient(listOf(Color(0xFF170B2E), Color(0xFF041E33)))
/** Glass: a faint white tint and a light edge over the backdrop. */
internal val GlassFill = Color.White.copy(alpha = 0.06f)
internal val GlassEdge = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f), Cyan.copy(alpha = 0.20f)))
internal val Secondary = Color.White.copy(alpha = 0.6f)

/** One thing AyPhone needs from Android, for the onboarding steps and the Setup list. */
internal data class SetupStep(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val done: Boolean,
    val optional: Boolean = false,
    val action: () -> Unit,
)

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 10.dp))
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, radius: Int = 22, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.dp))
            .background(GlassFill)
            .border(1.dp, GlassEdge, RoundedCornerShape(radius.dp))
            .padding(padding.dp),
        content = content
    )
}

/** A small status pill, as on the Mac ("Connected", "Wi-Fi"). */
@Composable
internal fun Pill(text: String, icon: ImageVector, tint: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, color = tint, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** The big gradient button (Pair with Mac, Continue…); its icon gives a little when pressed. */
@Composable
internal fun PrimaryButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    val iconScale by animateFloatAsState(if (pressed) 0.8f else 1f, spring(Spring.DampingRatioHighBouncy), label = "icon")
    Row(
        modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(18.dp))
            .background(AccentBrush)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.scale(iconScale))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** A setup step in the Setup list: a check that pops in once it's done, or Allow. */
@Composable
internal fun SetupRow(step: SetupStep, last: Boolean) {
    val tint by animateColorAsState(if (step.done) Cyan else MaterialTheme.colorScheme.outline, label = "check")
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !step.done) { step.action() }
                .padding(vertical = 12.dp)
        ) {
            AnimatedContent(
                targetState = step.done,
                transitionSpec = {
                    (scaleIn(spring(Spring.DampingRatioMediumBouncy), initialScale = 0.4f) + fadeIn()) togetherWith
                        (scaleOut(targetScale = 0.6f) + fadeOut())
                },
                label = "check"
            ) { isDone ->
                Icon(if (isDone) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = tint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(step.subtitle, style = MaterialTheme.typography.bodySmall, color = Secondary)
            }
            if (!step.done) Text("Allow", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
    }
}
