package com.hiron.emrys.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.hiron.emrys.core.Citation
import com.hiron.emrys.core.ContextItem
import com.hiron.emrys.core.ImageItem
import com.hiron.emrys.core.PlaceItem
import com.hiron.emrys.core.SearchItem
import com.hiron.emrys.core.SearchKind
import com.hiron.emrys.core.SearchResults
import com.hiron.emrys.core.VideoItem
import com.hiron.emrys.core.WebItem

/** What result cards can do beyond opening and sharing: hand a result to Emrys, and open the results page. */
class ResultActions(
    val add: (ContextItem) -> Unit,
    val seeAll: (SearchResults, SearchKind) -> Unit,
)

/** Provided by Home and the results page; without it cards offer only Open and Share. */
val LocalResultActions = staticCompositionLocalOf<ResultActions?> { null }

const val CARD_WIDTH_DP = 280

/** Intents for a result's actions; each opens outside Emrys. */
object ResultIntents {
    fun dial(phone: String): Intent = Intent(Intent.ACTION_DIAL, ("tel:" + phone.filter { it.isDigit() || it == '+' }).toUri())

    /** Maps' `geo:` search at the place's coordinates, labelled with its name. */
    fun directions(lat: Double, lng: Double, name: String): Intent =
        Intent(Intent.ACTION_VIEW, "geo:0,0?q=$lat,$lng(${Uri.encode(name)})".toUri())
}

/** The side margin of search results and the results page: 20dp, or 16dp on phones under 360dp wide. */
@Composable
internal fun searchGutter(): Dp {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return if (width > 0.dp && width < 360.dp) 16.dp else 20.dp
}

/** What a running search says beside its status dot while its placeholders show. */
internal fun searchingLine(kind: SearchKind): String = when (kind) {
    SearchKind.Web -> "Searching the web"
    SearchKind.News -> "Searching the news"
    SearchKind.Images -> "Searching images"
    SearchKind.Videos -> "Searching videos"
    SearchKind.Places -> "Searching places"
    SearchKind.Context -> "Searching pages"
    SearchKind.Answers -> "Searching for an answer"
}

/**
 * One search in the conversation, full width: an eyebrow and the words searched for in serif over a strip of cards
 * for its kind, or a glacier status line and its placeholders while it runs.
 */
@Composable
fun SearchBlock(id: String, results: SearchResults?, running: SearchKind?, modifier: Modifier = Modifier) {
    val kind = results?.kind ?: running ?: return
    val gutter = searchGutter()
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            results == null -> Searching(kind, Modifier.padding(horizontal = gutter))
            // An answer card carries its own label.
            results.kind != SearchKind.Answers -> Masthead(results, Modifier.padding(horizontal = gutter))
        }
        when {
            results == null -> Skeleton(kind)
            results.kind == SearchKind.Answers -> AnswerCard(results, Modifier.padding(horizontal = gutter))
            results.items.isEmpty() -> Nothing(results, Modifier.padding(horizontal = gutter))
            else -> Strip(id, results)
        }
    }
}

/** A running search: a breathing glacier dot and a plain sentence. */
@Composable
private fun Searching(kind: SearchKind, modifier: Modifier) {
    val p = LocalPalette.current
    Row(modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        SignalDot(p.cy, live = true)
        Spacer(Modifier.width(10.dp))
        Text(searchingLine(kind), style = Type.labelLg, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The kind and engine as an eyebrow, then what was searched for (Brave's rewording when it made one) in serif. */
@Composable
private fun Masthead(results: SearchResults, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("${results.kind.label} · Brave", dot = false)
        if (results.query.isNotBlank()) {
            Text(
                "“${results.altered ?: results.query}”", style = Type.quote.copy(fontSize = 24.sp, lineHeight = 29.sp), color = p.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Nothing(results: SearchResults, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nothing found for “${results.query}”.", style = Type.bodyLg, color = p.ink2)
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
        contentPadding = PaddingValues(horizontal = searchGutter()),
        pageSize = PageSize.Fixed(CARD_WIDTH_DP.dp),
        pageSpacing = 12.dp,
        verticalAlignment = Alignment.Top,
    ) { page(it) }
}

/**
 * The card's number, matching what the agent was told: a small Geist figure in a hairline circle. [onImage] sets it
 * on glass over a picture; [selected] fills it in porcelain, like the map's chosen pin.
 */
@Composable
fun NumberBadge(n: Int, modifier: Modifier = Modifier, onImage: Boolean = false, selected: Boolean = false) {
    val p = LocalPalette.current
    val fill = when {
        selected -> p.ink
        onImage -> p.glass
        else -> Color.Transparent
    }
    val line = when {
        selected -> p.ink
        onImage -> p.ink.copy(alpha = 0.22f)
        else -> p.lineHi
    }
    Box(
        modifier.size(24.dp).clip(CircleShape).background(fill).border(1.dp, line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = Type.labelMd.copy(fontFeatureSettings = "tnum", letterSpacing = 0.sp), color = if (selected) p.accentInk else p.ink, maxLines = 1)
    }
}

/**
 * The glass card every result kind shares: tapping its body opens [onOpen] and brightens the card; [actions] sit
 * under the body, then a hairline and the [footer]. [selected] draws a halo hairline and a faint halo light.
 */
@Composable
private fun ResultCard(
    description: String,
    onOpen: () -> Unit,
    modifier: Modifier,
    selected: Boolean = false,
    body: @Composable () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    footer: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val line = when {
        selected -> p.accent.copy(alpha = 0.55f)
        pressed -> p.lineHi
        else -> p.line
    }
    Column(
        modifier.then(if (selected) Modifier.glow(Glows.selected, shape) else Modifier)
            .clip(shape).background(if (pressed) p.cardHi else p.card).border(1.dp, line, shape),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(source, indication = null, onClickLabel = "Open", onClick = onOpen).clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        ) { body() }
        actions?.invoke()
        Rule(color = p.line)
        footer()
    }
}

/**
 * "Add to Emrys" on the left (only for links the brain accepts), then Open (unless [openLabel] is null, when the
 * card shows it as a pill) and Share on the right; with large text they wrap onto a second line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(kind: SearchKind, item: SearchItem, openLabel: String? = "Open") {
    val context = LocalContext.current
    val actions = LocalResultActions.current
    val added = if (actions != null && kind != SearchKind.Answers) ContextItem.of(kind, item) else null
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = if (added != null) Arrangement.SpaceBetween else Arrangement.End,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (actions != null && added != null) AddKey { actions.add(added) }
        Row {
            if (openLabel != null) ActionIcon(Icons.AutoMirrored.Outlined.OpenInNew, openLabel) { openExternally(context, item.url) }
            ActionIcon(Icons.Outlined.Share, "Share") { Share.start(context, Share.text(item.title, item.url)) }
        }
    }
}

/** A quiet round icon key, grey at rest and porcelain on a faint well while pressed. */
@Composable
private fun ActionIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier.size(48.dp).clip(CircleShape).background(if (pressed) p.edge else Color.Transparent)
            .clickable(source, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = if (pressed) p.ink else p.ink2, modifier = Modifier.size(20.dp)) }
}

/** "Add to Emrys": hands the result to whoever the user is talking to. A hairline pill with a halo plus. */
@Composable
private fun AddKey(onClick: () -> Unit) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier.heightIn(min = 48.dp).clickable(source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(pill).background(if (pressed) p.cardHi else Color.Transparent)
                .border(1.dp, if (pressed) p.accent.copy(alpha = 0.6f) else p.lineHi, pill).padding(start = 12.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = p.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text("Add to Emrys", style = Type.labelLg, color = p.ink, maxLines = 1)
        }
    }
}

/** A place's action as an outline pill (Call, Directions, Open), announced by its [label]. */
@Composable
private fun OutlinePill(label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(source, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 36.dp).clip(pill).background(if (pressed) p.cardHi else Color.Transparent)
                .border(1.dp, if (pressed) p.ink.copy(alpha = 0.28f) else p.lineHi, pill).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = Type.labelLg, color = p.ink, maxLines = 1) }
    }
}

/**
 * A thumbnail, or a soft noir gradient when the result has none, carrying the site's initial as a serif monogram in a
 * hairline circle unless [monogram] is false (a video's play disc takes the centre instead).
 */
@Composable
private fun Thumb(url: String?, fallback: String, modifier: Modifier, monogram: Boolean = true) {
    val p = LocalPalette.current
    if (url != null) {
        RemoteImage(url, null, modifier)
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(p.high, p.card))), contentAlignment = Alignment.Center) {
            if (monogram) {
                Box(Modifier.size(72.dp).border(1.dp, p.lineHi, CircleShape), contentAlignment = Alignment.Center) {
                    Text(fallback.take(1).uppercase(), style = Type.stat.copy(fontSize = 40.sp, lineHeight = 44.sp), color = p.ink.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** "Example Gear · 2 days ago": the site in Geist Medium, then how old it is. */
@Composable
private fun SiteLine(site: String, age: String?, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(site) }
            age?.let { append(" · $it") }
        },
        style = Type.cardBody, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

/**
 * A web page, a news article or a page's extracted text. Breaking news leads its site line with a coral dot.
 * [uniform] keeps every card in a strip the same height; a full-width list lets each take its own.
 */
@Composable
fun WebCard(item: WebItem, n: Int, kind: SearchKind, modifier: Modifier = Modifier, uniform: Boolean = true) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val site = item.site ?: item.url.toUri().host.orEmpty().removePrefix("www.")
    ResultCard(
        "$n. ${item.title}, $site", { openExternally(context, item.url) }, modifier,
        body = {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                Thumb(item.thumbnail, site, Modifier.fillMaxSize())
                NumberBadge(n, Modifier.padding(12.dp), onImage = true)
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.breaking) {
                        SignalDot(p.rec)
                        Spacer(Modifier.width(6.dp))
                        Text("Breaking", style = Type.cardBody.copy(fontWeight = FontWeight.Medium), color = p.ink, maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                    }
                    item.favicon?.let { RemoteImage(it, null, Modifier.size(14.dp).clip(RoundedCornerShape(4.dp))) }
                    if (item.favicon != null) Spacer(Modifier.width(6.dp))
                    SiteLine(site, item.age)
                }
                Text(item.title, style = Type.title, color = p.ink, maxLines = 2, minLines = if (uniform) 2 else 1, overflow = TextOverflow.Ellipsis)
                val text = if (kind == SearchKind.Context) item.snippets.firstOrNull() ?: item.description else item.description
                if (uniform || !text.isNullOrBlank()) {
                    Text(text.orEmpty(), style = Type.body, color = p.ink2, maxLines = 3, minLines = if (uniform) 3 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        footer = { Footer(kind, item) },
    )
}

/**
 * A video: its thumbnail with a quiet duration pill and a glass play disc, which plays YouTube links right here.
 * [uniform] keeps every card in a strip the same height.
 */
@Composable
fun VideoCard(item: VideoItem, n: Int, key: String, kind: SearchKind, modifier: Modifier = Modifier, uniform: Boolean = true) {
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
                    Thumb(item.thumbnail, by ?: item.title, Modifier.fillMaxSize(), monogram = false)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to p.bg.copy(alpha = 0.55f))))
                    Box(
                        Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(p.glass)
                            .border(1.dp, p.ink.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = p.ink, modifier = Modifier.size(28.dp)) }
                    NumberBadge(n, Modifier.padding(12.dp), onImage = true)
                    item.duration?.let {
                        val pill = RoundedCornerShape(50)
                        Text(
                            it, style = Type.tele.copy(fontSize = 11.sp), color = p.ink,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(pill).background(p.glass)
                                .border(1.dp, p.ink.copy(alpha = 0.16f), pill).padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = Type.title, color = p.ink, maxLines = 2, minLines = if (uniform) 2 else 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        by?.let { withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(it) } }
                        val rest = listOfNotNull(item.views?.let(::views), item.age)
                        if (rest.isNotEmpty()) append((if (by != null) " · " else "") + rest.joinToString(" · "))
                    },
                    style = Type.cardBody, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
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

/**
 * A place: name, rating, price, kind, today's hours and address, then Call, Directions and Open as outline pills,
 * with Add to Emrys and Share under a hairline. [titleInset] keeps the text clear of a key laid over the card's corner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceCard(
    item: PlaceItem,
    n: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    titleInset: Dp = 0.dp,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    ResultCard(
        "$n. ${item.title}, ${item.address.orEmpty()}", onSelect, modifier, selected = selected,
        body = {
            Row(Modifier.padding(start = 16.dp, end = 16.dp + titleInset, top = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBadge(n, selected = selected)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.title, style = Type.title, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val rating = item.rating?.let { r -> "${"%.1f".format(r)} ★" + (item.reviews?.let { " ($it)" } ?: "") }
                    val line = listOfNotNull(rating, item.price, item.categories.take(2).joinToString(", ").ifBlank { null })
                    if (line.isNotEmpty()) {
                        Text(
                            buildAnnotatedString {
                                line.forEachIndexed { i, part ->
                                    if (i > 0) append(" · ")
                                    if (i == 0 && rating != null) withStyle(SpanStyle(color = p.ink, fontWeight = FontWeight.Medium)) { append(part) } else append(part)
                                }
                            },
                            style = Type.body, color = p.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.hoursToday.isNotEmpty()) {
                        Text(
                            buildAnnotatedString {
                                append("Today ")
                                withStyle(SpanStyle(fontFamily = Mono, fontSize = 12.sp, color = p.ink, fontFeatureSettings = "tnum")) {
                                    append(item.hoursToday.joinToString(", ") { "${it.opens}–${it.closes}" })
                                }
                            },
                            style = Type.body, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.address?.let { Text(it, style = Type.body, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        actions = {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.phone?.let { phone -> OutlinePill("Call") { Share.start(context, ResultIntents.dial(phone)) } }
                item.at?.let { at -> OutlinePill("Directions") { Share.start(context, ResultIntents.directions(at.lat, at.lng, item.title)) } }
                OutlinePill("Open") { openExternally(context, item.url) }
            }
        },
        footer = { Footer(SearchKind.Places, item, openLabel = null) },
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
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.padding(horizontal = searchGutter()).fillMaxWidth().height(180.dp).clip(shape).border(1.dp, p.line, shape)) {
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
    LazyRow(contentPadding = PaddingValues(horizontal = searchGutter()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(images, key = { i, it -> "$i:${it.url}" }) { i, item ->
            ImageTile(item, i + 1, Modifier.height(140.dp).width(tileWidth(item, 140.dp))) { viewing = item to i + 1 }
        }
        if (actions != null) item(key = "all") { SeeAll(Modifier.height(140.dp).width(120.dp), radius = 14.dp) { actions.seeAll(results, SearchKind.Images) } }
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
        modifier.clip(shape).border(1.dp, p.line, shape)
            .clickable(onClickLabel = "View", role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = "$n. ${item.title}, ${item.source.orEmpty()}"
                role = Role.Button
            },
    ) {
        RemoteImage(item.thumbnail, null, Modifier.fillMaxSize())
        NumberBadge(n, Modifier.padding(8.dp), onImage = true)
    }
}

/** The strip's last page: everything on the results page. A hairline frame around an arrow in a hairline circle. */
@Composable
private fun SeeAll(modifier: Modifier, radius: Dp = 20.dp, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier.clip(shape).background(if (pressed) p.cardHi else Color.Transparent).border(1.dp, if (pressed) p.lineHi else p.line, shape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).border(1.dp, p.lineHi, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = p.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("See all", style = Type.labelLg, color = p.ink)
    }
}

/**
 * Brave's answer on a glass card: an eyebrow, the question in serif, the answer in Geist with its `[n]` markers,
 * and under a hairline the numbered sources they point to.
 */
@Composable
fun AnswerCard(results: SearchResults, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val answer = results.answer.orEmpty()
    val body = remember(answer) { Rich.format(answer, p) }
    EmrysCard(modifier, padding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Answer · Brave", dot = false, modifier = Modifier.weight(1f))
            ActionIcon(Icons.Outlined.Share, "Share", Modifier.offset(x = 14.dp)) { Share.start(context, Share.text(results.query, answer)) }
        }
        if (results.query.isNotBlank()) {
            Text("“${results.altered ?: results.query}”", style = Type.quote.copy(fontSize = 24.sp, lineHeight = 29.sp), color = p.ink)
            Spacer(Modifier.height(12.dp))
        }
        Text(body, style = Type.bodyLg, color = p.ink)
        if (results.citations.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Rule(color = p.line)
            Spacer(Modifier.height(16.dp))
            Eyebrow("Sources", dot = false)
            Spacer(Modifier.height(4.dp))
            results.citations.forEach { source -> SourceRow(source) }
        }
    }
}

/** One cited source: its number in a hairline circle, the site and its snippet; a tap opens it. */
@Composable
private fun SourceRow(source: Citation) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val site = source.url.toUri().host.orEmpty().removePrefix("www.").ifBlank { source.url }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (pressed) 0.6f else 1f)
            .clickable(interaction, indication = null, onClickLabel = "Open $site", role = Role.Button) { openExternally(context, source.url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NumberBadge(source.number)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(site, style = Type.labelLg, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            source.snippet?.let { Text(it, style = Type.body, color = p.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Quiet glass placeholders shaped like the cards to come, breathing slowly while a search runs. */
@Composable
fun Skeleton(kind: SearchKind) {
    val p = LocalPalette.current
    val a = pulse(low = 0.45f, periodMs = Motion.BREATHE)
    val gutter = searchGutter()
    val shape = RoundedCornerShape(20.dp)
    val bar = RoundedCornerShape(50)
    // The strip runs off the right edge, as the cards will; it never scrolls.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val answerWidth = (maxWidth - gutter * 2).coerceAtLeast(0.dp)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), enabled = false).padding(start = gutter).alpha(a),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(if (kind == SearchKind.Answers) 1 else 3) {
                when (kind) {
                    SearchKind.Images -> Box(Modifier.size(140.dp).clip(RoundedCornerShape(14.dp)).background(p.tile))
                    SearchKind.Answers -> Column(
                        Modifier.width(answerWidth).clip(shape).background(p.card).border(1.dp, p.line, shape).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.width(96.dp).height(8.dp).clip(bar).background(p.tile))
                        Box(Modifier.fillMaxWidth(0.75f).height(18.dp).clip(bar).background(p.tile))
                        Spacer(Modifier.height(4.dp))
                        repeat(3) { i -> Box(Modifier.fillMaxWidth(if (i == 2) 0.6f else 1f).height(11.dp).clip(bar).background(p.tile)) }
                    }
                    else -> Column(Modifier.width(CARD_WIDTH_DP.dp).clip(shape).background(p.card).border(1.dp, p.line, shape)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(p.tile))
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.width(96.dp).height(9.dp).clip(bar).background(p.tile))
                            Box(Modifier.fillMaxWidth().height(13.dp).clip(bar).background(p.tile))
                            Box(Modifier.fillMaxWidth(0.7f).height(13.dp).clip(bar).background(p.tile))
                            Spacer(Modifier.height(2.dp))
                            Box(Modifier.fillMaxWidth(0.9f).height(9.dp).clip(bar).background(p.edgeSoft))
                            Box(Modifier.fillMaxWidth(0.6f).height(9.dp).clip(bar).background(p.edgeSoft))
                        }
                    }
                }
            }
        }
    }
}
