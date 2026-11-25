package com.stickerzap.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.stickerzap.app.data.StickerItem
import com.stickerzap.app.data.StickerPack
import com.stickerzap.app.data.StickerRepository
import com.stickerzap.app.media.AudioHelper
import com.stickerzap.app.media.StickerProcessor
import com.stickerzap.app.provider.StickerPackContract
import com.stickerzap.app.provider.WhatsAppIntegrator
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00A884),
                    background = Color(0xFF0B141A),
                    surface = Color(0xFF111B21),
                    surfaceVariant = Color(0xFF202C33)
                )
            ) {
                StickerZapApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerZapApp() {
    val context = LocalContext.current
    val repo = remember { StickerRepository(context) }
    val audioHelper = remember { AudioHelper(context) }

    var packs by remember { mutableStateOf(repo.loadPacks()) }
    var selectedPackId by remember { mutableStateOf(packs.firstOrNull()?.identifier ?: "pack_memes_br") }
    var currentTab by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var waErrorDialog by remember { mutableStateOf<String?>(null) }
    val chatMessages = remember { mutableStateListOf<Pair<String, StickerItem?>>() }

    fun showMessage(msg: String, asDialog: Boolean = false) {
        statusMessage = msg
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        if (asDialog) waErrorDialog = msg
    }

    val addPackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val msg = WhatsAppIntegrator.messageForResult(result.resultCode, result.data)
        if (msg != null) {
            showMessage(msg, asDialog = WhatsAppIntegrator.isValidationError(result.data))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioHelper.stopRecording()
            audioHelper.stopPlayback()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("StickerZap", fontWeight = FontWeight.Bold)
                        Text("Figurinhas + Som + WhatsApp", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8696A0))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF111B21))
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111B21)) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                    label = { Text("Criar") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
                    label = { Text("Álbuns") }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text("Preview") }
                )
            }
        },
        snackbarHost = {
            statusMessage?.let { msg ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = { TextButton(onClick = { statusMessage = null }) { Text("OK") } }
                ) { Text(msg) }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF0B141A))) {
            when (currentTab) {
                0 -> StudioScreen(
                    packs = packs,
                    selectedPackId = selectedPackId,
                    onSelectPack = { selectedPackId = it },
                    audioHelper = audioHelper,
                    onStickerSaved = { packId, item ->
                        packs = packs.map {
                            if (it.identifier == packId) {
                                val nextVersion = (it.imageDataVersion.toIntOrNull() ?: 1) + 1
                                it.copy(
                                    stickers = it.stickers + item,
                                    imageDataVersion = nextVersion.toString()
                                )
                            } else it
                        }
                        repo.savePacks(packs)
                        statusMessage = "Figurinha \"${item.name}\" salva!"
                    },
                    onError = { statusMessage = it }
                )
                1 -> AlbumsScreen(
                    packs = packs,
                    selectedPackId = selectedPackId,
                    repo = repo,
                    audioHelper = audioHelper,
                    onSelectPack = { selectedPackId = it },
                    onCreatePack = { name, publisher ->
                        val newPack = StickerPack(
                            identifier = "pack_${System.currentTimeMillis()}",
                            name = StickerPackContract.clampName(name, "Álbum"),
                            publisher = StickerPackContract.clampName(publisher, "Você")
                        )
                        packs = packs + newPack
                        selectedPackId = newPack.identifier
                        repo.savePacks(packs)
                    },
                    onAddToWhatsApp = { pack ->
                        when (val request = WhatsAppIntegrator.prepareAdd(context, pack, repo)) {
                            is WhatsAppIntegrator.AddPackRequest.Error -> {
                                showMessage(request.message, asDialog = true)
                            }
                            is WhatsAppIntegrator.AddPackRequest.Launch -> {
                                try {
                                    addPackLauncher.launch(request.intent)
                                } catch (_: ActivityNotFoundException) {
                                    showMessage(
                                        "WhatsApp não encontrado ou desatualizado. Atualize o WhatsApp e tente de novo.",
                                        asDialog = true
                                    )
                                }
                            }
                        }
                    },
                    onTestInPreview = { item ->
                        chatMessages.add("Figurinha enviada" to item)
                        currentTab = 2
                    }
                )
                2 -> PreviewScreen(
                    packs = packs,
                    selectedPackId = selectedPackId,
                    repo = repo,
                    audioHelper = audioHelper,
                    messages = chatMessages,
                    onSendSticker = { item -> chatMessages.add("Figurinha enviada" to item) }
                )
            }
        }
    }

    waErrorDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { waErrorDialog = null },
            title = { Text("Não foi possível adicionar ao WhatsApp") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { waErrorDialog = null }) { Text("OK") }
            }
        )
    }
}

@Composable
fun StudioScreen(
    packs: List<StickerPack>,
    selectedPackId: String,
    onSelectPack: (String) -> Unit,
    audioHelper: AudioHelper,
    onStickerSaved: (String, StickerItem) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { StickerRepository(context) }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var stickerName by remember { mutableStateOf("Minha Figurinha") }
    var topText by remember { mutableStateOf("") }
    var bottomText by remember { mutableStateOf("") }
    var mask by remember { mutableStateOf(StickerProcessor.MaskType.ORIGINAL) }
    var isRecording by remember { mutableStateOf(false) }
    var pendingAudioFile by remember { mutableStateOf<File?>(null) }
    var pendingAudioLabel by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) selectedImageUri = uri
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val tmp = File(context.cacheDir, "tmp_audio_${System.currentTimeMillis()}.m4a")
            if (audioHelper.copyAudioUri(uri, tmp)) {
                pendingAudioFile = tmp
                pendingAudioLabel = "Áudio importado"
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onError("Permissão de microfone necessária para gravar voz.")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Estúdio 512×512", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF202C33)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImageUri != null) {
                            AsyncImage(model = selectedImageUri, contentDescription = null, modifier = Modifier.size(180.dp))
                        } else {
                            Text("Toque abaixo para escolher uma imagem", color = Color(0xFF8696A0))
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { imagePicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Escolher Foto")
                        }
                    }

                    OutlinedTextField(
                        value = stickerName,
                        onValueChange = { stickerName = it },
                        label = { Text("Nome da figurinha") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = topText,
                            onValueChange = { topText = it },
                            label = { Text("Texto topo") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = bottomText,
                            onValueChange = { bottomText = it },
                            label = { Text("Texto rodapé") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Recorte", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StickerProcessor.MaskType.entries) { m ->
                            FilterChip(
                                selected = mask == m,
                                onClick = { mask = m },
                                label = { Text(m.name) }
                            )
                        }
                    }

                    Text("Áudio (Figurinha com Som)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                if (!hasPerm) {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@OutlinedButton
                                }
                                if (!isRecording) {
                                    val recFile = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
                                    audioHelper.startRecording(recFile)
                                    pendingAudioFile = recFile
                                    pendingAudioLabel = "Voz gravada"
                                    isRecording = true
                                } else {
                                    audioHelper.stopRecording()
                                    isRecording = false
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(if (isRecording) "Parar" else "Gravar")
                        }
                        OutlinedButton(onClick = { audioPicker.launch("audio/*") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.MusicNote, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Importar Som")
                        }
                    }

                    pendingAudioFile?.let { f ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { audioHelper.playFile(f) },
                                label = { Text("Ouvir: ${pendingAudioLabel ?: "Áudio"}") },
                                leadingIcon = { Icon(Icons.Default.VolumeUp, contentDescription = null) }
                            )
                            TextButton(onClick = { pendingAudioFile = null; pendingAudioLabel = null }) { Text("Remover") }
                        }
                    }

                    Text("Álbum de destino", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(packs) { p ->
                            FilterChip(
                                selected = p.identifier == selectedPackId,
                                onClick = { onSelectPack(p.identifier) },
                                label = { Text(p.name) }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val uri = selectedImageUri
                            if (uri == null) {
                                onError("Escolha uma imagem primeiro.")
                                return@Button
                            }
                            val bmp = StickerProcessor.createStickerBitmap(context, uri, mask, topText, bottomText)
                            if (bmp == null) {
                                onError("Falha ao processar imagem.")
                                return@Button
                            }
                            val packDir = repo.packDir(selectedPackId)
                            val id = UUID.randomUUID().toString().take(8)
                            val imgName = "stk_$id.webp"
                            try {
                                StickerProcessor.saveWebpUnder100Kb(bmp, File(packDir, imgName))
                                val trayFile = File(packDir, "tray.png")
                                if (!trayFile.exists()) {
                                    StickerProcessor.saveTrayIcon96(bmp, trayFile)
                                }
                            } catch (e: Exception) {
                                onError(e.message ?: "Falha ao salvar a figurinha.")
                                return@Button
                            }

                            var soundName: String? = null
                            pendingAudioFile?.let { src ->
                                if (src.exists()) {
                                    soundName = "snd_$id.m4a"
                                    src.copyTo(File(packDir, soundName!!), overwrite = true)
                                }
                            }

                            onStickerSaved(
                                selectedPackId,
                                StickerItem(
                                    id = id,
                                    name = stickerName.ifBlank { "Figurinha" },
                                    imageFileName = imgName,
                                    emojis = StickerPackContract.sanitizeEmojis(listOf("😄")),
                                    soundFileName = soundName,
                                    soundLabel = pendingAudioLabel
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Salvar Figurinha no Álbum")
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(
    packs: List<StickerPack>,
    selectedPackId: String,
    repo: StickerRepository,
    audioHelper: AudioHelper,
    onSelectPack: (String) -> Unit,
    onCreatePack: (String, String) -> Unit,
    onAddToWhatsApp: (StickerPack) -> Unit,
    onTestInPreview: (StickerItem) -> Unit
) {
    var showNewDialog by remember { mutableStateOf(false) }
    var newPackName by remember { mutableStateOf("") }
    var newPackAuthor by remember { mutableStateOf("Você") }

    val activePack = packs.firstOrNull { it.identifier == selectedPackId } ?: packs.firstOrNull()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Seus Álbuns", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = { showNewDialog = true }) { Text("+ Novo Álbum") }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(packs) { p ->
                FilterChip(
                    selected = p.identifier == activePack?.identifier,
                    onClick = { onSelectPack(p.identifier) },
                    label = { Text("${p.name} (${p.stickers.size})") }
                )
            }
        }

        activePack?.let { pack ->
            Button(
                onClick = { onAddToWhatsApp(pack) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366), contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Adicionar Álbum ao WhatsApp (mín. 3)", fontWeight = FontWeight.Bold)
            }

            LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pack.stickers) { item ->
                    val imgFile = File(repo.packDir(pack.identifier), item.imageFileName)
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21))) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                AsyncImage(
                                    model = imgFile,
                                    contentDescription = item.name,
                                    modifier = Modifier.size(84.dp).clickable { onTestInPreview(item) }
                                )
                                if (item.soundFileName != null) {
                                    IconButton(
                                        onClick = {
                                            audioHelper.playFile(File(repo.packDir(pack.identifier), item.soundFileName))
                                        },
                                        modifier = Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(Color(0xCC000000))
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = "Ouvir", tint = Color(0xFF25D366), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            Text(item.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("Novo Álbum") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newPackName, onValueChange = { newPackName = it }, label = { Text("Nome do álbum") })
                    OutlinedTextField(value = newPackAuthor, onValueChange = { newPackAuthor = it }, label = { Text("Autor") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPackName.isNotBlank()) {
                        onCreatePack(newPackName.trim(), newPackAuthor.ifBlank { "Você" })
                        newPackName = ""
                        showNewDialog = false
                    }
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun PreviewScreen(
    packs: List<StickerPack>,
    selectedPackId: String,
    repo: StickerRepository,
    audioHelper: AudioHelper,
    messages: List<Pair<String, StickerItem?>>,
    onSendSticker: (StickerItem) -> Unit
) {
    val activePack = packs.firstOrNull { it.identifier == selectedPackId } ?: packs.firstOrNull()
    var playingId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages) { (_, sticker) ->
                if (sticker != null && activePack != null) {
                    val imgFile = File(repo.packDir(activePack.identifier), sticker.imageFileName)
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Box(modifier = Modifier.size(140.dp)) {
                            AsyncImage(model = imgFile, contentDescription = sticker.name, modifier = Modifier.fillMaxSize())
                            if (sticker.soundFileName != null) {
                                Surface(
                                    onClick = {
                                        playingId = sticker.id
                                        audioHelper.playFile(File(repo.packDir(activePack.identifier), sticker.soundFileName)) {
                                            playingId = null
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = if (playingId == sticker.id) Color(0xFF25D366) else Color(0xCC000000),
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (playingId == sticker.id) Color.Black else Color(0xFF25D366))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (playingId == sticker.id) "Tocando" else "Ouvir", style = MaterialTheme.typography.labelSmall, color = if (playingId == sticker.id) Color.Black else Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sticker tray
        Surface(color = Color(0xFF111B21)) {
            LazyRow(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val stickers = activePack?.stickers ?: emptyList()
                items(stickers) { s ->
                    val imgFile = File(repo.packDir(activePack!!.identifier), s.imageFileName)
                    AsyncImage(
                        model = imgFile,
                        contentDescription = s.name,
                        modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF202C33)).clickable { onSendSticker(s) }.padding(4.dp)
                    )
                }
            }
        }
    }
}
