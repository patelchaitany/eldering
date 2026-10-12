package com.eldering.safety.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eldering.safety.R
import com.eldering.safety.ui.components.DesignButton
import com.eldering.safety.ui.components.DesignButtons
import com.eldering.safety.ui.components.ElderingIcons
import com.eldering.safety.ui.theme.Eldering
import com.eldering.safety.ui.theme.ElderingTheme

@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onLogIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Eldering.palette.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Eldering.palette.safe),
            ) {
                Icon(ElderingIcons.ShieldCheck, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = Eldering.type.screenTitle,
                    color = Eldering.palette.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.welcome_tagline), style = Eldering.type.body, color = Eldering.palette.inkMuted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WelcomePoint(ElderingIcons.Activity, stringResource(R.string.welcome_point_detect), Eldering.palette.alert)
                WelcomePoint(ElderingIcons.MapPin, stringResource(R.string.welcome_point_alert), Eldering.palette.me)
                WelcomePoint(ElderingIcons.Shield, stringResource(R.string.welcome_point_privacy), Eldering.palette.safe)
            }
        }
        DesignButton(
            text = stringResource(R.string.welcome_create_account),
            onClick = onCreateAccount,
            height = 56.dp,
            textStyle = Eldering.type.buttonLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        DesignButton(
            text = stringResource(R.string.welcome_log_in),
            onClick = onLogIn,
            colors = DesignButtons.outline(),
            height = 56.dp,
            textStyle = Eldering.type.buttonLarge,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WelcomePoint(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Eldering.palette.surface),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(text, style = Eldering.type.bodyStrong, color = Eldering.palette.ink, modifier = Modifier.weight(1f))
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun WelcomePreview() {
    ElderingTheme { WelcomeScreen({}, {}) }
}
