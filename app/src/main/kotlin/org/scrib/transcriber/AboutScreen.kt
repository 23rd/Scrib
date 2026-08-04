package org.scrib.transcriber

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹  " + stringResource(R.string.about_back), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text("Scrib", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground)
            Text(
                stringResource(R.string.about_tagline),
                fontSize = 14.sp, color = cs.onSurfaceVariant, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
            )

            Heading(stringResource(R.string.about_two_ways))
            SubHeading(stringResource(R.string.about_own_h))
            Body(stringResource(R.string.about_own_b))
            SubHeading(stringResource(R.string.about_engine_h))
            Body(stringResource(R.string.about_engine_b))
            SubHeading(stringResource(R.string.about_keyboard_h))
            Body(stringResource(R.string.about_keyboard_b))

            Spacer(Modifier.height(22.dp))
            Heading(stringResource(R.string.about_models_h))
            Body(stringResource(R.string.about_models_b))

            Spacer(Modifier.height(22.dp))
            Heading(stringResource(R.string.about_dev_h))
            Body(stringResource(R.string.about_dev_b))

            Spacer(Modifier.height(28.dp))
            Text(stringResource(R.string.about_version), fontSize = 12.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text, fontSize = 14.sp, lineHeight = 21.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)
    )
}
