package com.hiron.ren.ui

import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hiron.ren.R
import com.hiron.ren.browser.Addresses
import com.hiron.ren.browser.Browser
import com.hiron.ren.browser.BrowserTab
import com.hiron.ren.browser.DownloadRequest
import com.hiron.ren.core.RenRuntime

const val ADDRESS_TAG = "address"
const val ASK_REN_TAG = "ask-ren"
const val TABS_TAG = "tabs"
const val MORE_TAG = "more"

/** The other screens the browser's menu opens. */
enum class Page { Activity, Settings }

/**
 * The browser: the toolbar with the address bar and Ask Ren, the active tab (Ren's home page or a web page), and the
 * bottom bar with back, forward, search, the tabs and the menu. The bottom bar steps aside while the keyboard is up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(runtime: RenRuntime, waiting: Int, onAskRen: () -> Unit, onOpen: (Page) -> Unit) {
    val p = LocalPalette.current
    val browser = runtime.browser
    val state by browser.state.collectAsStateWithLifecycle()
    val tab = state.current ?: return
    var editing by rememberSaveable { mutableStateOf(false) }
    var tabs by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val download by browser.downloadRequest.collectAsStateWithLifecycle()
    val fullscreen by browser.fullscreen.collectAsStateWithLifecycle()
    val go = { typed: String ->
        editing = false
        browser.go(typed, runtime.settings.searchLevel)
    }
    Uploads(browser)
    BackHandler(fullscreen != null) { browser.exitFullscreen() }
    BackHandler(fullscreen == null && editing) { editing = false }
    BackHandler(fullscreen == null && !editing && tab.canGoBack) { browser.back() }

    Column(Modifier.fillMaxSize().background(p.bg).imePadding()) {
        Toolbar(tab, editing, waiting, onEdit = { editing = true }, onGo = go, onCancel = { editing = false }, onHome = { browser.load(tab.id, Addresses.HOME) }, onAskRen = onAskRen)
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.chrome)) {
            if (tab.loading) Box(Modifier.fillMaxHeight().fillMaxWidth(tab.progress.coerceIn(5, 100) / 100f).background(p.accent))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.home) BrowserHome(runtime, go) else TabView(browser, tab.id)
        }
        if (!WindowInsets.isImeVisible) {
            BottomBar(
                tab, state.tabs.size,
                onBack = browser::back, onForward = browser::forward, onSearch = { editing = true }, onTabs = { tabs = true }, onMore = { menu = true },
            )
        }
    }
    if (tabs) TabsSheet(browser, state.tabs, state.active, onDismiss = { tabs = false })
    if (menu) {
        MenuSheet(
            tab,
            onDismiss = { menu = false },
            onNewTab = { browser.open() },
            onReload = { if (tab.loading) browser.stop() else browser.reload() },
            onOpen = onOpen,
        )
    }
    download?.let { DownloadSheet(it, onSave = browser::download, onDismiss = browser::dismissDownload) }
    fullscreen?.let { view ->
        AndroidView(factory = { FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK) } }, modifier = Modifier.fillMaxSize(), update = { frame ->
            if (view.parent !== frame) {
                (view.parent as? ViewGroup)?.removeView(view)
                frame.removeAllViews()
                frame.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        })
    }
}

/** A page's upload field opens the system's file picker while the browser is on screen. */
@Composable
private fun Uploads(browser: Browser) {
    var answer by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        answer?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        answer = null
    }
    DisposableEffect(browser) {
        browser.fileChooser = { callback, params ->
            answer?.onReceiveValue(null)
            answer = callback
            runCatching { picker.launch(params.createIntent()) }.isSuccess.also { if (!it) { callback.onReceiveValue(null); answer = null } }
        }
        onDispose { browser.fileChooser = null }
    }
}

/** The active tab's WebView, in the activity's context while it is shown so its own dialogs and menus can open. */
@Composable
private fun TabView(browser: Browser, id: String) {
    val context = LocalContext.current
    AndroidView(
        factory = { FrameLayout(it) },
        modifier = Modifier.fillMaxSize(),
        update = { frame ->
            val view = browser.shown(id)
            if (view != null && view.parent !== frame) {
                (view.parent as? ViewGroup)?.removeView(view)
                (view.context as? MutableContextWrapper)?.baseContext = context
                frame.removeAllViews()
                frame.addView(view)
            }
        },
        onRelease = { it.removeAllViews() },
    )
}

/** The toolbar: Ren's mark (home), the address bar, and Ask Ren. While the address is edited it takes the whole bar. */
@Composable
private fun Toolbar(
    tab: BrowserTab,
    editing: Boolean,
    waiting: Int,
    onEdit: () -> Unit,
    onGo: (String) -> Unit,
    onCancel: () -> Unit,
    onHome: () -> Unit,
    onAskRen: () -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.chrome).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                AddressField(tab, onGo, Modifier.weight(1f))
                TextKey("Cancel", onCancel)
            } else {
                Image(
                    painterResource(R.drawable.ren_mark), contentDescription = "Home",
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(Radius.control)).clickable(role = Role.Button, onClick = onHome),
                )
                AddressPill(tab, onEdit, Modifier.weight(1f))
                AskRen(waiting, onAskRen)
            }
        }
        Rule(color = p.line)
    }
}

/** The address at rest: a padlock or globe and the page's identity, or the invitation to search on home. */
@Composable
private fun AddressPill(tab: BrowserTab, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val resting = Addresses.resting(tab.url)
    Row(
        modifier.height(36.dp).clip(pill).background(p.bg).border(1.dp, p.line, pill).clickable(role = Role.Button, onClick = onEdit)
            .testTag(ADDRESS_TAG).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = if (resting.isEmpty() || Addresses.searchQuery(tab.url) != null) Icons.Outlined.Search else if (Addresses.secure(tab.url)) Icons.Outlined.Lock else Icons.Outlined.Public
        Icon(icon, contentDescription = null, tint = p.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            resting.ifEmpty { ADDRESS_HINT }, style = Type.labelLg, color = if (resting.isEmpty()) p.muted else p.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

const val ADDRESS_HINT = "Search or type an address"

/** The address being edited: the exact address, selected, with the keyboard's Go. */
@Composable
private fun AddressField(tab: BrowserTab, onGo: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val pill = RoundedCornerShape(50)
    val start = Addresses.searchQuery(tab.url) ?: tab.url.takeUnless { tab.home }.orEmpty()
    var value by remember(tab.id) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        modifier = modifier.focusRequester(focus).testTag(ADDRESS_TAG),
        singleLine = true,
        textStyle = Type.body.copy(color = p.ink),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
        decorationBox = { inner ->
            Row(
                Modifier.height(36.dp).ring(p.accent.copy(alpha = 0.2f), pill, true, width = 2.dp).clip(pill).background(p.bg).border(1.dp, p.accent, pill).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.text.isEmpty()) Text(ADDRESS_HINT, style = Type.body, color = p.muted, maxLines = 1)
                    inner()
                }
                if (value.text.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close, contentDescription = "Clear", tint = p.ink2,
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button) { value = TextFieldValue("") }.padding(6.dp),
                    )
                }
            }
        },
    )
}

/**
 * Ask Ren, as ren desktop draws it (`ArticleAgentActions`): a pill in the logo's volt with white type, in every theme,
 * the name set in the logotype's face. It opens the agent panel and counts the actions waiting on the user.
 */
@Composable
private fun AskRen(waiting: Int, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(LogoVolt).clickable(role = Role.Button, onClick = onClick).testTag(ASK_REN_TAG)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_ren), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text("Ask Ren", style = TextStyle(fontFamily = Logo, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, lineHeight = 16.sp), color = Color.White, maxLines = 1)
        if (waiting > 0) {
            Box(Modifier.heightIn(min = 16.dp).clip(CircleShape).background(LocalPalette.current.amb).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                Text("$waiting", style = Type.labelSm.copy(fontFeatureSettings = "tnum"), color = LightPalette.ink, maxLines = 1)
            }
        }
    }
}

/** The bottom bar: back, forward, search, the tabs and the menu. */
@Composable
private fun BottomBar(tab: BrowserTab, count: Int, onBack: () -> Unit, onForward: () -> Unit, onSearch: () -> Unit, onTabs: () -> Unit, onMore: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.chrome).navigationBarsPadding()) {
        Rule(color = p.line)
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarKey(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tab.canGoBack, onBack, Modifier.weight(1f))
            BarKey(Icons.AutoMirrored.Outlined.ArrowForward, "Forward", tab.canGoForward, onForward, Modifier.weight(1f))
            BarKey(Icons.Outlined.Search, "Search", true, onSearch, Modifier.weight(1f))
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(Radius.control)).clickable(role = Role.Button, onClick = onTabs).testTag(TABS_TAG).semantics { contentDescription = "$count open tabs" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).border(1.5.dp, p.ink, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text("$count", style = Type.labelSm.copy(fontWeight = Type.key.fontWeight, fontFeatureSettings = "tnum"), color = p.ink, maxLines = 1)
                }
            }
            BarKey(Icons.Outlined.MoreHoriz, "Menu", true, onMore, Modifier.weight(1f).testTag(MORE_TAG))
        }
    }
}

@Composable
private fun BarKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(Radius.control)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) p.ink else p.dot, modifier = Modifier.size(22.dp))
    }
}

/** The open tabs: tap one to show it, close it with its cross, or open a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(browser: Browser, tabs: List<BrowserTab>, active: String?, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.raised, contentColor = p.ink) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Open tabs", Modifier.weight(1f))
                Keycap("New tab", { browser.open(); onDismiss() }, icon = Icons.Outlined.Add)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    val on = tab.id == active
                    val shape = RoundedCornerShape(Radius.card)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(if (on) p.accentSoft else p.card)
                            .border(1.dp, if (on) p.accent else p.line, shape)
                            .clickable(role = Role.Tab) { browser.select(tab.id); onDismiss() }.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(tab)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tab.label, style = Type.cardTitle, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!tab.home) Text(Addresses.resting(tab.url), style = Type.labelMd, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(
                            Icons.Outlined.Close, contentDescription = "Close ${tab.label}", tint = p.ink2,
                            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { browser.close(tab.id) }.padding(12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Favicon(tab: BrowserTab) {
    val p = LocalPalette.current
    val icon = tab.favicon
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(p.tile), contentAlignment = Alignment.Center) {
        when {
            tab.home -> Icon(painterResource(R.drawable.ic_ren), contentDescription = null, tint = p.accent, modifier = Modifier.size(16.dp))
            icon != null -> Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(18.dp))
            else -> Icon(Icons.Outlined.Public, contentDescription = null, tint = p.muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** The menu: a new tab, reload, share the page, and Ren's Activity and Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuSheet(tab: BrowserTab, onDismiss: () -> Unit, onNewTab: () -> Unit, onReload: () -> Unit, onOpen: (Page) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.raised, contentColor = p.ink) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp).padding(bottom = 12.dp)) {
            MenuRow(Icons.Outlined.Add, "New tab") { onNewTab(); onDismiss() }
            if (!tab.home) {
                MenuRow(if (tab.loading) Icons.Outlined.Close else Icons.Outlined.Refresh, if (tab.loading) "Stop" else "Reload") { onReload(); onDismiss() }
                MenuRow(Icons.Outlined.Share, "Share") {
                    onDismiss()
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url).putExtra(Intent.EXTRA_SUBJECT, tab.title)
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
            Rule(Modifier.padding(vertical = 4.dp, horizontal = 8.dp), color = p.line)
            MenuRow(Icons.Outlined.Timeline, "Activity") { onDismiss(); onOpen(Page.Activity) }
            MenuRow(Icons.Outlined.Tune, "Settings") { onDismiss(); onOpen(Page.Settings) }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(Radius.control)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = p.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = Type.body.copy(fontWeight = Type.labelLg.fontWeight), color = p.ink)
    }
}

/** A page wants to save a file: what and from where, with Save and Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(request: DownloadRequest, onSave: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.raised, contentColor = p.ink) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Download")
            Text(request.name, style = Type.title, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull("From ${request.host}", size(request.bytes)).joinToString(" · "), style = Type.body, color = p.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryKey("Cancel", onDismiss, Modifier.weight(1f))
                PrimaryKey("Save", onSave, Modifier.weight(1f), trailingArrow = false)
            }
        }
    }
}

/** A file size in plain units; null when the page didn't say. */
internal fun size(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1_000_000 -> "${(bytes + 500) / 1000} KB"
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}
