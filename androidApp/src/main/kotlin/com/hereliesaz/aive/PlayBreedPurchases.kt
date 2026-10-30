package com.hereliesaz.aive

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.hereliesaz.geministrator.BreedPurchase
import com.hereliesaz.geministrator.BreedPurchases
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Breeds bought through Google Play (one-time consumable products named by
 * [BreedPurchases.productId]; they must exist in the Play Console). A purchase counts only once
 * Play reports it purchased; it is consumed only after the stable has granted it.
 */
class PlayBreedPurchases(private val activity: Activity) : BreedPurchases {
    /** The open billing flow's result, completed from the billing thread; one flow at a time ([buying]). */
    private val pending = AtomicReference<CompletableDeferred<List<Purchase>>?>(null)
    private val buying = Mutex()
    private val connecting = Mutex()

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener { result, purchases ->
            // A purchase arriving with no flow open (a pending one settling later) is left to unclaimed().
            pending.getAndSet(null)?.complete(
                if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases.orEmpty() else emptyList(),
            )
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        val done = CompletableDeferred<Boolean>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                done.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                done.complete(false)
            }
        })
        done.await()
    }

    private suspend fun details(breedIds: List<String>) = if (breedIds.isEmpty() || !ready()) {
        emptyList()
    } else {
        client.queryProductDetails(
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    breedIds.map {
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(BreedPurchases.productId(it))
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    },
                )
                .build(),
        ).productDetailsList.orEmpty()
    }

    override suspend fun prices(breedIds: List<String>): Map<String, String> = details(breedIds).mapNotNull { product ->
        val price = product.oneTimePurchaseOfferDetails?.formattedPrice ?: return@mapNotNull null
        BreedPurchases.breedId(product.productId)?.let { it to price }
    }.toMap()

    override suspend fun buy(breedId: String): List<BreedPurchase> {
        // A second buy while a flow is open would orphan the first one's result; it does nothing.
        if (!buying.tryLock()) return emptyList()
        try {
            val product = details(listOf(breedId)).firstOrNull() ?: return emptyList()
            val waiting = CompletableDeferred<List<Purchase>>()
            pending.set(waiting)
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build()))
                .build()
            if (client.launchBillingFlow(activity, params).responseCode != BillingClient.BillingResponseCode.OK) {
                pending.compareAndSet(waiting, null)
                return emptyList()
            }
            return waiting.await()
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && BreedPurchases.productId(breedId) in it.products }
                .map(::breedPurchase)
        } finally {
            buying.unlock()
        }
    }

    override suspend fun unclaimed(): List<BreedPurchase> {
        if (!ready()) return emptyList()
        val owned = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        return owned.purchasesList
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .map(::breedPurchase)
    }

    override suspend fun consume(purchase: BreedPurchase): Boolean =
        ready() &&
            client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(purchase.token).build())
                .billingResult.responseCode == BillingClient.BillingResponseCode.OK

    private fun breedPurchase(purchase: Purchase) =
        BreedPurchase(purchase.purchaseToken, purchase.products.mapNotNull(BreedPurchases::breedId))
}
