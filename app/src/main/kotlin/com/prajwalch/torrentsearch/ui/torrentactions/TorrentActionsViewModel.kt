package com.prajwalch.torrentsearch.ui.torrentactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.prajwalch.torrentsearch.data.repository.BookmarkRepository
import com.prajwalch.torrentsearch.data.repository.SettingsRepository
import com.prajwalch.torrentsearch.domain.SearchProvidersGateway
import com.prajwalch.torrentsearch.domain.TorrentFileDownloadResult
import com.prajwalch.torrentsearch.domain.TorrentFileDownloader
import com.prajwalch.torrentsearch.domain.model.MagnetUriState
import com.prajwalch.torrentsearch.domain.model.Torrent
import com.prajwalch.torrentsearch.util.TorrentUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

import java.io.OutputStream

sealed interface MagnetUriUiState {
    data object Loading : MagnetUriUiState

    data object Fetching : MagnetUriUiState

    data class Ready(val magnetUri: String) : MagnetUriUiState
}

sealed interface TorrentFileUiState {
    data object Downloading : TorrentFileUiState
    data class DownloadComplete(val fileName: String) : TorrentFileUiState
    data object DownloadFailed : TorrentFileUiState
    data object FileNotFound : TorrentFileUiState
    data object WritingContent : TorrentFileUiState
    data object WriteComplete : TorrentFileUiState
}

@KoinViewModel
class TorrentActionsViewModel(
    @InjectedParam private val torrent: Torrent,
    private val searchProvidersGateway: SearchProvidersGateway,
    private val bookmarkRepository: BookmarkRepository,
    private val torrentFileDownloader: TorrentFileDownloader,
    settingsRepository: SettingsRepository,
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

    private val _torrentFileState = MutableStateFlow<TorrentFileUiState?>(null)
    val torrentFileUiState = _torrentFileState.asStateFlow()

    val isTorrentBookmarked: StateFlow<Boolean> =
        bookmarkRepository.getBookmarkIds()
            .map { torrent.id in it }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    val openTorrentDetailsInApp: StateFlow<Boolean> =
        settingsRepository.openTorrentDetailsInApp
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = true,
            )

    private var pendingTorrentFile: ByteArray? = null

    fun toggleBookmark(bookmark: Boolean) {
        val currentMagnetUriUiState = magnetUriUiState.value
        if (currentMagnetUriUiState !is MagnetUriUiState.Ready) {
            return
        }

        viewModelScope.launch {
            val magnetUri = currentMagnetUriUiState.magnetUri

            if (bookmark) {
                bookmarkRepository.createAndAddBookmark(
                    torrentId = torrent.id,
                    name = torrent.name,
                    magnetUri = magnetUri,
                    size = torrent.size,
                    seeders = torrent.peers,
                    peers = torrent.seeders,
                    providerName = torrent.providerName,
                    uploadDate = torrent.uploadDate,
                    category = torrent.category,
                    descriptionPageUrl = torrent.descriptionPageUrl,
                    fileDownloadLink = torrent.fileDownloadLink,
                )
            } else {
                bookmarkRepository.deleteBookmarkById(torrent.id)
            }
        }
    }


    fun downloadTorrentFile(magnetUri: String) {
        _torrentFileState.value = TorrentFileUiState.Downloading

        viewModelScope.launch {
            val downloadResult = if (torrent.fileDownloadLink != null) {
                torrentFileDownloader.download(torrent.fileDownloadLink)
            } else {
                val infoHash = TorrentUtils.getInfoHashFromMagnetUri(magnetUri)
                torrentFileDownloader.tryDownloadUsingInfoHash(infoHash)
            }

            when (downloadResult) {
                TorrentFileDownloadResult.Failed -> {
                    _torrentFileState.value = TorrentFileUiState.DownloadFailed
                }

                TorrentFileDownloadResult.FileNotFound -> {
                    _torrentFileState.value = TorrentFileUiState.FileNotFound
                }

                is TorrentFileDownloadResult.Success -> {
                    pendingTorrentFile = downloadResult.content

                    val fileName = torrent.name.replace(" ", "_")
                    _torrentFileState.value = TorrentFileUiState.DownloadComplete(fileName)
                }
            }
        }
    }

    fun writeTorrentFileContent(outputStream: OutputStream) {
        viewModelScope.launch {
            _torrentFileState.value = TorrentFileUiState.WritingContent

            outputStream.use {
                val currentPendingFile = pendingTorrentFile ?: return@use

                withContext(Dispatchers.IO) {
                    currentPendingFile.let(it::write)
                }
            }

            _torrentFileState.value = TorrentFileUiState.WriteComplete
        }
    }

    fun resetTorrentFileState() {
        _torrentFileState.value = null
    }
}