package ai.nova.app
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@Composable fun MatureStudioSection(model:NovaViewModel){
 if(!model.showMature||!model.matureSection)return
 var expanded by remember{mutableStateOf(false)}
 var filter by remember{mutableStateOf("Все")}
 var selected by remember{mutableStateOf<StudioHero?>(null)}
 NovaEditorialHero("Встречи\nс характером","12 оригинальных героинь · 25–36 лет","studio-201","MATURE / 18+")
 Text("NOVA Originals",style=MaterialTheme.typography.titleLarge)
 Text("Авторская подборка. Начав историю, ты создашь личную версию героини. Публикация — только через обычную модерацию.",style=MaterialTheme.typography.bodySmall,color=NovaDesign.Muted)
 OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()){Text(if(expanded)"Свернуть подборку" else "Открыть 12 историй")}
 if(expanded){
  Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   listOf("Все","Романтика","Творчество","Приключения").forEach{tag->FilterChip(selected=filter==tag,onClick={filter=tag},label={Text(tag)})}
  }
  StudioCast.heroes.filter{filter=="Все"||it.category==filter}.chunked(2).forEach{pair->
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
    pair.forEach{hero->
     Card(onClick={selected=hero},modifier=Modifier.weight(1f),shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,NovaDesign.Border)){
      NovaPortrait(hero.id,hero.name,Modifier.fillMaxWidth().aspectRatio(0.72f))
      Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
       Text(hero.name,style=MaterialTheme.typography.titleMedium)
       Text("${hero.age} лет · ${hero.category}",style=MaterialTheme.typography.labelSmall,color=NovaDesign.Rose)
      }
     }
    }
    if(pair.size==1)Spacer(Modifier.weight(1f))
   }
  }
 }
 selected?.let{hero->AlertDialog(onDismissRequest={selected=null},title={Text(hero.name)},
  text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
   NovaPortrait(hero.id,hero.name,Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(20.dp)))
   Text("18+ · ${hero.age} лет · Вымышленная героиня",color=NovaDesign.Rose)
   Text(hero.biography);Text(hero.personality,color=NovaDesign.Muted)
   HorizontalDivider();Text("Первое сообщение",fontWeight=FontWeight.SemiBold);Text(hero.greeting)
  }},confirmButton={Button(onClick={selected=null;model.startStudioChat(hero)},enabled=!model.loading){Text("Начать историю")}},dismissButton={TextButton(onClick={selected=null}){Text("Закрыть")}})}
}
