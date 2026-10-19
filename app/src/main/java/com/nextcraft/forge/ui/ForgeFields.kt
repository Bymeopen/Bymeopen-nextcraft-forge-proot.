package com.nextcraft.forge.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.error as semanticsError
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.net.URI

/**
 * What a field holds. The kind picks the soft keyboard (URL keys, digits, password keyboard without
 * suggestions) and whether autocorrect and auto-capitalisation help or hurt.
 */
enum class ForgeFieldKind(
    val keyboardType: KeyboardType,
    val capitalization: KeyboardCapitalization,
    val autoCorrect: Boolean,
) {
    /** Names of things people read: project, label, snapshot. */
    NAME(KeyboardType.Text, KeyboardCapitalization.Words, true),

    /** Free prose. */
    TEXT(KeyboardType.Text, KeyboardCapitalization.Sentences, true),

    /** Model ids, branches, paths, hosts, commands, codes: exact strings that autocorrect would corrupt. */
    IDENTIFIER(KeyboardType.Text, KeyboardCapitalization.None, false),

    URL(KeyboardType.Uri, KeyboardCapitalization.None, false),
    EMAIL(KeyboardType.Email, KeyboardCapitalization.None, false),
    NUMBER(KeyboardType.Number, KeyboardCapitalization.None, false),

    /** API keys and tokens: masked, and the password keyboard stops the IME from learning them. */
    SECRET(KeyboardType.Password, KeyboardCapitalization.None, false),
    ;

    fun keyboardOptions(imeAction: ImeAction = ImeAction.Done) = KeyboardOptions(
        capitalization = capitalization,
        autoCorrect = autoCorrect,
        keyboardType = keyboardType,
        imeAction = imeAction,
    )
}

/** Pure checks shared by the screens (and unit-tested without Android). */
object FieldValidation {
    /** True for an absolute http(s) URL with a host, which is all the clone, API and manifest fields accept. */
    fun isHttpUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    fun isPort(raw: String): Boolean = raw.toIntOrNull()?.let { it in 1..65535 } == true
}

/**
 * Alpha of onSurfaceVariant that keeps a control's resting border at >= 3:1 against every surface of the
 * theme (WCAG 1.4.11). Measured per theme; ContrastTest keeps it honest.
 */
internal fun controlBorderAlpha(tokens: ForgeThemeTokens): Float = when {
    tokens.isGlass -> 0.45f
    tokens.isDark -> 0.60f
    else -> 0.75f
}

/** Resting border of inputs and switches: visible, unlike the decorative `outline` of cards. */
@Composable
fun forgeControlBorder(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = controlBorderAlpha(LocalForgeThemeTokens.current))

/**
 * The one outlined text field of the app: 14dp corners, a keyboard that matches [kind], an IME action
 * that does something, and inline validation.
 *
 * [error] is shown (red outline, supporting text, TalkBack error) only after the field has been left once,
 * so people are not scolded mid-typing; it then updates live. Pass null when the value is fine.
 * [imeAction] Next moves focus down; Done/Search/Go/Send call [onImeAction] or just close the keyboard.
 */
@Composable
fun ForgeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    kind: ForgeFieldKind = ForgeFieldKind.TEXT,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val focusManager = LocalFocusManager.current
    var touched by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val shownError = error?.takeIf { touched }
    val finish = onImeAction ?: { focusManager.clearFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged {
                if (hadFocus && !it.isFocused) touched = true
                hadFocus = it.isFocused
            }
            .semantics { if (shownError != null) semanticsError(shownError) },
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        textStyle = textStyle,
        singleLine = singleLine,
        isError = shownError != null,
        supportingText = shownError?.let { message -> { Text(message) } },
        visualTransformation = if (kind == ForgeFieldKind.SECRET) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = kind.keyboardOptions(imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
            onDone = { finish() },
            onGo = { finish() },
            onSearch = { finish() },
            onSend = { finish() },
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = forgeControlBorder()),
    )
}
