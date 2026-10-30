package com.univibe.app.features.premium

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.univibe.app.app.AppContainer
import com.univibe.app.data.model.BillingItem
import com.univibe.app.data.model.CheckoutResult
import com.univibe.app.data.model.OrderInfo
import com.univibe.app.data.model.PremiumOverview
import com.univibe.app.data.repository.PremiumRepository
import com.univibe.app.data.repository.promotionMessage
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PremiumState(
    val loading: Boolean = true,
    val error: String? = null,
    val overview: PremiumOverview? = null,
    val selectedPlanId: String? = null,
    val checkout: CheckoutResult? = null,
    val receipt: String = "",
    val receiptSent: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val history: List<BillingItem> = emptyList(),
    val historyNext: String? = null,
)

class PremiumViewModel(
    private val repository: PremiumRepository = AppContainer.premiumRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PremiumState())
    val state: StateFlow<PremiumState> = _state.asStateFlow()

    /** One key per attempt, kept until the order exists, so a double tap cannot create two orders. */
    private var attemptKey: String? = null

    init { load() }

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            try {
                val o = repository.overview()
                val h = repository.billing(null)
                com.univibe.app.data.prefs.EntitlementsStore.update(o.entitlements)
                _state.update {
                    it.copy(
                        loading = false, error = null, overview = o, history = h.items, historyNext = h.next,
                        selectedPlanId = it.selectedPlanId?.takeIf { id -> o.plans.any { p -> p.id == id } } ?: o.plans.firstOrNull()?.id,
                    )
                }
            } catch (e: Exception) {
                _state.update { if (quiet && it.overview != null) it else it.copy(loading = false, error = e.promotionMessage()) }
            }
        }
    }

    fun select(planId: String) { if (_state.value.checkout == null) { attemptKey = null; _state.update { it.copy(selectedPlanId = planId) } } }
    fun setReceipt(v: String) = _state.update { it.copy(receipt = v) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Creates the order. This proves nothing about payment; access starts only after staff confirm the money. */
    fun subscribe() {
        val s = _state.value
        val planId = s.selectedPlanId ?: return
        if (s.busy || s.checkout != null) return
        val key = attemptKey ?: UUID.randomUUID().toString().also { attemptKey = it }
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val c = repository.checkout(planId, key)
                attemptKey = null
                _state.update { it.copy(busy = false, checkout = c, receipt = "", receiptSent = false) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = e.promotionMessage()) }
            }
        }
    }

    /** Resume a waiting order from the billing list (for example after closing the app mid-payment). */
    fun resume(item: BillingItem) {
        val pay = _state.value.overview?.payment ?: return
        _state.update { it.copy(checkout = CheckoutResult(OrderInfo(item.id, item.reference, item.amountMinor, item.amount, item.status, 0L), pay), receipt = "", receiptSent = false) }
    }

    fun closeCheckout() = _state.update { it.copy(checkout = null, receipt = "", receiptSent = false) }

    fun submitReceipt() {
        val s = _state.value
        val order = s.checkout?.order ?: return
        if (s.busy || s.receipt.isBlank()) return
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                repository.submitReceipt(order.id, s.receipt.trim())
                _state.update { it.copy(busy = false, receiptSent = true) }
                load(quiet = true)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, notice = e.promotionMessage()) }
            }
        }
    }

    fun loadMoreHistory() {
        val s = _state.value
        val cursor = s.historyNext ?: return
        viewModelScope.launch {
            try {
                val p = repository.billing(cursor)
                _state.update { it.copy(history = it.history + p.items, historyNext = p.next) }
            } catch (_: Exception) { /* the next scroll tries again */ }
        }
    }
}
