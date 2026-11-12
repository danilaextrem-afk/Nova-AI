package ai.nova.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File

private val ART_STYLES = listOf("anime" to "Аниме", "realistic" to "Реализм", "fantasy" to "Фэнтези", "portrait" to "Портрет", "cinematic" to "Кино")
private val ART_IDEAS = listOf(
    "Девушка-маг с серебряными волосами в ночном неоновом городе",
    "Уютная кофейня в дождливом Токио, вид из окна",
    "Рыцарь в золотых доспехах на краю облачного замка",
    "Космический корабль над фиолетовой планетой",
)

/** AI Art studio: describe a picture, pick a style, get an image that stays on this phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtScreen(model: NovaViewModel) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<File?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    NovaEyebrow("AI АРТ")
    Text("Нарисуй сцену\nиз своей истории", style = MaterialTheme.typography.headlineLarge)
    val status = model.artStatus
    Text(
        when {
            status == null -> "Загружаю…"
            !status.configured -> "Рисование пока недоступно на сервере."
            status.freeLeft > 0 -> "Бесплатно сегодня: ${status.freeLeft} из ${status.freePerDay}. Картинки сохраняются только на твоём телефоне."
            else -> "Бесплатные картинки на сегодня закончились. Следующая — ${status.priceTokens} ◈."
        }, color = NovaDesign.Muted, style = MaterialTheme.typography.bodySmall
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ART_STYLES.forEach { (id, label) -> FilterChip(selected = model.artStyle == id, onClick = { model.artStyle = id }, label = { Text(label) }) }
    }
    OutlinedTextField(
        value = model.artPrompt, onValueChange = { model.artPrompt = it.take(600) },
        label = { Text("Что нарисовать?") }, placeholder = { Text("Например: героиня у окна поезда на закате") },
        minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() })
    )
    val paid = status != null && status.freeLeft == 0
    Button(
        onClick = { keyboard?.hide(); model.generateArt() },
        enabled = !model.loading && status?.configured == true && model.artPrompt.trim().length >= 3,
        modifier = Modifier.fillMaxWidth().height(54.dp)
    ) { Text(if (paid) "Нарисовать · ${status!!.priceTokens} ◈" else "Нарисовать бесплатно") }
    if (model.loading && model.activeTab == NovaTab.ART && status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Рисую… обычно 10–30 секунд", color = NovaDesign.Muted)
    }
    if (model.artPrompt.isBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ART_IDEAS.forEach { idea -> AssistChip(onClick = { model.artPrompt = idea }, label = { Text(idea, maxLines = 1) }) }
    }
    Text("Нельзя: обнажённая натура, дети, реальные люди, жестокость. Такие запросы отклоняются без списания токенов.",
        style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)

    model.artLatest?.let { file ->
        NovaCard {
            NovaEyebrow("ГОТОВО")
            AsyncImage(model = file, contentDescription = "Созданная картинка", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)))
            ArtActions(context, model, file)
        }
    }
    if (model.artGallery.isNotEmpty()) {
        Text("Мои картинки", style = MaterialTheme.typography.titleLarge)
        model.artGallery.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { f ->
                    Surface(onClick = { open = f }, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, NovaDesign.Border),
                        modifier = Modifier.weight(1f).aspectRatio(1f)) {
                        AsyncImage(model = f, contentDescription = "Картинка из галереи", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    open?.let { f ->
        AlertDialog(onDismissRequest = { open = null }, confirmButton = { TextButton(onClick = { open = null }) { Text("Закрыть") } },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(model = f, contentDescription = "Картинка", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    ArtActions(context, model, f) { open = null }
                }
            })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtActions(context: Context, model: NovaViewModel, file: File, onDeleted: () -> Unit = {}) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (Build.VERSION.SDK_INT >= 29) OutlinedButton(onClick = {
            model.showNotice(if (saveToGallery(context, file)) "Сохранено в галерею (Pictures/NOVA AI)" else "Не удалось сохранить")
        }) { Text("В галерею") }
        OutlinedButton(onClick = { shareArt(context, file) }) { Text("Поделиться") }
        TextButton(onClick = { model.deleteArt(file); onDeleted() }) { Text("Удалить") }
    }
}

private fun saveToGallery(context: Context, file: File): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "NOVA-" + file.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NOVA AI")
    }
    val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    true
}.getOrDefault(false)

private fun shareArt(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Поделиться картинкой"))
    }
}
