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
                Text("‹  Back", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text("Scrib", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = cs.onBackground)
            Text(
                "On-device speech-to-text. Your audio never leaves your phone.",
                fontSize = 14.sp, color = cs.onSurfaceVariant, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp)
            )

            Heading("Two ways to use Scrib")
            SubHeading("Transcribe your own audio")
            Body("Pick any audio file on your phone and get the text — right here, fully offline.")
            SubHeading("A transcription engine for other apps")
            Body(
                "Scrib also works in the background. Apps that support it — like Forkgram — can turn a voice " +
                    "message into text using Scrib, on your device. In that app's settings, choose Scrib as the " +
                    "offline transcriber. Nothing is uploaded."
            )

            Spacer(Modifier.height(22.dp))
            Heading("Models")
            Body(
                "Scrib transcribes with open Whisper speech models. Download one on the main screen, or pick " +
                    "your language and Scrib fetches a fitting one. Bigger models are more accurate; keep several " +
                    "and switch anytime."
            )

            Spacer(Modifier.height(22.dp))
            Heading("For developers")
            Body(
                "Any app can use Scrib as an offline transcriber through the open Open Transcribe contract " +
                    "(org.opentranscribe.api) — a small AIDL service, no SDK and no network. The contract is " +
                    "vendor-neutral, so apps aren't tied to Scrib: they can bind any compatible transcriber the " +
                    "user has installed."
            )

            Spacer(Modifier.height(28.dp))
            Text("Version 0.1", fontSize = 12.sp, color = cs.onSurfaceVariant)
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
