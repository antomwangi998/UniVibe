package com.univibe.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.univibe.app.core.keyedViewModel
import com.univibe.app.data.storage.StorageInspector
import com.univibe.app.data.storage.StorageSummary
import com.univibe.app.data.storage.formatBytes
import com.univibe.app.ui.components.BannerKind
import com.univibe.app.ui.components.ConfirmDialog
import com.univibe.app.ui.components.MessageBanner
import com.univibe.app.ui.components.VibeButton
import com.univibe.app.ui.components.VibeButtonStyle
import com.univibe.app.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DataStorageState(
    val summary: StorageSummary? = null,
    val clearing: Boolean = false,
    val notice: String? = null,
)

class DataStorageViewModel(private val inspector: StorageInspector) : ViewModel() {
    private val _state = MutableStateFlow(DataStorageState())
    val state: StateFlow<DataStorageState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _state.value = _state.value.copy(summary = inspector.measure()) }
    }

    fun clearCache() {
        if (_state.value.clearing) return
        _state.value = _state.value.copy(clearing = true, notice = null)
        viewModelScope.launch {
            val freed = inspector.clearCache()
            _state.value = DataStorageState(
                summary = inspector.measure(), clearing = false,
                notice = if (freed > 0) "Cache cleared. Freed ${formatBytes(freed)}." else "The cache was already empty.",
            )
        }
    }
}

/** Settings → Data & storage. Sizes are measured from the app's real folders on this phone. */
@Composable
fun DataStorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val viewModel = keyedViewModel("data-storage") { DataStorageViewModel(StorageInspector.forContext(context)) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    SettingsPage("Data & storage", onBack) {
        state.notice?.let { item { MessageBanner(it, BannerKind.Info) } }
        val s = state.summary
        if (s == null) {
            item { Row(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        } else {
            item {
                SettingsGroup {
                    SettingsSectionTitle("Cache")
                    StorageRow("Pictures", formatBytes(s.imageCacheBytes))
                    RowDivider()
                    StorageRow("Maps", formatBytes(s.mapCacheBytes))
                    RowDivider()
                    StorageRow("Other temporary files", formatBytes(s.otherCacheBytes))
                    RowDivider()
                    StorageRow("Total cache", formatBytes(s.cacheBytes), bold = true)
                }
            }
            item {
                VibeButton(
                    "Clear cache", onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(), style = VibeButtonStyle.Secondary,
                    enabled = !state.clearing && s.cacheBytes > 0, loading = state.clearing,
                )
            }
            item {
                SettingsGroup {
                    SettingsSectionTitle("On this phone")
                    StorageRow("Photos and videos you added", formatBytes(s.mediaBytes))
                    RowDivider()
                    StorageRow("Free space on your phone", if (s.deviceTotalBytes > 0) "${formatBytes(s.deviceAvailableBytes)} of ${formatBytes(s.deviceTotalBytes)}" else "Unknown")
                }
            }
        }
        item {
            SettingsNote(
                "Clearing the cache only removes copies UniVibe can download again. Your account, posts and messages are kept on UniVibe's servers and are not affected. " +
                    "Logging out and deleting your account are different: to delete your account, go to Settings → Account.",
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear cache?",
            message = "Pictures and maps will load again the next time you need them, which uses some data. Nothing in your account is deleted.",
            confirmLabel = "Clear cache", destructive = false,
            onConfirm = { confirmClear = false; viewModel.clearCache() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun StorageRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
