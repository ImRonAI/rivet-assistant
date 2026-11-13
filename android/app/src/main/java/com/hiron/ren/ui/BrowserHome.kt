package com.hiron.ren.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hiron.ren.browser.SearchLevel
import com.hiron.ren.core.RenRuntime

const val HOME_SEARCH_TAG = "home-search"
const val HOME_SEARCH_HINT = "Search the web or type an address"

/**
 * Ren's home page, the page a new tab opens on: the logotype over a spray of volt, and the search box with how hard
 * the search should work. What is typed goes where the address bar would take it.
 */
@Composable
internal fun BrowserHome(runtime: RenRuntime, onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var level by rememberSaveable { mutableStateOf(runtime.settings.searchLevel) }
    Column(
        Modifier.fillMaxSize().background(p.bg).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(96.dp))
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
        Spacer(Modifier.height(40.dp))
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
        Spacer(Modifier.height(32.dp))
    }
}

/** The search box: a card with the field and a round ink key that goes. */
@Composable
private fun SearchBox(onGo: (String) -> Unit) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(Radius.panel)
    val go = { if (text.isNotBlank()) onGo(text) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(p.card).border(1.dp, p.line, shape).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = p.muted, modifier = Modifier.size(18.dp))
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
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(HOME_SEARCH_HINT, style = Type.bodyLg, color = p.muted, maxLines = 1)
                    inner()
                }
            },
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (text.isBlank()) p.tile else p.ink).clickable(enabled = text.isNotBlank(), role = Role.Button, onClick = go)
                .semantics { contentDescription = "Search" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = if (text.isBlank()) p.muted else p.accentInk, modifier = Modifier.size(18.dp))
        }
    }
}
