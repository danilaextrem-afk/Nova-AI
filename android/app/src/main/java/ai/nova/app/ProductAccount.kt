package ai.nova.app

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun WalletScreen(model: NovaViewModel) {
    TextButton(onClick = { model.changeTab(model.productReturnTab) }) {
        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Назад")
    }
    Text("NOVA Tokens", style = MaterialTheme.typography.headlineMedium)
    val wallet = model.wallet
    NovaCard {
        Text("Баланс", style = MaterialTheme.typography.titleMedium, color = NovaDesign.Muted)
        Text(wallet?.total?.let { "$it ◈" } ?: "—", fontSize = 48.sp,
            fontWeight = FontWeight.Light, color = NovaDesign.Lavender)
        if (wallet != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BalanceSource("Покупки", wallet.purchased, Modifier.weight(1f))
                BalanceSource("Реклама", wallet.adReward, Modifier.weight(1f))
                BalanceSource("Бонусы", wallet.bonus, Modifier.weight(1f))
            }
        }
        OutlinedButton(onClick = { model.refreshWallet() }, enabled = !model.loading) { Text("Обновить баланс") }
    }
    NovaCard {
        Text("Premium", style = MaterialTheme.typography.titleLarge)
        val premium = model.aiStatus
        Text(when {
            premium?.premiumConfigured == true -> "Premium-ответ стоит ${premium.premiumPriceTokens} ◈. В чате можно выбрать бесплатный режим."
            premium == null -> "Цена Premium отображается в чате, когда режим доступен."
            else -> "Premium сейчас недоступен. Обычное общение остаётся бесплатным."
        }, color = NovaDesign.Muted)
    }
    RewardCards(model)
    NovaCard {
        Text("История операций", fontWeight = FontWeight.Bold)
        if (model.transactions.isEmpty() && wallet != null) Text("Пока нет операций.", color = NovaDesign.Muted)
        model.transactions.forEachIndexed { index, tx ->
            if (index > 0) HorizontalDivider(color = NovaDesign.Border)
            val debit = tx.type.startsWith("DEBIT")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(transactionLabel(tx.source, tx.type), style = MaterialTheme.typography.bodyMedium)
                    Text(transactionDate(tx.createdAt), style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted)
                }
                Text((if (debit) "−" else "+") + kotlin.math.abs(tx.amount) + " ◈", color = if (debit) NovaDesign.Muted else NovaDesign.Rose, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    NovaCard {
        Text("Покупки и реклама", style = MaterialTheme.typography.titleMedium)
        MonetizationControls(model)
    }
}

@Composable
private fun BalanceSource(label: String, amount: Int, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = NovaDesign.Ink,
        border = BorderStroke(1.dp, NovaDesign.Border)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(amount.toString(), style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = NovaDesign.Muted)
        }
    }
}

@Composable
internal fun AccountScreen(model: NovaViewModel) {
    Text("Твой аккаунт", style = MaterialTheme.typography.headlineMedium)
    NovaCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = Color(0xFF3C2A50), modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(model.email.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(model.email, Modifier.weight(1f), color = NovaDesign.Lavender, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = { model.changeTab(NovaTab.WALLET) }, modifier = Modifier.fillMaxWidth()) {
            Text("Кошелёк NOVA Tokens")
        }
    }
    NovaCard {
        Text("Уведомления", style = MaterialTheme.typography.titleMedium)
        if (model.notifications.isEmpty() && !model.loading) Text("Новых уведомлений нет", color = NovaDesign.Muted)
        model.notifications.forEachIndexed { index, notice ->
            if (index > 0) HorizontalDivider(color = NovaDesign.Border)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(notice.title, style = MaterialTheme.typography.titleSmall,
                    color = if (notice.readAt == null) NovaDesign.Lavender else NovaDesign.Muted)
                Text(notice.body, style = MaterialTheme.typography.bodyMedium)
                if (notice.readAt == null) TextButton(onClick = { model.readNotification(notice.id) },
                    enabled = !model.loading) { Text("Прочитано") }
            }
        }
    }
    NovaCard {
        Text("Настройки", style = MaterialTheme.typography.titleMedium)
        LanguageSwitch()
        MatureSettings(model)
    }
    NovaCard {
        Text("Поддержка", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = model.supportText, onValueChange = { model.supportText = it.take(500) },
            label = { Text("Обращение в поддержку") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
        Button(onClick = { model.sendSupport() }, enabled = !model.loading && model.supportText.trim().length >= 5,
            modifier = Modifier.fillMaxWidth()) { Text("Отправить обращение") }
    }
    NovaCard {
        Text("Данные и безопасность", style = MaterialTheme.typography.titleMedium)
        Text("Чаты и память хранятся в твоём аккаунте.", color = NovaDesign.Muted)
        OutlinedButton(onClick = { model.exportAccount() }, enabled = !model.loading) { Text("Экспортировать мои данные") }
        val context = LocalContext.current
        model.exportText?.let { data ->
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, data)
                context.startActivity(Intent.createChooser(send, "Экспорт NOVA"))
            }) { Text("Поделиться экспортом") }
        }
        OutlinedButton(onClick = { model.logout() }) { Text("Выйти из аккаунта") }
        var deleting by remember { mutableStateOf(false) }
        TextButton(onClick = { deleting = true }) { Text("Удалить аккаунт", color = MaterialTheme.colorScheme.error) }
        if (deleting) AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Удалить аккаунт и истории?") },
            text = { Text("Чаты, память и персонажи будут удалены. Учёт покупок сохраняется для обработки возвратов.") },
            confirmButton = {
                TextButton(onClick = { deleting = false; model.deleteAccount() }, enabled = !model.loading) {
                    Text("Удалить навсегда")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun RewardCards(model: NovaViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    model.dailyBonus?.takeIf { it.enabled }?.let { bonus ->
        NovaCard {
            Text("Ежедневный бонус", style=MaterialTheme.typography.headlineMedium)
            if (bonus.claimedToday) Text("Получено сегодня. Следующий бонус — завтра.", color = NovaDesign.Muted)
            else Button(onClick = { model.claimDailyBonus() }, enabled = !model.loading, modifier = Modifier.fillMaxWidth()) {
                Text("Забрать +" + bonus.amount + " ◈")
            }
        }
    }
    model.referral?.let { ref ->
        NovaCard {
            Text("Пригласи друга", style=MaterialTheme.typography.headlineMedium)
            if (ref.bonusTokens > 0) Text("Вам обоим по " + ref.bonusTokens + " ◈, когда друг отправит " + ref.requiredTurns + " сообщения в чате.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            ref.code?.let { code ->
                Text(code, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                OutlinedButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "Присоединяйся к NOVA AI! Мой код приглашения: " + code)
                    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Поделиться кодом")) }
                }) { Text("Поделиться кодом") }
            }
            Text("Приглашено друзей: ${ref.invited}. Бонусов получено: ${ref.rewarded}.", color = NovaDesign.Muted)
            ref.myInvite?.let { mine ->
                Text(if (mine.rewarded) "Бонус за твоё приглашение получен"
                    else "Твой код друга принят: " + mine.turns + " из " + ref.requiredTurns + " сообщений", color = Color.LightGray)
            }
            if (ref.canApplyCode) {
                OutlinedTextField(value = model.referralInput, onValueChange = { model.referralInput = it.take(8) },
                    label = { Text("Код друга") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { model.applyReferralCode() }, enabled = !model.loading && model.referralInput.length == 8) {
                    Text("Применить код")
                }
            }
        }
    }
}

@Composable
private fun MatureSettings(model: NovaViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Раздел 18+ (Mature)", fontWeight = FontWeight.SemiBold)
            Text("Выключен по умолчанию. Романтика без откровенного контента.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        }
        Switch(checked = model.showMature, enabled = !model.loading,
            onCheckedChange = { if (it) confirm = true else model.updateShowMature(false) })
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Только для взрослых") },
        text = { Text("Раздел содержит романтические и взрослые сюжеты с вымышленными совершеннолетними персонажами. Откровенный сексуальный контент запрещён. Подтверди, что тебе исполнилось 18 лет.") },
        confirmButton = { TextButton(onClick = { confirm = false; model.updateShowMature(true) }) { Text("Мне есть 18") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
    )
}

private fun transactionLabel(source: String, type: String): String = when (source) {
    "daily_bonus" -> "Ежедневный бонус"
    "referral_welcome" -> "Бонус за приглашение"
    "referral_invite" -> "Друг присоединился по твоему коду"
    "premium_ai_chat" -> "Premium-ответ"
    "admob_ssv" -> "Награда за рекламу"
    "play_billing" -> "Покупка токенов"
    "play_refund", "refund" -> "Возврат"
    else -> if (type.startsWith("DEBIT")) "Списание" else "Начисление"
}

private fun transactionDate(timestamp: String): String =
    runCatching {
        LocalDate.parse(timestamp.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru")))
    }.getOrDefault("")
