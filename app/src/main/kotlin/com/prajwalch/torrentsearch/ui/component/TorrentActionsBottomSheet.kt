package com.prajwalch.torrentsearch.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner

import com.prajwalch.torrentsearch.R
import com.prajwalch.torrentsearch.domain.SearchProvidersGateway
import com.prajwalch.torrentsearch.domain.model.MagnetUriState
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.ui.theme.spaces

import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import org.koin.androidx.compose.koinViewModel
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.parameter.parametersOf

sealed interface MagnetUriUiState {
    data object Loading : MagnetUriUiState

    data object Fetching : MagnetUriUiState

    data class Ready(val magnetUri: String) : MagnetUriUiState
}

@KoinViewModel
class TorrentActionsViewModel(
    @InjectedParam private val torrent: Torrent,
    private val searchProvidersGateway: SearchProvidersGateway,
) : ViewModel() {
    val magnetUriUiState: StateFlow<MagnetUriUiState> = flow {
        when (val magnetUriState = torrent.magnetUriState) {
            is MagnetUriState.Available -> {
                emit(MagnetUriUiState.Ready(magnetUriState.magnetUri))
            }

            is MagnetUriState.FetchRequired -> {
                emit(MagnetUriUiState.Fetching)

                val magnetUri = searchProvidersGateway.getMagnetUri(
                    torrentId = torrent.id,
                    sourceUrl = magnetUriState.url,
                    providerName = torrent.providerName
                )
                emit(MagnetUriUiState.Ready(magnetUri))
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MagnetUriUiState.Loading,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentActionsBottomSheet(
    torrent: Torrent,
    onOpenMagnetLink: (String) -> Unit,
    onDownloadTorrentFile: (url: String?, magnetUri: String) -> Unit,
    onCopyMagnetLink: (String) -> Unit,
    onShareMagnetLink: (String) -> Unit,
    onOpenDescriptionPage: () -> Unit,
    onCopyDescriptionPageUrl: () -> Unit,
    onShareDescriptionPageUrl: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    customAction: @Composable (() -> Unit)? = null,
) {
    val viewModelStoreOwner = rememberViewModelStoreOwner()
    val viewModel = koinViewModel<TorrentActionsViewModel>(
        viewModelStoreOwner = viewModelStoreOwner,
        parameters = { parametersOf(torrent) },
    )
    val magnetUriUiState by viewModel.magnetUriUiState.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    fun actionWithDismiss(action: () -> Unit): () -> Unit {
        return {
            action()

            coroutineScope.launch {
                sheetState.hide()
            }.invokeOnCompletion {
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.spaces.large)
                .padding(bottom = MaterialTheme.spaces.large)
                .verticalScroll(state = rememberScrollState())
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spaces.medium),
        ) {
            BottomSheetHeader(title = torrent.name, showNSFWBadge = torrent.isNSFW)
            HorizontalDivider()

            AnimatedContent(magnetUriUiState) { targetMagnetUriState ->
                when (targetMagnetUriState) {
                    MagnetUriUiState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(472.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    MagnetUriUiState.Fetching -> {
                        ContentState(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(472.dp),
                            icon = { CircularProgressIndicator() },
                            title = { Text("Getting magnet link...") },
                        )
                    }

                    is MagnetUriUiState.Ready -> {
                        val magnetUri = targetMagnetUriState.magnetUri

                        ActionColumn(
                            onOpenMagnetLink = actionWithDismiss { onOpenMagnetLink(magnetUri) },
                            onDownloadTorrentFile = actionWithDismiss {
                                onDownloadTorrentFile(torrent.fileDownloadLink, magnetUri)
                            },
                            onCopyMagnetLink = actionWithDismiss { onCopyMagnetLink(magnetUri) },
                            onShareMagnetLink = actionWithDismiss { onShareMagnetLink(magnetUri) },
                            onOpenDescriptionPage = actionWithDismiss(onOpenDescriptionPage),
                            onCopyDescriptionPageUrl = actionWithDismiss(onCopyDescriptionPageUrl),
                            onShareDescriptionPageUrl = actionWithDismiss(onShareDescriptionPageUrl),
                            enableDescriptionPageAction = torrent.descriptionPageUrl != null,
                            customAction = customAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSheetHeader(
    title: String,
    showNSFWBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(
            space = MaterialTheme.spaces.small,
            alignment = Alignment.CenterVertically,
        ),
    ) {
        if (showNSFWBadge) NSFWBadge()
        Text(
            text = title,
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ActionColumn(
    onOpenMagnetLink: () -> Unit,
    onDownloadTorrentFile: () -> Unit,
    onCopyMagnetLink: () -> Unit,
    onShareMagnetLink: () -> Unit,
    onOpenDescriptionPage: () -> Unit,
    onCopyDescriptionPageUrl: () -> Unit,
    onShareDescriptionPageUrl: () -> Unit,
    modifier: Modifier = Modifier,
    enableDescriptionPageAction: Boolean = true,
    customAction: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spaces.medium),
    ) {
        customAction?.invoke()

        PrimaryActionColumn(
            onOpenMagnetLink = onOpenMagnetLink,
            onDownloadTorrentFile = onDownloadTorrentFile,
            onCopyMagnetLink = onCopyMagnetLink,
            onShareMagnetLink = onShareMagnetLink,
        )
        DetailsPageActionColumn(
            onOpenDescriptionPage = onOpenDescriptionPage,
            onCopyDescriptionPageUrl = onCopyDescriptionPageUrl,
            onShareDescriptionPageUrl = onShareDescriptionPageUrl,
            enabled = enableDescriptionPageAction,
        )
    }
}

@Composable
private fun PrimaryActionColumn(
    onOpenMagnetLink: () -> Unit,
    onDownloadTorrentFile: () -> Unit,
    onCopyMagnetLink: () -> Unit,
    onShareMagnetLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.clip(MaterialTheme.shapes.large)) {
        ActionListItem(
            onClick = onOpenMagnetLink,
            icon = painterResource(R.drawable.ic_magnet),
            label = stringResource(R.string.torrent_list_action_open_magnet_link),
        )
        ActionListItem(
            onClick = onDownloadTorrentFile,
            icon = painterResource(R.drawable.ic_download),
            label = stringResource(R.string.torrent_list_action_download_torrent_file),
        )
        ActionListItem(
            onClick = onCopyMagnetLink,
            icon = painterResource(R.drawable.ic_copy),
            label = stringResource(R.string.torrent_list_action_copy_magnet_link),
        )
        ActionListItem(
            onClick = onShareMagnetLink,
            icon = painterResource(R.drawable.ic_share),
            label = stringResource(R.string.torrent_list_action_share_magnet_link),
        )
    }
}

@Composable
private fun DetailsPageActionColumn(
    onOpenDescriptionPage: () -> Unit,
    onCopyDescriptionPageUrl: () -> Unit,
    onShareDescriptionPageUrl: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.clip(MaterialTheme.shapes.large)) {
        ActionListItem(
            onClick = onOpenDescriptionPage,
            icon = painterResource(R.drawable.ic_link),
            label = stringResource(R.string.torrent_list_action_open_description_page),
            enabled = enabled,
        )
        ActionListItem(
            onClick = onCopyDescriptionPageUrl,
            icon = painterResource(R.drawable.ic_copy),
            label = stringResource(R.string.torrent_list_action_copy_description_page_url),
            enabled = enabled,
        )
        ActionListItem(
            onClick = onShareDescriptionPageUrl,
            icon = painterResource(R.drawable.ic_share),
            label = stringResource(R.string.torrent_list_action_share_description_page_url),
            enabled = enabled,
        )
    }
}

@Composable
fun ActionListItem(
    onClick: () -> Unit,
    icon: Painter,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ListItemColors = ListItemDefaults.colors(enabled),
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick, enabled = enabled),
        leadingContent = {
            Icon(
                modifier = Modifier.size(22.dp),
                painter = icon,
                contentDescription = null,
            )
        },
        headlineContent = {
            Text(
                text = label,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        colors = colors,
    )
}

@Composable
private fun ListItemDefaults.colors(enabled: Boolean): ListItemColors {
    return if (enabled) {
        colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    } else {
        with(colors()) {
            copy(
                headlineColor = disabledHeadlineColor,
                leadingIconColor = disabledLeadingIconColor,
            )
        }
    }
}

//@Preview
//@Composable
//private fun TorrentActionsBottomSheetPreview() {
//    TorrentSearchTheme {
//        TorrentActionsBottomSheet(
//            onDismiss = {},
//            title = "Torrent Actions Bottom Sheet Title",
//            onOpenMagnetLink = {},
//            onCopyMagnetLink = {},
//            onShareMagnetLink = {},
//            onOpenDescriptionPage = {},
//            onCopyDescriptionPageUrl = {},
//            onShareDescriptionPageUrl = {},
//            showNSFWBadge = true,
//            enableDescriptionPageActions = true,
//            onDownloadTorrentFile = {},
//        )
//    }
//}