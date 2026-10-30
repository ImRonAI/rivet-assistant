package com.hiron.rivet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hiron.rivet.core.ContextItem
import com.hiron.rivet.core.ImageItem
import com.hiron.rivet.core.PlaceItem
import com.hiron.rivet.core.SearchKind
import com.hiron.rivet.core.SearchQuery
import com.hiron.rivet.core.SearchResults
import com.hiron.rivet.core.Suggester
import com.hiron.rivet.core.VideoItem
import com.hiron.rivet.core.WebItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the results page opens on: [initial] results already found, or an empty field to type in ([typing]). */
data class ResultsRequest(val query: String, val kind: SearchKind, val initial: SearchResults? = null, val typing: Boolean = false) {
    companion object {
        /** The widget's search sheet. */
        val Sheet = ResultsRequest("", SearchKind.Web, typing = true)
    }
}

/** The tabs of the results page, in order. */
val ResultTabs = listOf(SearchKind.Web, SearchKind.News, SearchKind.Images, SearchKind.Videos, SearchKind.Places)

const val RESULTS_FIELD_TAG = "results-field"

/** Suggestions for what is being typed, and Brave's spelling correction when it has one. */
data class TypingHelp(val suggestions: List<String>, val corrected: String?)

/**
 * Brave Suggest after a 250 ms pause (2 characters or more) and Spellcheck after 800 ms (4 or more); a keystroke
 * cancels both. Off when [enabled] is false, so nothing is sent.
 */
@Composable
fun rememberTypingHelp(text: String, enabled: Boolean, suggester: Suggester?): TypingHelp {
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var corrected by remember { mutableStateOf<String?>(null) }
    val q = text.trim()
    val on = enabled && suggester != null
    LaunchedEffect(q, on) {
        if (!on || q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        suggestions = suggester.suggest(q).filterNot { it.equals(q, ignoreCase = true) }
    }
    LaunchedEffect(q, on) {
        corrected = null
        if (!on || q.length < 4) return@LaunchedEffect
        delay(800)
        corrected = suggester.spellcheck(q)?.takeUnless { it.equals(q, ignoreCase = true) }
    }
    return TypingHelp(suggestions, corrected)
}

/** "Did you mean <corrected>?", in cyan; a tap takes the correction. */
@Composable
fun DidYouMean(corrected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        buildAnnotatedString {
            append("Did you mean ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(corrected) }
            append("?")
        },
        style = Type.body, color = p.cy, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(corrected) }.padding(vertical = 14.dp),
    )
}

/**
 * The full results page: a search field with suggestions, a tab per kind (switching runs that search for the
 * same words), the results, and "Ask Rivet about …". Results stay in memory while the page is open.
 */
@Composable
fun ResultsScreen(
    request: ResultsRequest,
    search: suspend (SearchQuery) -> Result<SearchResults>,
    suggester: Suggester?,
    suggestions: Boolean,
    onAsk: (String) -> Unit,
    onAdd: (ContextItem) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val start = request.initial?.let { it.altered ?: it.query } ?: request.query
    var query by remember { mutableStateOf(start) }
    var text by remember { mutableStateOf(start) }
    var tab by remember { mutableStateOf(if (request.kind in ResultTabs) request.kind else SearchKind.Web) }
    val found = remember {
        mutableStateMapOf<Pair<String, SearchKind>, SearchResults>().apply {
            request.initial?.takeIf { it.kind in ResultTabs }?.let { put(start to it.kind, it) }
        }
    }
    val errors = remember { mutableStateMapOf<Pair<String, SearchKind>, String>() }
    // "Try again" moves it on, so the same words and tab search again.
    var attempt by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        if (request.typing) {
            focus.requestFocus()
            ime?.show()
        }
    }
    val key = query to tab
    LaunchedEffect(key, attempt) {
        if (query.isBlank() || key in found) return@LaunchedEffect
        errors.remove(key)
        search(SearchQuery(tab, query)).fold({ found[key] = it }, { errors[key] = it.message ?: "Search didn't work." })
    }
    val submit = { picked: String ->
        val q = picked.trim()
        if (q.isNotEmpty()) {
            text = q
            query = q
            focusManager.clearFocus()
            ime?.hide()
        }
    }
    val typing = focused && (text.trim() != query || query.isBlank())
    val help = rememberTypingHelp(text, suggestions && typing, suggester)
    val actions = remember(onAdd) { ResultActions(add = onAdd, seeAll = { _, kind -> if (kind in ResultTabs) tab = kind }) }

    CompositionLocalProvider(LocalResultActions provides actions) {
        Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconKey(Icons.AutoMirrored.Outlined.ArrowBack, "Close search", onClose, size = 48.dp)
                Spacer(Modifier.width(8.dp))
                RecessedField(
                    text, { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "Search the web",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                    fieldModifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }.testTag(RESULTS_FIELD_TAG),
                )
            }
            if (typing) {
                Typing(help, text, onPick = { submit(it) }, modifier = Modifier.weight(1f))
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResultTabs.forEach { kind -> FilterChip(kind.label, kind == tab, { tab = kind }, Modifier.heightIn(min = 48.dp)) }
                }
                Rule(color = p.line)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val results = found[key]
                    val error = errors[key]
                    when {
                        query.isBlank() -> Hint("Type what to look for, then press search.")
                        error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoStrip(error, icon = Icons.Outlined.WarningAmber, tone = Tone.Red)
                            Keycap("Try again", { attempt++ }, icon = Icons.Outlined.Refresh)
                        }
                        results == null -> Column(Modifier.padding(top = 16.dp)) { Skeleton(tab) }
                        results.items.isEmpty() -> Hint(
                            "Nothing found for “$query”." + (results.altered?.let { " Brave searched for “$it”." } ?: ""),
                        )
                        else -> Page(results)
                    }
                }
                if (query.isNotBlank()) {
                    Rule(color = p.line)
                    PrimaryKey(
                        "Ask Rivet about “$query”", {
                            onAsk(query)
                            onClose()
                        },
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp), leading = Icons.Outlined.Search,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    val p = LocalPalette.current
    Text(text, style = Type.body, color = p.ink2, modifier = Modifier.padding(24.dp))
}

/** While typing: Brave's suggestions (up to 8) and "Did you mean". */
@Composable
private fun Typing(help: TypingHelp, text: String, onPick: (String) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        help.corrected?.let { c -> item(key = "fix") { DidYouMean(c, onPick) } }
        itemsIndexed(help.suggestions.take(8), key = { i, s -> "$i:$s" }) { _, s ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onPick(s) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = p.ink2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(14.dp))
                Text(s, style = Type.bodyLg, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Rule(color = p.line)
        }
        if (text.isNotBlank()) {
            item(key = "go") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onPick(text) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = p.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Search for “${text.trim()}”", style = Type.bodyLg, color = p.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One kind's results at full width. */
@Composable
private fun Page(results: SearchResults) {
    var viewing by remember { mutableStateOf<Pair<ImageItem, Int>?>(null) }
    val actions = LocalResultActions.current
    when (results.kind) {
        SearchKind.Places -> PlacesPage(results)
        SearchKind.Images -> {
            val images = results.items.filterIsInstance<ImageItem>()
            LazyVerticalStaggeredGrid(
                StaggeredGridCells.Fixed(2), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalItemSpacing = 10.dp,
            ) {
                itemsIndexed(images, key = { i, it -> "$i:${it.url}" }) { i, item ->
                    val w = item.width
                    val h = item.height
                    val ratio = if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2f) else 1f
                    ImageTile(item, i + 1, Modifier.fillMaxWidth().aspectRatio(ratio)) { viewing = item to i + 1 }
                }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(results.items, key = { i, it -> "$i:${it.url}" }) { i, item ->
                when (item) {
                    is VideoItem -> VideoCard(item, i + 1, "page/${results.query}/$i", results.kind, Modifier.fillMaxWidth())
                    is WebItem -> WebCard(item, i + 1, results.kind, Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        }
    }
    viewing?.let { (item, n) -> ImageViewer(item, n, onAdd = { actions?.add(it) }, onDismiss = { viewing = null }) }
}

/** The interactive map on the top 45 %, the places under it; a tapped pin raises its card over the map's edge. */
@Composable
private fun PlacesPage(results: SearchResults) {
    val places = results.items.filterIsInstance<PlaceItem>()
    val pins = remember(places) { pins(places) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var raised by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        if (pins.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().weight(0.45f)) {
                PlaceMap(
                    pins, interactive = true, selected = selected,
                    onSelect = { n ->
                        selected = n
                        raised = true
                        scope.launch { list.animateScrollToItem(n - 1) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                val shown = selected?.let { places.getOrNull(it - 1) }
                if (raised && shown != null) {
                    val n = selected ?: 1
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        PlaceCard(shown, n, Modifier.fillMaxWidth(), selected = true)
                        IconKey(Icons.Outlined.Close, "Close card", { raised = false }, Modifier.align(Alignment.TopEnd).padding(4.dp), size = 48.dp)
                    }
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(0.55f), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(places, key = { i, it -> "$i:${it.url}" }) { i, place ->
                PlaceCard(place, i + 1, Modifier.fillMaxWidth(), selected = selected == i + 1, onSelect = { selected = i + 1 })
            }
        }
    }
}
