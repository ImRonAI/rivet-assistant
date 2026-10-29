package com.hiron.rivet.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.hiron.rivet.core.ContextItem
import com.hiron.rivet.core.ImageItem
import com.hiron.rivet.core.PlaceItem
import com.hiron.rivet.core.SearchItem
import com.hiron.rivet.core.SearchKind
import com.hiron.rivet.core.SearchResults
import com.hiron.rivet.core.VideoItem
import com.hiron.rivet.core.WebItem

/** What result cards can do beyond opening and sharing: hand a result to Rivet, and open the results page. */
class ResultActions(
    val add: (ContextItem) -> Unit,
    val seeAll: (SearchResults, SearchKind) -> Unit,
)

/** Provided by Home and the results page; without it cards offer only Open and Share. */
val LocalResultActions = staticCompositionLocalOf<ResultActions?> { null }

const val CARD_WIDTH_DP = 280

/** Intents for a result's actions; each opens outside Rivet. */
object ResultIntents {
    fun dial(phone: String): Intent = Intent(Intent.ACTION_DIAL, ("tel:" + phone.filter { it.isDigit() || it == '+' }).toUri())

    /** Maps' `geo:` search at the place's coordinates, labelled with its name. */
    fun directions(lat: Double, lng: Double, name: String): Intent =
        Intent(Intent.ACTION_VIEW, "geo:0,0?q=$lat,$lng(${Uri.encode(name)})".toUri())
}


/** One search in the conversation, full width: a strip of cards for its kind, or its placeholders while it runs. */
@Composable
fun SearchBlock(id: String, results: SearchResults?, running: SearchKind?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val kind = results?.kind ?: running ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // An answer card carries its own label.
        if (results?.kind != SearchKind.Answers) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(if (results == null) "SEARCHING ${kind.label.uppercase()}" else "${kind.label.uppercase()} · BRAVE", tone = Tone.Cyan, live = results == null)
            if (results != null && results.query.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text("“${results.altered ?: results.query}”", style = Type.mono, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            results == null -> Skeleton(kind)
            results.kind == SearchKind.Answers -> AnswerCard(results, Modifier.padding(horizontal = 16.dp))
            results.items.isEmpty() -> Nothing(results, Modifier.padding(horizontal = 16.dp))
            else -> Strip(id, results)
        }
    }
}

@Composable
private fun Nothing(results: SearchResults, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nothing found for “${results.query}”.", style = Type.body, color = p.ink2)
        results.altered?.let { Text("Brave searched for “$it”.", style = Type.body, color = p.ink2) }
    }
}

@Composable
private fun Strip(id: String, results: SearchResults) {
    val actions = LocalResultActions.current
    when (results.kind) {
        SearchKind.Images -> ImageRow(results)
        SearchKind.Places -> PlaceStrip(id, results)
        else -> {
            val more = actions != null
            val pager = rememberPagerState { results.items.size + if (more) 1 else 0 }
            CardPager(pager) { page ->
                val item = results.items.getOrNull(page)
                when {
                    item is VideoItem -> VideoCard(item, page + 1, "$id/$page", results.kind)
                    item is WebItem -> WebCard(item, page + 1, results.kind)
                    actions != null -> SeeAll(Modifier.fillMaxWidth().height(240.dp)) { actions.seeAll(results, results.kind) }
                }
            }
        }
    }
}

@Composable
private fun CardPager(state: androidx.compose.foundation.pager.PagerState, page: @Composable (Int) -> Unit) {
    HorizontalPager(
        state,
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        pageSize = PageSize.Fixed(CARD_WIDTH_DP.dp),
        pageSpacing = 12.dp,
        verticalAlignment = Alignment.Top,
    ) { page(it) }
}

/** The card's number, matching what the agent was told. */
@Composable
fun NumberBadge(n: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(p.accent),
        contentAlignment = Alignment.Center,
    ) { Text("$n", style = Type.labelMd, color = p.accentInk) }
}

/** The card frame every result kind shares; tapping its body opens [onOpen]. */
@Composable
private fun ResultCard(description: String, onOpen: () -> Unit, modifier: Modifier, body: @Composable () -> Unit, footer: @Composable () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(p.card).border(1.dp, p.edge, shape)) {
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Open", onClick = onOpen).clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        ) { body() }
        Rule(color = p.line)
        footer()
    }
}

/** Open, Share and Add to Rivet, wrapping onto a second line when the text is large. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(kind: SearchKind, item: SearchItem, openLabel: String = "Open", extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val actions = LocalResultActions.current
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        extra()
        ActionIcon(Icons.AutoMirrored.Outlined.OpenInNew, openLabel) { openExternally(context, item.url) }
        ActionIcon(Icons.Outlined.Share, "Share") { Share.start(context, Share.text(item.title, item.url)) }
        val added = if (actions != null && kind != SearchKind.Answers) ContextItem.of(kind, item) else null
        if (actions != null && added != null) AddKey { actions.add(added) }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp)) }
}

/** "Add to Rivet": hands the result to whoever the user is talking to. */
@Composable
private fun AddKey(onClick: () -> Unit) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.heightIn(min = 48.dp).padding(vertical = 6.dp).clip(pill).background(p.accentSoft)
            .border(1.dp, p.accent.copy(alpha = 0.35f), pill).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = p.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add to Rivet", style = Type.labelLg, color = p.ink, maxLines = 1)
    }
}

/** A thumbnail, or the site's initial on graphite when the result has none. */
@Composable
private fun Thumb(url: String?, fallback: String, modifier: Modifier) {
    val p = LocalPalette.current
    if (url != null) {
        RemoteImage(url, null, modifier)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(p.high, p.tile))), contentAlignment = Alignment.Center) {
            Text(fallback.take(1).uppercase(), style = Type.stat, color = p.ink2.copy(alpha = 0.5f))
        }
    }
}

/** A web page, a news article or a page's extracted text. */
@Composable
fun WebCard(item: WebItem, n: Int, kind: SearchKind, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val site = item.site ?: item.url.toUri().host.orEmpty().removePrefix("www.")
    ResultCard(
        "$n. ${item.title}, $site", { openExternally(context, item.url) }, modifier,
        body = {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Thumb(item.thumbnail, site, Modifier.fillMaxSize())
                NumberBadge(n, Modifier.padding(10.dp))
                if (item.breaking) StatusTag("BREAKING", Tone.Accent, Modifier.align(Alignment.TopEnd).padding(10.dp).background(p.bg.copy(alpha = 0.6f), RoundedCornerShape(4.dp)))
            }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.favicon?.let { RemoteImage(it, null, Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))) }
                    if (item.favicon != null) Spacer(Modifier.width(6.dp))
                    Text(
                        listOfNotNull(site.uppercase(), item.age?.uppercase()).joinToString(" · "),
                        style = Type.labelSm, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(item.title, style = Type.title, color = p.ink, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                val text = if (kind == SearchKind.Context) item.snippets.firstOrNull() ?: item.description else item.description
                Text(text.orEmpty(), style = Type.body, color = p.ink2, maxLines = 3, minLines = 3, overflow = TextOverflow.Ellipsis)
            }
        },
        footer = { Footer(kind, item) },
    )
}

/** A video: its thumbnail with the length and a play key, which plays YouTube links right here. */
@Composable
fun VideoCard(item: VideoItem, n: Int, key: String, kind: SearchKind, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val playing = InlinePlayer.playing == key && item.youtubeId != null
    val play = {
        if (item.youtubeId != null) InlinePlayer.playing = key else openExternally(context, item.url)
    }
    val by = item.creator ?: item.publisher
    ResultCard(
        "$n. ${item.title}, ${listOfNotNull(by, item.duration).joinToString(", ")}", { play() }, modifier,
        body = {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                val id = item.youtubeId
                if (playing && id != null) {
                    VideoPlayer(key, id, Modifier.fillMaxSize())
                } else {
                    Thumb(item.thumbnail, by ?: item.title, Modifier.fillMaxSize())
                    Box(
                        Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(p.bg.copy(alpha = 0.72f))
                            .border(1.dp, p.white.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = p.white, modifier = Modifier.size(30.dp)) }
                    NumberBadge(n, Modifier.padding(10.dp))
                    item.duration?.let {
                        Text(
                            it, style = Type.labelMd, color = p.white,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = Type.title, color = p.ink, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(by, item.views?.let(::views), item.age).joinToString(" · "),
                    style = Type.mono, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        footer = { Footer(kind, item, openLabel = if (item.youtubeId != null) "Open in YouTube" else "Open") },
    )
}

/** "1.2M views". */
internal fun views(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1fB views".format(count / 1e9)
    count >= 1_000_000 -> "%.1fM views".format(count / 1e6)
    count >= 1_000 -> "%.1fK views".format(count / 1e3)
    else -> "$count views"
}.replace(".0", "")

/** A place: name, rating, price, kind, today's hours and address, with Call, Directions, Open and Add to Rivet. */
@Composable
fun PlaceCard(item: PlaceItem, n: Int, modifier: Modifier = Modifier, selected: Boolean = false, onSelect: () -> Unit = {}) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    ResultCard(
        "$n. ${item.title}, ${item.address.orEmpty()}", onSelect,
        modifier.then(if (selected) Modifier.border(2.dp, p.accent, shape) else Modifier),
        body = {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBadge(n)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.title, style = Type.title, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val rating = item.rating?.let { r -> "${"%.1f".format(r)} ★" + (item.reviews?.let { " ($it)" } ?: "") }
                    val line = listOfNotNull(rating, item.price, item.categories.take(2).joinToString(", ").ifBlank { null })
                    if (line.isNotEmpty()) Text(line.joinToString(" · "), style = Type.body, color = p.amb, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.hoursToday.isNotEmpty()) {
                        Text("Today " + item.hoursToday.joinToString(", ") { "${it.opens}–${it.closes}" }, style = Type.mono, color = p.ok, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    item.address?.let { Text(it, style = Type.body, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        footer = {
            Footer(SearchKind.Places, item) {
                item.phone?.let { phone -> ActionIcon(Icons.Outlined.Call, "Call") { Share.start(context, ResultIntents.dial(phone)) } }
                item.at?.let { at -> ActionIcon(Icons.Outlined.Directions, "Directions") { Share.start(context, ResultIntents.directions(at.lat, at.lng, item.title)) } }
            }
        },
    )
}

/** The map card (no gestures; a tap opens the results page) over a pager of place cards that lights the matching pin. */
@Composable
private fun PlaceStrip(id: String, results: SearchResults) {
    val p = LocalPalette.current
    val actions = LocalResultActions.current
    val places = results.items.filterIsInstance<PlaceItem>()
    val pager = rememberPagerState { places.size + if (actions != null) 1 else 0 }
    val pins = remember(places) { pins(places) }
    if (pins.isNotEmpty()) {
        val shape = RoundedCornerShape(16.dp)
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(180.dp).clip(shape).border(1.dp, p.edge, shape)) {
            PlaceMap(pins, interactive = false, selected = (pager.currentPage + 1).coerceAtMost(places.size), onSelect = {}, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().clickable(enabled = actions != null, role = Role.Button) { actions?.seeAll(results, SearchKind.Places) }
                    .semantics { contentDescription = "Map of ${pins.size} places. Opens the results page." },
            )
        }
    }
    CardPager(pager) { page ->
        val place = places.getOrNull(page)
        when {
            place != null -> PlaceCard(place, page + 1, selected = page == pager.currentPage)
            actions != null -> SeeAll(Modifier.fillMaxWidth().height(180.dp)) { actions.seeAll(results, SearchKind.Places) }
        }
    }
}

internal fun pins(places: List<PlaceItem>): List<MapPin> = places.mapIndexedNotNull { i, place -> place.at?.let { MapPin(i + 1, it, place.title) } }

/** Image tiles 140dp tall, as wide as each image's shape; a tap opens the viewer. */
@Composable
private fun ImageRow(results: SearchResults) {
    val actions = LocalResultActions.current
    var viewing by remember { mutableStateOf<Pair<ImageItem, Int>?>(null) }
    val images = results.items.filterIsInstance<ImageItem>()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(images, key = { i, it -> "$i:${it.url}" }) { i, item ->
            ImageTile(item, i + 1, Modifier.height(140.dp).width(tileWidth(item, 140.dp))) { viewing = item to i + 1 }
        }
        if (actions != null) item(key = "all") { SeeAll(Modifier.height(140.dp).width(120.dp)) { actions.seeAll(results, SearchKind.Images) } }
    }
    viewing?.let { (item, n) -> ImageViewer(item, n, onAdd = { actions?.add(it) }, onDismiss = { viewing = null }) }
}

/** An image tile's width for [height], from the image's shape (square when unknown). */
internal fun tileWidth(item: ImageItem, height: Dp): Dp {
    val w = item.width
    val h = item.height
    val ratio = if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else 1f
    return height * ratio.coerceIn(0.6f, 1.8f)
}

@Composable
fun ImageTile(item: ImageItem, n: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape).border(1.dp, p.edge, shape)
            .clickable(onClickLabel = "View", role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = "$n. ${item.title}, ${item.source.orEmpty()}"
                role = Role.Button
            },
    ) {
        RemoteImage(item.thumbnail, null, Modifier.fillMaxSize())
        NumberBadge(n, Modifier.padding(8.dp))
    }
}

/** The pager's last page: everything on the results page. */
@Composable
private fun SeeAll(modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(p.raised).border(1.dp, p.edge, shape).clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = p.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(8.dp))
        Text("See all", style = Type.labelLg, color = p.ink)
    }
}

/** Brave's answer, formatted. */
@Composable
fun AnswerCard(results: SearchResults, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val answer = results.answer.orEmpty()
    val body = remember(answer) { Rich.format(answer, p) }
    RivetCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("ANSWER · BRAVE", tone = Tone.Accent, modifier = Modifier.weight(1f))
            ActionIcon(Icons.Outlined.Share, "Share") { Share.start(context, Share.text(results.query, answer)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(body, style = Type.bodyLg, color = p.ink)
    }
}

/** Grey cards while a search runs. */
@Composable
fun Skeleton(kind: SearchKind) {
    val p = LocalPalette.current
    val a = pulse(low = 0.45f, periodMs = 1400)
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp).alpha(a), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(if (kind == SearchKind.Answers) 1 else 3) {
            when (kind) {
                SearchKind.Images -> Box(Modifier.size(140.dp).clip(shape).background(p.tile))
                SearchKind.Answers -> Column(Modifier.fillMaxWidth().padding(end = 16.dp).height(120.dp).clip(shape).background(p.card).border(1.dp, p.edge, shape)) {}
                else -> Column(Modifier.width(CARD_WIDTH_DP.dp).clip(shape).background(p.card).border(1.dp, p.edge, shape)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(p.tile))
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(90.dp).height(10.dp).clip(RoundedCornerShape(3.dp)).background(p.tile))
                        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(3.dp)).background(p.tile))
                        Box(Modifier.fillMaxWidth(0.7f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(p.tile))
                    }
                }
            }
        }
    }
}
