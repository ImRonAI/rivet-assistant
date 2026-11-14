package com.hiron.ren.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hiron.ren.browser.Addresses
import com.hiron.ren.browser.Favorite
import com.hiron.ren.browser.Favorites
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.core.RenRuntime

const val HOME_SEARCH_TAG = "home-search"
const val HOME_SEARCH_HINT = "Ask me anything. I dare you."
const val HOME_DISCOVER_TAG = "home-discover"
const val HOME_ADD_FAVORITE_TAG = "home-add-favorite"

/**
 * Ren's home page, the page a new tab opens on: where to go (Search, here, and Discover), the logotype over a spray
 * of volt, the search box with how hard the search should work, and the favorite links. What is typed goes where the
 * address bar would take it; a favorite opens in this tab.
 */
@Composable
internal fun BrowserHome(runtime: RenRuntime, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var level by rememberSaveable { mutableStateOf(runtime.settings.searchLevel) }
    val favorites by runtime.favorites.list.collectAsState()
    Column(
        Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Environments(onDiscover = { onGo(Addresses.DISCOVER) })
        Spacer(Modifier.height(64.dp))
        // The spray halo: a soft volt bloom behind the mark, the one street detail on this page.
        Box(
            Modifier.drawBehind {
                val r = size.width * 0.9f
                drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.2f), Color.Transparent), Offset(size.width * 0.35f, size.height * 0.45f), r), r, Offset(size.width * 0.35f, size.height * 0.45f))
                drawCircle(Brush.radialGradient(listOf(p.accentHi.copy(alpha = 0.14f), Color.Transparent), Offset(size.width * 0.72f, size.height * 0.62f), r * 0.8f), r * 0.8f, Offset(size.width * 0.72f, size.height * 0.62f))
            },
        ) {
            RenWordmark(size = 84f)
        }
        Spacer(Modifier.height(36.dp))
        SearchBox(onGo)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            SearchLevel.entries.forEach { option ->
                FilterChip(option.label, option == level, {
                    level = option
                    runtime.settings.searchLevel = option
                })
            }
        }
        Spacer(Modifier.height(36.dp))
        FavoriteLinks(favorites, runtime.favorites, onGo)
        Spacer(Modifier.height(32.dp))
    }
}

/** Search (this page) and Discover side by side, as ren desktop's sidebar lists its destinations. */
@Composable
private fun Environments(onDiscover: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(Radius.card)
    Row(Modifier.clip(shape).background(p.tile).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Destination(Icons.Outlined.Search, "Search", current = true, onClick = {})
        Destination(Icons.Outlined.Explore, "Discover", current = false, onClick = onDiscover, modifier = Modifier.testTag(HOME_DISCOVER_TAG))
    }
}

@Composable
private fun Destination(icon: ImageVector, label: String, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(Radius.control)
    Row(
        modifier.height(36.dp).clip(shape).background(if (current) p.card else Color.Transparent)
            .clickable(enabled = !current, role = Role.Tab, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (current) p.ink else p.ink2, modifier = Modifier.size(16.dp))
        Text(label, style = Type.key, color = if (current) p.ink else p.ink2)
    }
}

/**
 * The search box: a card that lights up in volt while it has focus, the volt search tile, the field, and the go key,
 * which turns volt once there is something to go to. The hint shrinks to fit the field rather than being cut off.
 */
@Composable
private fun SearchBox(onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Radius.panel)
    val edge by animateColorAsState(if (focused) p.accent else p.line, tween(Motion.QUICK), label = "search-edge")
    val go = { if (text.isNotBlank()) onGo(text) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).ring(p.accent.copy(alpha = 0.2f), shape, focused, gap = 2.dp, width = 3.dp)
            .clip(shape).background(p.card).border(if (focused) 1.5.dp else 1.dp, edge, shape).padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.card)).background(p.accentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).testTag(HOME_SEARCH_TAG),
            singleLine = true,
            textStyle = Type.bodyLg.copy(color = p.ink),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            interactionSource = source,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        BasicText(
                            HOME_SEARCH_HINT,
                            style = Type.bodyLg.copy(color = p.muted),
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = Type.bodyLg.fontSize),
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        val ready = text.isNotBlank()
        val key by animateColorAsState(if (ready) p.accent else p.tile, tween(Motion.QUICK), label = "search-key")
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(key).clickable(enabled = ready, role = Role.Button, onClick = go)
                .semantics { contentDescription = "Search" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = if (ready) p.accentInk else p.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The favorite links, the way ren desktop's Discover draws them (`FavoriteLinks.tsx`): a micro label on a hairline,
 * then each link as a badge with its site's icon, and Add. Holding a badge offers to remove it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteLinks(list: List<Favorite>, favorites: Favorites, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("FAVORITES", style = Type.tele.copy(fontSize = 11.sp, letterSpacing = 0.08.sp * 11), color = p.ink2)
            Rule(Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { favorite -> FavoriteBadge(favorite, onOpen = { onGo(favorite.url) }, onRemove = { favorites.remove(favorite.id) }) }
            AddBadge(onClick = { adding = true })
        }
    }
    if (adding) AddFavorite(favorites, onDone = { adding = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteBadge(favorite: Favorite, onOpen: () -> Unit, onRemove: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(Radius.card)
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(40.dp).clip(shape).background(p.card).border(1.dp, p.line, shape)
                .combinedClickable(role = Role.Button, onLongClickLabel = "Remove ${favorite.title}", onLongClick = { menu = true }, onClick = onOpen)
                .padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(p.tile), contentAlignment = Alignment.Center) {
                SiteIcon(favorite.url)
            }
            Text(favorite.title, style = Type.labelLg, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 144.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Remove from favorites") }, onClick = {
                menu = false
                onRemove()
            })
        }
    }
}

/** The site's icon from Google's favicon service, as ren desktop shows it; a globe while it loads or when it can't. */
@Composable
private fun SiteIcon(url: String) {
    val p = LocalPalette.current
    val images = LocalImages.current
    val px = with(LocalDensity.current) { 16.dp.roundToPx() }
    val icon = produceState<ImageBitmap?>(null, url, images) {
        value = Favorites.icon(url)?.let { images?.bitmap(it, px * 2) }
    }.value
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    else Icon(Icons.Outlined.Public, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
}

@Composable
private fun AddBadge(onClick: () -> Unit) {
    val p = LocalPalette.current
    val radius = with(LocalDensity.current) { Radius.card.toPx() }
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(Radius.card))
            .drawBehind {
                drawRoundRect(p.lineHi, cornerRadius = CornerRadius(radius), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp).testTag(HOME_ADD_FAVORITE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = p.ink2, modifier = Modifier.size(16.dp))
        Text("Add", style = Type.labelLg, color = p.ink2)
    }
}

/** ren desktop's "Add a favorite": an optional title and the address, which has to be an http(s) one. */
@Composable
private fun AddFavorite(favorites: Favorites, onDone: () -> Unit) {
    val p = LocalPalette.current
    var title by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val save = {
        if (favorites.add(title, address) == null) error = "Enter a valid http(s) address." else onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = p.card,
        title = { Text("Add a favorite", style = Type.headline, color = p.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RecessedField(title, { title = it }, placeholder = "Title (optional)")
                RecessedField(
                    address,
                    {
                        address = it
                        error = null
                    },
                    placeholder = "https://…",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                error?.let { Text(it, style = Type.labelMd, color = p.danger) }
            }
        },
        confirmButton = { TextButton(onClick = save) { Text("Save", style = Type.button, color = p.accent) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", style = Type.button, color = p.ink2) } },
    )
}
