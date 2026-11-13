package com.hiron.ren.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hiron.ren.core.RenRuntime
import com.hiron.ren.core.Settings
import com.hiron.ren.voice.Catalogue
import com.hiron.ren.voice.Provider

/** What the picker knows of a provider's live catalogue. */
private sealed interface Listing {
    data object Loading : Listing
    data class Ready(val catalogue: Catalogue) : Listing
    data class Failed(val reason: String) : Listing
}

/**
 * The text model and its reasoning for a Strands [provider]: the model row opens the provider's live catalogue, searchable,
 * and the chosen model's reasoning levels sit under it, with Auto (the provider's own level) first.
 */
@Composable
internal fun TextModelSetting(runtime: RenRuntime, provider: Provider, refresh: Int) {
    val p = LocalPalette.current
    val s = runtime.settings
    var model by remember(provider, refresh) { mutableStateOf(s.textModel(provider)) }
    var effort by remember(provider, refresh) { mutableStateOf(s.effort(provider)) }
    var listing by remember(provider, refresh) { mutableStateOf<Listing>(Listing.Loading) }
    var efforts by remember(provider, refresh) { mutableStateOf<List<String>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(provider, refresh) {
        listing = runCatching { runtime.strands.models(provider) }
            .fold({ Listing.Ready(it) }, { Listing.Failed(it.message ?: "Couldn't load the models.") })
    }
    val default = (listing as? Listing.Ready)?.catalogue?.default
    val shown = model ?: default
    LaunchedEffect(provider, shown) {
        efforts = shown?.let { id -> runCatching { runtime.strands.efforts(provider, id) }.getOrDefault(emptyList()) }.orEmpty()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Model", style = Type.labelLg, color = p.ink2)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(p.field)
                .clickable(role = Role.Button) { picking = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    model != null -> model.orEmpty()
                    default != null -> "Default · $default"
                    else -> "Default"
                },
                style = Type.mono, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        (listing as? Listing.Failed)?.let { InfoStrip(it.reason, icon = Icons.Outlined.WarningAmber, tone = Tone.Amber) }
    }
    if (efforts.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reasoning", style = Type.labelLg, color = p.ink2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(Settings.AUTO) + efforts).forEach { level ->
                    FilterChip(
                        level.replaceFirstChar(Char::uppercase), level == effort,
                        {
                            effort = level
                            runtime.chooseEffort(provider, level)
                        },
                        Modifier.heightIn(min = 40.dp),
                    )
                }
            }
        }
    }

    if (picking) {
        ModelSheet(provider, listing, model, onDismiss = { picking = false }) { chosen ->
            picking = false
            model = chosen
            effort = Settings.AUTO
            runtime.chooseModel(provider, chosen)
        }
    }
}

/** The provider's models in a sheet: a search field over the list, the default first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(provider: Provider, listing: Listing, selected: String?, onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.raised,
        contentColor = p.ink,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text("${provider.label} models", style = Type.title, color = p.ink)
            Spacer(Modifier.height(14.dp))
            RecessedField(query, { query = it }, placeholder = "Search models")
            Spacer(Modifier.height(10.dp))
            when (listing) {
                Listing.Loading -> Text("Loading the models…", style = Type.body, color = p.ink2, modifier = Modifier.padding(vertical = 16.dp))
                is Listing.Failed -> InfoStrip(listing.reason, icon = Icons.Outlined.WarningAmber, tone = Tone.Amber)
                is Listing.Ready -> {
                    val found = listing.catalogue.models.filter { it.id.contains(query.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        if (query.isBlank()) {
                            item { ModelRow("Default · ${listing.catalogue.default}", selected == null) { onChoose(null) } }
                        }
                        items(found, key = { it.id }) { ModelRow(it.id, it.id == selected) { onChoose(it.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.mono, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = "Chosen", tint = p.accent, modifier = Modifier.size(18.dp))
        }
    }
}
