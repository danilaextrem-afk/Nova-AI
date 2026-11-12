package ai.nova.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalLayoutApi::class)
/** Presentation only: all requests, streaming, persistence and charging remain in the existing model. */
@Composable fun ColumnScope.NovaConversation(model:NovaViewModel){
 val chat=model.activeChat?:return
 val keyboard=LocalSoftwareKeyboardController.current
 var settings by remember(chat.id){mutableStateOf(false)}
 var deleting by remember{mutableStateOf(false)}
 val list=rememberLazyListState()
 val dragging by list.interactionSource.collectIsDraggedAsState()
 var followLatest by remember(chat.id) { mutableStateOf(true) }
 LaunchedEffect(dragging) {
  if (dragging) followLatest = false
  else if (!list.canScrollForward) followLatest = true
 }
 val voice=rememberNovaVoice()
 var call by remember(chat.id){mutableStateOf(false)}
 // Auto-read only replies that arrive while the chat is open, never the history shown on opening.
 var heardId by remember(chat.id){mutableStateOf(model.chatMessages.lastOrNull()?.id)}
 LaunchedEffect(model.chatMessages.lastOrNull()?.id){
  val last=model.chatMessages.lastOrNull()?:return@LaunchedEffect
  if(last.id!=heardId){val prev=heardId;heardId=last.id;if(prev!=null&&!call&&voice.autoRead&&last.role!="user")voice.speak(last.id,last.content,last.speakerId ?: chat.characterId,NovaLang.code,model.genderOf(last.speakerId ?: chat.characterId))}
 }
 DisposableEffect(chat.id){onDispose{voice.stop()}}
 LaunchedEffect(chat.id, model.chatMessages.lastOrNull()?.id, model.streamingText?.length, followLatest) {
  val lastItem = list.layoutInfo.totalItemsCount - 1
  if (followLatest && lastItem >= 0) list.scrollToItem(lastItem)
 }
 Surface(color=NovaDesign.Surface){
  Row(
   Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
   verticalAlignment = Alignment.CenterVertically,
   horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
   IconButton(onClick = { model.closeChat() }, enabled = !model.loading) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Назад"))
   }
   val name = chat.characterName ?: chat.title ?: "Твоя история"
   NovaPortrait(chat.characterId, name, Modifier.size(36.dp).clip(CircleShape),
    chat.characterAvatarUrl ?: model.chatPortraitUrl(chat.characterId))
   Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(if (chat.participantIds.isNullOrEmpty()) "AI-персонаж" else "Группа · ${chat.participantIds.size + 1}",
     style = MaterialTheme.typography.labelSmall, color = NovaDesign.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
   }
   IconButton(onClick = { voice.stop(); call = true }) {
    Icon(Icons.Default.Call, contentDescription = tr("Голосовой разговор"))
   }
   IconButton(onClick = { settings = true }) {
    Icon(Icons.Default.MoreVert, contentDescription = tr("Ещё"))
   }
  }
 }
 LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item { Text("Это разговор с AI. Не отправляй конфиденциальные данные.",style=MaterialTheme.typography.bodySmall,color=NovaDesign.Muted) }
  if(model.nextBefore!=null)item{TextButton(onClick={model.loadOlder()},enabled=!model.loading){Text("Более ранние сообщения")}}
  if(model.chatMessages.isEmpty())model.chatGreeting?.let{hello->item{
   Surface(color=NovaDesign.Surface,shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,NovaDesign.Border)){
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
     Text((chat.characterName?:chat.title?:"NOVA").uppercase(),style=MaterialTheme.typography.labelSmall,color=NovaDesign.Rose)
     androidx.compose.material3.Text(hello,style=MaterialTheme.typography.bodyLarge)
    }
   }
  }}
  if(model.chatMessages.isEmpty()&&model.chatGreeting==null)item{
   Column(Modifier.fillMaxWidth().padding(vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text("Всё начинается\nс одного «привет».",style=MaterialTheme.typography.headlineMedium)
    Text("Выбери слова. Историю создадите вместе.",color=NovaDesign.Muted,style=MaterialTheme.typography.bodySmall)
   }
  }
  items(model.chatMessages,key={it.id}){message->
   val mine=message.role=="user"
   var actions by remember(message.id){mutableStateOf(false)}
   var remove by remember(message.id){mutableStateOf(false)}
   Column(Modifier.fillMaxWidth(),horizontalAlignment=if(mine)Alignment.End else Alignment.Start){
    Surface(color=if(mine)Color(0xFF3B2A50)else NovaDesign.Surface,
     shape=RoundedCornerShape(topStart=22.dp,topEnd=22.dp,bottomEnd=if(mine)6.dp else 22.dp,bottomStart=if(mine)22.dp else 6.dp),
     border=BorderStroke(1.dp,if(mine)Color(0xFF604578)else NovaDesign.Border),modifier=Modifier.widthIn(max=520.dp).fillMaxWidth(if(mine)0.90f else 0.97f).animateContentSize()){
     Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
      Text(if(mine)"ТЫ" else (message.speakerName?:chat.characterName?:chat.title?:"NOVA").uppercase(),style=MaterialTheme.typography.labelSmall,color=if(mine)NovaDesign.Lavender else NovaDesign.Rose)
      androidx.compose.material3.Text(chatText(message.content,NovaDesign.Muted),style=MaterialTheme.typography.bodyLarge) // User content: never translated.
      if(!message.alternatives.isNullOrEmpty())Text("Версия ${message.alternatives.size+1} · прежние сохранены",style=MaterialTheme.typography.labelSmall,color=NovaDesign.Muted)
     }
    }
    FlowRow(verticalArrangement=Arrangement.Center){
     if(mine)TextButton(onClick={model.startEditMessage(message)},enabled=!model.loading){Text("Изменить",fontSize=11.sp)}
     if(!mine)TextButton(onClick={if(voice.speakingId==message.id)voice.stop() else voice.speak(message.id,message.content,message.speakerId ?: chat.characterId,NovaLang.code,model.genderOf(message.speakerId ?: chat.characterId))},enabled=voice.ready){
      Text(if(voice.speakingId==message.id)"■ Стоп" else "▶ Слушать",fontSize=11.sp)}
     if(!mine&&model.chatMessages.lastOrNull()?.id==message.id)TextButton(onClick={model.regenerate(message)},enabled=!model.loading){Text(if(model.premiumChatSelected)"Другой ответ · ${model.aiStatus?.premiumPriceTokens?:5} ◈" else "Другой ответ",fontSize=11.sp)}
     Box{
      TextButton(onClick={actions=true}){Text("Действия",fontSize=11.sp)}
      DropdownMenu(expanded=actions,onDismissRequest={actions=false}){
       DropdownMenuItem(text={Text("Ответвить историю")},onClick={actions=false;model.branch(message)},enabled=!model.loading)
       DropdownMenuItem(text={Text("Удалить")},onClick={actions=false;remove=true},enabled=!model.loading)
       if(!mine)DropdownMenuItem(text={Text("Пожаловаться")},onClick={actions=false;model.reportMessage(message.id)},enabled=!model.loading)
      }
     }
    }
   }
   if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("Удалить сообщение?")},text={Text(if(mine)"Сообщение и ответ персонажа на него будут удалены." else "Ответ будет удалён.")},confirmButton={TextButton(onClick={remove=false;model.deleteMessage(message)}){Text("Удалить")}},dismissButton={TextButton(onClick={remove=false}){Text("Отмена")}})
  }
  model.streamingText?.let{partial->item{
   Surface(color=NovaDesign.Surface,shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,NovaDesign.Border)){
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
     NovaTyping();if(partial.isNotEmpty())androidx.compose.material3.Text(chatText(partial,NovaDesign.Muted),style=MaterialTheme.typography.bodyLarge)
    }
   }
  }}
 }
 // Composer: one row (field + round send button) so it fits above the keyboard on small phones;
 // the mode chips, dictation and price hint show only while the keyboard is closed.
 val imeOpen=WindowInsets.isImeVisible
 Surface(color=NovaDesign.Surface,shadowElevation=12.dp){
  Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   if(!imeOpen)Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    FilterChip(selected=!model.premiumChatSelected,onClick={model.premiumChatSelected=false},label={Text("Бесплатно",fontSize=11.sp)})
    FilterChip(selected=model.premiumChatSelected,onClick={model.premiumChatSelected=true},enabled=model.aiStatus?.premiumConfigured==true,label={Text("Premium · ${model.aiStatus?.premiumPriceTokens?:5} ◈",fontSize=11.sp)})
    DictationButton(model, compact = true)
   }
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    OutlinedTextField(value=model.chatInput,onValueChange={model.updateChatInput(it)},label={Text("Напиши сообщение")},
     modifier=Modifier.weight(1f),maxLines=4,shape=RoundedCornerShape(20.dp))
    val sendLabel=if(model.premiumChatSelected)"Отправить · ${model.aiStatus?.premiumPriceTokens?:5} ◈" else "Отправить бесплатно"
    if(model.streamingText!=null)FilledTonalIconButton(onClick={model.stopReply()},modifier=Modifier.size(54.dp)){
     Icon(Icons.Default.Stop,contentDescription="Остановить ответ")
    } else FilledIconButton(onClick={followLatest=true;keyboard?.hide();model.sendChatMessage()},enabled=!model.loading&&model.chatInput.isNotBlank(),modifier=Modifier.size(54.dp)){
     Icon(Icons.AutoMirrored.Filled.Send,contentDescription=tr(sendLabel))
    }
   }
   if(model.premiumChatSelected)Text("Premium: ${model.aiStatus?.premiumPriceTokens?:5} ◈ спишутся только за успешный ответ",style=MaterialTheme.typography.labelSmall,color=NovaDesign.Muted)
  }
 }
 if(call)VoiceCallDialog(model,voice){call=false}
 if(settings)AlertDialog(onDismissRequest={settings=false},title={Text("Твоя история")},text={Column(Modifier.verticalScroll(rememberScrollState())){
  NovaEyebrow("РЕЖИМ ОБЩЕНИЯ")
  Text(if(model.premiumChatSelected) "Premium: более мощная модель, длинные ответы и увеличенный контекст. ${model.aiStatus?.premiumPriceTokens?:5} токенов только за успешный ответ. Баланс: ${model.aiStatus?.tokenBalance?:0} ◈." else "Обычный чат: без дневного лимита NOVA и без списания токенов. Возможны технические задержки провайдера.",style=MaterialTheme.typography.bodyMedium)
  VoiceSettings(voice,chat.characterId,model.chatMessages.lastOrNull{it.role!="user"}?.content?:"Привет! Так звучит мой голос.",model.genderOf(chat.characterId))
  StoryControls(model)
  OutlinedTextField(value=model.inChatQuery,onValueChange={model.inChatQuery=it},label={Text("Поиск в этой переписке")})
  TextButton(onClick={model.searchInChat()},enabled=!model.loading){Text("Найти")}
  model.inChatResults?.let{found->Text("Найдено: ${found.size}");found.forEach{Text(it.content)}}
  Text("Сообщения обрабатывают сервер NOVA и внешние AI-провайдеры. На бесплатном тарифе данные могут использоваться провайдером для улучшения моделей.",style=MaterialTheme.typography.bodySmall)
  TextButton(onClick={deleting=true}){Text("Удалить чат")}
 }},confirmButton={TextButton(onClick={settings=false}){Text("Закрыть")}})
 if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("Удалить чат?")},text={Text("История и её память будут удалены.")},confirmButton={TextButton(onClick={deleting=false;settings=false;model.deleteActiveChat()}){Text("Удалить чат")}},dismissButton={TextButton(onClick={deleting=false}){Text("Отмена")}})
 model.editingMessageId?.let{AlertDialog(onDismissRequest={model.editingMessageId=null},title={Text("Изменить сообщение")},text={OutlinedTextField(value=model.editingMessageText,onValueChange={model.editingMessageText=it})},confirmButton={TextButton(onClick={model.saveEditedMessage()},enabled=!model.loading){Text("Сохранить")}},dismissButton={TextButton(onClick={model.editingMessageId=null}){Text("Отмена")}})}
}
