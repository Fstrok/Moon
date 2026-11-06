package app.moon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moon.App
import app.moon.data.Link
import app.moon.data.Source
import app.moon.data.site.NudeMoonParser
import app.moon.ui.common.ErrorBox
import app.moon.ui.common.LoadingBox
import app.moon.ui.common.LocalNavigator
import app.moon.ui.web.EmbeddedSitePage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class DirectoryViewModel(private val startUrl: String) : ViewModel() {
    data class State(
        val url: String,
        val entries: List<Link> = emptyList(),
        val letters: List<Link> = emptyList(),
        val loading: Boolean = true,
        val error: Throwable? = null,
    )

    private val _state = MutableStateFlow(State(startUrl))
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        load(startUrl)
        viewModelScope.launch { App.retrySignal.drop(1).collect { if (_state.value.error != null) load(_state.value.url) } }
    }

    fun load(url: String) {
        job?.cancel()
        job = viewModelScope.launch {
            val keepLetters = _state.value.letters
            _state.value = State(url, letters = keepLetters, loading = true)
            _state.value = try {
                val d = NudeMoonParser.parseDirectory(App.api.document(App.api.abs(url)))
                State(url, d.entries, d.letters.ifEmpty { keepLetters }, loading = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                State(url, letters = keepLetters, loading = false, error = e)
            }
        }
    }
}

/** Указатель сайта: алфавит + поиск по списку; выбор открывает список тайтлов. */
@Composable
fun DirectoryContent(url: String, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val vm: DirectoryViewModel = viewModel(key = "dir-$url") { DirectoryViewModel(url) }
    val st by vm.state.collectAsState()
    var filter by rememberSaveable(url) { mutableStateOf("") }
    val shown = remember(st.entries, filter) {
        val f = filter.trim()
        if (f.isEmpty()) st.entries else st.entries.filter { it.name.contains(f, true) }
    }

    if (!st.loading && st.error == null && st.entries.isEmpty() && st.letters.isEmpty()) {
        EmbeddedSitePage(App.api.abs(st.url), modifier)
        return
    }
    Column(modifier.fillMaxSize()) {
        if (st.letters.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(st.letters, key = { it.url }) { l ->
                    FilterChip(selected = l.url == st.url, onClick = { vm.load(l.url) }, label = { Text(l.name) })
                }
            }
        }
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Найти в списке (${st.entries.size})") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        when {
            st.loading -> LoadingBox()
            st.error != null -> ErrorBox(st.error!!, { vm.load(st.url) }, siteUrl = App.api.abs(st.url))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.url }) { e ->
                    Text(
                        e.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nav.listing(Source.Url(e.url, e.name)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
