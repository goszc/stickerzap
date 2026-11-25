package com.stickerzap.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stickerzap.app.R
import com.stickerzap.app.media.HandoffExport
import com.stickerzap.app.media.WhatsAppShare

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundGuideSheet(
    export: HandoffExport,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.guide_title),
                fontWeight = FontWeight.Bold
            )
            Text(stringResource(R.string.guide_saved_to))
            Text(stringResource(R.string.guide_step1))
            Text(stringResource(R.string.guide_step2, export.displayName))
            Text(stringResource(R.string.guide_step3))
            if (export.clampedTo5s) Text(stringResource(R.string.guide_clamped_5s))
            if (export.replacedAudio) Text(stringResource(R.string.guide_replaced_audio))
            Text(stringResource(R.string.guide_note))
            Text(stringResource(R.string.guide_api))
            Button(
                onClick = {
                    if (!WhatsAppShare.openWhatsApp(context)) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.no_whatsapp),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.open_whatsapp)) }
            if (export.videoExportFile.exists()) {
                OutlinedButton(
                    onClick = { WhatsAppShare.shareVideoChooser(context, export.videoExportFile) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.share_video)) }
            }
            export.audioShareFile?.let { audio ->
                OutlinedButton(
                    onClick = { WhatsAppShare.shareAudioToWhatsApp(context, audio) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.share_audio)) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
