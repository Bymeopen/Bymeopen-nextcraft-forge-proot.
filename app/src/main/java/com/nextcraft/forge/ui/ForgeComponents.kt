package com.nextcraft.forge.ui

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Linear-style toggle cell: label and optional description on the left, switch on the right.
 * The whole row is the touch target (>= 56dp tall) and is announced as one switch by TalkBack.
 */
@Composable
fun ForgeToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        // onCheckedChange = null: the row's toggleable owns the click so the switch is not a second target.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary,
                checkedBorderColor = scheme.primary,
                uncheckedThumbColor = scheme.onSurfaceVariant,
                uncheckedTrackColor = scheme.surfaceVariant,
                uncheckedBorderColor = scheme.outline,
            ),
        )
    }
}

/**
 * True while the soft keyboard is showing. The window uses adjustResize (no edge-to-edge), so Compose
 * never sees an IME inset; the root view's insets are the reliable signal.
 */
@Composable
fun rememberImeVisible(): State<Boolean> {
    val view = LocalView.current
    val visible = remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            visible.value = ViewCompat.getRootWindowInsets(view)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return visible
}

/**
 * Where the system Back button goes from [tab]; null = let the system leave the app.
 * Tool screens return to the Tools launcher, top-level tabs return to Projects.
 */
fun forgeBackTarget(tab: ForgeTab): ForgeTab? = when (tab) {
    ForgeTab.PROJECTS -> null
    ForgeTab.FILES, ForgeTab.TERMINAL, ForgeTab.ANDROID, ForgeTab.PREVIEW -> ForgeTab.TOOLS
    ForgeTab.AGENT, ForgeTab.TOOLS, ForgeTab.SETTINGS -> ForgeTab.PROJECTS
}

/** Large left-aligned screen title (Linear style) with an optional quiet subtitle. */
@Composable
fun ForgeLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalPadding: Dp = 20.dp,
) {
    Column(modifier = modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 8.dp, bottom = 2.dp)) {
        Text(text = title, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(text = subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Quiet section label that groups the cards/rows below it. */
@Composable
fun ForgeSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 10.dp),
    )
}
