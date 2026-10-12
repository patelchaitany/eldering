package com.eldering.safety.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eldering.safety.ui.theme.Eldering

/** Inline problem notice with an optional retry; announced politely by TalkBack. */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Eldering.palette.alertContainer,
        border = BorderStroke(1.dp, Eldering.palette.alert.copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(ElderingIcons.Warning, contentDescription = null, tint = Eldering.palette.alert, modifier = Modifier.size(18.dp))
            Text(
                message,
                style = Eldering.type.bodySmall,
                color = Eldering.palette.alertText,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            if (actionLabel != null && onAction != null) {
                TextLinkButton(text = actionLabel, onClick = onAction, color = Eldering.palette.alertText)
            }
        }
    }
}

/** Green confirmation notice ("Marked as handled — family has been told"). */
@Composable
fun SuccessBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Eldering.palette.safeContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            message,
            style = Eldering.type.bodySmallStrong,
            color = Eldering.palette.safeText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        CircularProgressIndicator(color = Eldering.palette.safe)
    }
}

/** Centered explanation for empty or unavailable content. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Eldering.palette.inkMuted, modifier = Modifier.size(32.dp))
        }
        Text(title, style = Eldering.type.cardTitle, color = Eldering.palette.ink, textAlign = TextAlign.Center)
        Text(body, style = Eldering.type.bodySmall, color = Eldering.palette.inkMuted, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** Labelled text field in the design's colors; [error] is shown below and exposed to TalkBack. */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        enabled = enabled,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(14.dp),
        textStyle = Eldering.type.body,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Eldering.palette.ink,
            unfocusedBorderColor = Eldering.palette.hairline,
            focusedLabelColor = Eldering.palette.ink,
            cursorColor = Eldering.palette.ink,
            focusedContainerColor = Eldering.palette.surface,
            unfocusedContainerColor = Eldering.palette.surface,
            errorContainerColor = Eldering.palette.surface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .then(if (error != null) Modifier.semantics { error(error) } else Modifier),
    )
}
