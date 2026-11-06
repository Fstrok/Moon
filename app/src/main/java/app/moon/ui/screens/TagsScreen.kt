package app.moon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.data.Source
import app.moon.ui.common.ErrorBox
import app.moon.ui.common.LoadingBox
import app.moon.ui.common.LocalNavigator
import app.moon.ui.common.SortChips
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class TagsViewModel : ViewModel() {
    data class State(val tags: List<String> = emptyList(), val loading: Boolean = true, val error: Throwable? = null)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { App.retrySignal.drop(1).collect { if (_state.value.error != null) load() } }
    }

    fun load(force: Boolean = false) = viewModelScope.launch {
        _state.value = State(loading = true)
        _state.value = try {
            State(App.api.tagList(force).sortedBy { it.lowercase() }, loading = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            State(loading = false, error = e)
        }
    }
}

@Composable
fun TagsScreen() {
    val nav = LocalNavigator.current
    val vm: TagsViewModel = viewModel { TagsViewModel() }
    val st by vm.state.collectAsState()
    val library by App.library.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var sort by rememberSaveable { mutableStateOf(App.settings.value.defaultSort) }
    val haptic = LocalHapticFeedback.current

    val shown = remember(st.tags, filter) {
        val f = filter.trim()
        if (f.isEmpty()) st.tags else st.tags.filter { it.contains(f, true) }
    }

    fun toggle(t: String) {
        selected = if (t in selected) selected - t else selected + t
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Теги") }, navigationIcon = { BackButton { nav.back() } })
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Выбрано: ${selected.size}", Modifier.weight(1f))
                        IconButton(onClick = { selected = emptyList() }) { Icon(Icons.Outlined.Close, "Сбросить") }
                        Button(onClick = { nav.listing(Source.Tags(selected, sort)) }) { Text("Показать") }
                    }
                }
            }
        },
    ) { pad ->
        when {
            st.loading -> LoadingBox(Modifier.padding(pad))
            st.error != null -> ErrorBox(st.error!!, { vm.load(true) }, Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        placeholder = { Text("Найти тег (${st.tags.size})") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
                item { SortChips(sort, { sort = it }, Modifier.padding(vertical = 8.dp)) }
                if (library.pinnedTags.isNotEmpty() && filter.isBlank()) {
                    item { Section("Закреплённые") }
                    item {
                        TagFlow(library.pinnedTags, selected, library.pinnedTags, ::toggle) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            App.library.togglePinnedTag(it)
                        }
                    }
                }
                item { Section(if (filter.isBlank()) "Все теги · долгое нажатие — закрепить" else "Найдено: ${shown.size}") }
                item {
                    TagFlow(shown, selected, library.pinnedTags, ::toggle) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        App.library.togglePinnedTag(it)
                        App.toast(if (it in App.library.value.pinnedTags) "Тег закреплён" else "Тег откреплён")
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagFlow(
    tags: List<String>,
    selected: List<String>,
    pinned: List<String>,
    onClick: (String) -> Unit,
    onLong: (String) -> Unit,
) {
    FlowRow(
        Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { t ->
            val sel = t in selected
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) cs.primaryContainer else cs.surfaceContainer)
                    .border(1.dp, if (sel) cs.primary else cs.outlineVariant, RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { onClick(t) }, onLongClick = { onLong(t) })
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t in pinned) {
                        Icon(Icons.Outlined.PushPin, null, Modifier.size(14.dp), tint = cs.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(t, style = MaterialTheme.typography.bodyMedium, color = if (sel) cs.onPrimaryContainer else cs.onSurface)
                }
            }
        }
    }
}
