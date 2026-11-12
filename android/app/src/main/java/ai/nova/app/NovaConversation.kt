package ai.nova.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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

/** Presentation only: all requests, streaming, persistence and charging remain in the existing model. */
@Composable fun ColumnScope.NovaConversation(model:NovaViewModel){
 val chat=model.activeChat?:return
 val keyboard=LocalSoftwareKeyboardController.current
 var settings by remember(chat.id){mutableStateOf(false)}
 var deleting by remember{mutableStateOf(false)}
 val list=rememberLazyListState()
 LaunchedEffect(model.chatMessages.size,model.streamingText?.length){
  if(list.layoutInfo.totalItemsCount>0)list.scrollToItem(list.layoutInfo.totalItemsCount-1)
 }
 Surface(color=NovaDesign.Surface){
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
   TextButton(onClick={model.closeChat()},enabled=!model.loading,contentPadding=PaddingValues(4.dp)){Text("Назад")}
   NovaPortrait(chat.characterId,chat.characterName?:chat.title?:"NOVA",Modifier.size(44.dp).clip(CircleShape),model.chatPortraitUrl(chat.characterId))
   Column(Modifier.weight(1f)){
    Text(chat.characterName?:chat.title?:"Твоя история",style=MaterialTheme.typography.titleMedium,maxLines=1)
    Text("Вымышленный AI-персонаж",style=MaterialTheme.typography.labelSmall,color=NovaDesign.Muted)
   }
   TextButton(onClick={settings=true},contentPadding=PaddingValues(4.dp)){Text("Ещё")}
  }
 }
 LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
  item { Text("Это разговор с AI. Не отправляй конфиденциальные данные.",style=MaterialTheme.typography.bodySmall,color=NovaDesign.Muted) }
  if(model.nextBefore!=null)item{TextButton(onClick={model.loadOlder()},enabled=!model.loading){Text("Более ранние сообщения")}}
  if(model.chatMessages.isEmpty())item{
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
      Text(if(mine)"ТЫ" else (chat.characterName?:chat.title?:"NOVA").uppercase(),style=MaterialTheme.typography.labelSmall,color=if(mine)NovaDesign.Lavender else NovaDesign.Rose)
      Text(message.content,style=MaterialTheme.typography.bodyLarge)
      if(!message.alternatives.isNullOrEmpty())Text("Версия ${message.alternatives.size+1} · прежние сохранены",style=MaterialTheme.typography.labelSmall,color=NovaDesign.Muted)
     }
    }
    Row(verticalAlignment=Alignment.CenterVertically){
     if(mine)TextButton(onClick={model.startEditMessage(message)},enabled=!model.loading){Text("Изменить",fontSize=11.sp)}
     if(!mine&&model.chatMessages.lastOrNull()?.id==message.id)TextButton(onClick={model.regenerate(message)},enabled=!model.loading){Text(if(model.premiumChatSelected)"Другой ответ · ${model.aiStatus?.premiumPriceTokens?:5} ◈" else "Другой ответ",fontSize=11.sp)}
     Box{
      TextButton(onClick={actions=true}){Text("Действия",fontSize=11.sp)}
      DropdownMenu(expanded=actions,onDismissRequest={actions=false}){
       DropdownMenuItem(text={Text("Ответвить историю")},onClick={actions=false;model.branch(message)},enabled=!model.loading)
       DropdownMenuItem(text={Text("Удалить")},onClick={actions=false;remove=true},enabled=!model.loading)
       if(!mine)DropdownMenuItem(text={Text("Пожаловаться")},onClick={actions=false;model.reportMessage(message.id)},enabled=!model.loading)
       SpeakButton(message.content)
      }
     }
    }
   }
   if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("Удалить сообщение?")},text={Text(if(mine)"Сообщение и ответ персонажа на него будут удалены." else "Ответ будет удалён.")},confirmButton={TextButton(onClick={remove=false;model.deleteMessage(message)}){Text("Удалить")}},dismissButton={TextButton(onClick={remove=false}){Text("Отмена")}})
  }
  model.streamingText?.let{partial->item{
   Surface(color=NovaDesign.Surface,shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,NovaDesign.Border)){
    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
     NovaTyping();if(partial.isNotEmpty())Text(partial,style=MaterialTheme.typography.bodyLarge)
    }
   }
  }}
 }
 Surface(color=NovaDesign.Surface,shadowElevation=12.dp){
  Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
    FilterChip(selected=!model.premiumChatSelected,onClick={model.premiumChatSelected=false},label={Text("Обычный · бесплатно",fontSize=11.sp)})
    FilterChip(selected=model.premiumChatSelected,onClick={model.premiumChatSelected=true},enabled=model.aiStatus?.premiumConfigured==true,label={Text("Premium · ${model.aiStatus?.premiumPriceTokens?:5} ◈",fontSize=11.sp)})
   }
   OutlinedTextField(value=model.chatInput,onValueChange={model.updateChatInput(it)},label={Text("Напиши сообщение")},modifier=Modifier.fillMaxWidth(),maxLines=3,shape=RoundedCornerShape(20.dp))
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.weight(1f)){DictationButton(model)}
    if(model.streamingText!=null)OutlinedButton(onClick={model.stopReply()}){Text("Остановить ответ")}
    else Button(onClick={keyboard?.hide();model.sendChatMessage()},enabled=!model.loading&&model.chatInput.isNotBlank()){
     Text(if(model.premiumChatSelected)"Отправить · ${model.aiStatus?.premiumPriceTokens?:5} ◈" else "Отправить бесплатно",fontSize=12.sp)
    }
   }
  }
 }
 if(settings)AlertDialog(onDismissRequest={settings=false},title={Text("Твоя история")},text={Column(Modifier.verticalScroll(rememberScrollState())){
  NovaEyebrow("РЕЖИМ ОБЩЕНИЯ")
  Text(if(model.premiumChatSelected) "Premium: более мощная модель, длинные ответы и увеличенный контекст. ${model.aiStatus?.premiumPriceTokens?:5} токенов только за успешный ответ. Баланс: ${model.aiStatus?.tokenBalance?:0} ◈." else "Обычный чат: без дневного лимита NOVA и без списания токенов. Возможны технические задержки провайдера.",style=MaterialTheme.typography.bodyMedium)
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
