package ai.nova.app

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage

object NovaDesign {
 val Ink=Color(0xFF0C0B14)
 val Surface=Color(0xFF191623)
 val Lavender=Color(0xFFC3A6FF)
 val Rose=Color(0xFFF3B3CF)
 val Muted=Color(0xFFBBB4C9)
 val Border=Color(0xFF383044)
 val Glow=Brush.linearGradient(listOf(Color(0xFF322440),Color(0xFF1D1829),Color(0xFF15131F)))
}
@Composable fun NovaTheme(content:@Composable ()->Unit){
 MaterialTheme(colorScheme=darkColorScheme(primary=NovaDesign.Lavender,secondary=NovaDesign.Rose,
  tertiary=Color(0xFF9DDBCF),background=NovaDesign.Ink,surface=NovaDesign.Surface,
  surfaceVariant=Color(0xFF282231),onBackground=Color(0xFFF6F1FA),onSurface=Color(0xFFF6F1FA),
  onPrimary=Color(0xFF241436),outline=Color(0xFF6C607C)),
  shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(14.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp),extraLarge=RoundedCornerShape(32.dp)),
  typography=Typography(
   headlineLarge=TextStyle(fontFamily=FontFamily.Serif,fontSize=38.sp,lineHeight=42.sp,fontWeight=FontWeight.Normal),
   headlineMedium=TextStyle(fontFamily=FontFamily.Serif,fontSize=30.sp,lineHeight=35.sp),
   titleLarge=TextStyle(fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),
   titleMedium=TextStyle(fontSize=17.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold),
   bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),bodyMedium=TextStyle(fontSize=14.sp,lineHeight=21.sp),
   bodySmall=TextStyle(fontSize=12.sp,lineHeight=18.sp),labelLarge=TextStyle(fontSize=14.sp,fontWeight=FontWeight.SemiBold),
   labelMedium=TextStyle(fontSize=11.sp,fontWeight=FontWeight.Medium,letterSpacing=0.3.sp)
  )){Surface(Modifier.fillMaxSize(),color=NovaDesign.Ink){content()}}
}
@Composable fun NovaWordmark(compact:Boolean=false){
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
  Surface(shape=RoundedCornerShape(12.dp),color=Color(0xFF332640),border=BorderStroke(1.dp,NovaDesign.Border)){
   Text("✦",color=NovaDesign.Rose,fontSize=if(compact)22.sp else 30.sp,modifier=Modifier.padding(horizontal=10.dp,vertical=5.dp))
  }
  Text("NOVA",fontWeight=FontWeight.SemiBold,letterSpacing=5.sp,fontSize=if(compact)20.sp else 28.sp)
 }
}
@Composable fun NovaEyebrow(text:String){Text(text.uppercase(),color=NovaDesign.Rose,fontSize=10.sp,letterSpacing=2.sp,fontWeight=FontWeight.SemiBold)}
internal val LocalPortraitAccount = staticCompositionLocalOf<NovaViewModel?> { null }

@Composable fun NovaPortrait(id:String?,name:String,modifier:Modifier=Modifier,url:String?=null,contentScale:ContentScale=ContentScale.Crop){
 val asset=when {
  id?.matches(Regex("00000000-0000-4000-8000-000000000[12](0[1-9]|1[0-2])"))==true -> "file:///android_asset/portraits/${id.takeLast(3)}.webp"
  id?.matches(Regex("studio-2(0[1-9]|1[0-2])"))==true -> "file:///android_asset/portraits/${id.takeLast(3)}.webp"
  url?.startsWith(StudioCast.assetBase+"/")==true && url.substringAfterLast("/").removeSuffix(".webp").toIntOrNull()?.let { it in 101..112 || it in 201..212 } == true -> "file:///android_asset/portraits/${url.substringAfterLast("/")}"
  else->null
 }
 val account = LocalPortraitAccount.current
 val context = androidx.compose.ui.platform.LocalContext.current
 val source = asset ?: url?.takeIf { it.isNotBlank() }
 val request = remember(source, account?.accessToken, account?.serverUrl, context) {
  val authorization = if (asset == null && source != null) account?.portraitAuthorization(source) else null
  if (authorization == null) source else coil.request.ImageRequest.Builder(context)
   .data(source)
   .addHeader("Authorization", authorization)
   .diskCachePolicy(coil.request.CachePolicy.DISABLED)
   .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
   .build()
 }
 Box(modifier.background(Color(0xFF2C2339)),contentAlignment=Alignment.Center){
  if(asset!=null||!url.isNullOrBlank())AsyncImage(model=request,contentDescription="Портрет персонажа $name",contentScale=contentScale,alignment=Alignment.TopCenter,modifier=Modifier.fillMaxSize())
  else Text(name.take(1),style=MaterialTheme.typography.headlineLarge,color=NovaDesign.Lavender)
 }
}
@Composable fun NovaEditorialHero(title:String,subtitle:String,id:String="00000000-0000-4000-8000-000000000101",eyebrow:String="NOVA ORIGINALS",height:Dp=310.dp){
 Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(28.dp))){
  NovaPortrait(id,title,Modifier.fillMaxSize())
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0x33100B18),Color(0xF7100B18)))))
  Column(Modifier.align(Alignment.BottomStart).padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   NovaEyebrow(eyebrow);Text(title,style=MaterialTheme.typography.headlineLarge,color=Color.White)
   Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=Color(0xFFE2D8EC))
  }
 }
}
@Composable fun NovaTyping(){
 val transition=rememberInfiniteTransition(label="typing")
 val phase by transition.animateFloat(0.3f,1f,infiniteRepeatable(tween(700),RepeatMode.Reverse),label="typingAlpha")
 Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
  repeat(3){Box(Modifier.size(5.dp).background(NovaDesign.Lavender.copy(alpha=if(it==1)1-phase+0.3f else phase),CircleShape))}
  Text("печатает…",color=NovaDesign.Muted,style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(start=5.dp))
 }
}
