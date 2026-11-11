package ai.nova.app

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.android.billingclient.api.*
import com.google.android.gms.ads.*
import com.google.android.gms.ads.rewarded.*
import com.google.android.ump.*
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** Play charges are enabled by both build configuration and the verified server deployment flag. */
@Composable fun MonetizationControls(model: NovaViewModel) {
    val activity=LocalContext.current as? Activity ?: return
    val scope=rememberCoroutineScope()
    var offers by remember {mutableStateOf<List<ProductDetails>>(emptyList())}
    val billing=remember {
        BillingClient.newBuilder(activity).setListener {result,purchases->
            if(result.responseCode==BillingClient.BillingResponseCode.OK) purchases?.forEach {purchase->
                if(purchase.purchaseState==Purchase.PurchaseState.PURCHASED) scope.launch {
                    runCatching { purchase.products.forEach {model.verifyPurchase(it,purchase.purchaseToken)} }
                        .onSuccess {model.refreshWallet()}.onFailure {model.showNotice("Покупка ожидает проверки. Используй восстановление покупок.")}
                }
            }
        }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build()
    }
    DisposableEffect(billing) { onDispose {billing.endConnection()} }
    val products=model.products
    LaunchedEffect(products) {
        if(!BuildConfig.MONETIZATION_ENABLED||products?.enabled!=true)return@LaunchedEffect
        billing.startConnection(object: BillingClientStateListener {
            override fun onBillingServiceDisconnected() {model.showNotice("Google Play временно недоступен")}
            override fun onBillingSetupFinished(result:BillingResult) {
                if(result.responseCode!=BillingClient.BillingResponseCode.OK)return
                val query=QueryProductDetailsParams.newBuilder().setProductList(products.items.map {
                    QueryProductDetailsParams.Product.newBuilder().setProductId(it.sku).setProductType(BillingClient.ProductType.INAPP).build()
                }).build()
                billing.queryProductDetailsAsync(query){r,data->if(r.responseCode==BillingClient.BillingResponseCode.OK)offers=data.productDetailsList}
            }
        })
    }
    if(!BuildConfig.MONETIZATION_ENABLED||products?.enabled!=true) {Text("Покупки временно недоступны. Бесплатный чат остаётся доступным.");return}
    offers.forEach {offer->
        val detail=offer.oneTimePurchaseOfferDetails
        val tokens=products.items.firstOrNull {it.sku==offer.productId}?.tokens ?: return@forEach
        Button(onClick={
            val params=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer).build()
            val account=MessageDigest.getInstance("SHA-256").digest(products.accountId.toByteArray()).joinToString(""){"%02x".format(it)}
            billing.launchBillingFlow(activity,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).setObfuscatedAccountId(account).build())
        },enabled=detail!=null){Text("$tokens NOVA Tokens · ${detail?.formattedPrice.orEmpty()}")}
    }
    OutlinedButton(onClick={
        billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()){r,list->
            if(r.responseCode==BillingClient.BillingResponseCode.OK)scope.launch {
                runCatching {list.filter {it.purchaseState==Purchase.PurchaseState.PURCHASED}.forEach {p->p.products.forEach {model.verifyPurchase(it,p.purchaseToken)}}}
                    .onSuccess {model.refreshWallet()}.onFailure {model.showNotice("Не удалось проверить покупки. Повтори позже.")}
            }
        }
    }){Text("Восстановить незавершённые покупки")}
    val ads=model.ads
    if(ads?.configured==true && ads.adUnit!=null)OutlinedButton(onClick={
        val consent=UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(activity,ConsentRequestParameters.Builder().build(),{
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity){error->
                if(error!=null||!consent.canRequestAds()){model.showNotice("Реклама недоступна без необходимых настроек согласия");return@loadAndShowConsentFormIfRequired}
                MobileAds.initialize(activity){}
                RewardedAd.load(activity,ads.adUnit,AdRequest.Builder().build(),object:RewardedAdLoadCallback(){
                    override fun onAdLoaded(ad:RewardedAd){
                        ad.setServerSideVerificationOptions(ServerSideVerificationOptions.Builder().setUserId(ads.userId).build())
                        ad.fullScreenContentCallback=object:FullScreenContentCallback(){override fun onAdDismissedFullScreenContent(){model.refreshWallet()}}
                        ad.show(activity){model.showNotice("Просмотр завершён. Награда появится после проверки сервером.")}
                    }
                    override fun onAdFailedToLoad(error:LoadAdError){model.showNotice("Сейчас нет доступной рекламы")}
                })
            }
        },{model.showNotice("Не удалось загрузить настройки конфиденциальности рекламы")})
    },enabled=ads.remainingToday>0&&ads.secondsUntilNext==0){Text("Посмотреть рекламу за токены")}
    TextButton(onClick={UserMessagingPlatform.showPrivacyOptionsForm(activity){}}){Text("Настройки конфиденциальности рекламы")}
}
