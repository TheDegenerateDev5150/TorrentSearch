package com.prajwalch.torrentsearch.ui.torrentdetails

import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.prajwalch.torrentsearch.data.repository.SettingsRepository
import com.prajwalch.torrentsearch.domain.SearchProvidersGateway
import com.prajwalch.torrentsearch.domain.TorrentFileDownloadResult
import com.prajwalch.torrentsearch.domain.TorrentFileDownloader
import com.prajwalch.torrentsearch.domain.model.GetTorrentDetailsResponse
import com.prajwalch.torrentsearch.domain.model.TorrentDetails
import com.prajwalch.torrentsearch.network.ConnectivityChecker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import org.koin.core.annotation.KoinViewModel

import java.io.IOException
import java.io.OutputStream

data class TorrentDetailsUiState(
    val state: TorrentDetailsState = TorrentDetailsState.Loading,
    val torrentFileState: TorrentFileState = TorrentFileState.Idle,
    val isRefreshing: Boolean = false,
    val blurNSFWImages: Boolean = true,
)

@Stable
sealed interface TorrentDetailsState {
    data object Loading : TorrentDetailsState
    data object NoInternetConnection : TorrentDetailsState
    data object Unavailable : TorrentDetailsState
    data class UnsupportedTorrentSite(val host: String) : TorrentDetailsState
    data class SomethingWentWrong(val message: String?) : TorrentDetailsState
    data class Available(val details: TorrentDetails) : TorrentDetailsState
}

sealed interface TorrentFileState {
    data object Idle : TorrentFileState
    data object Downloading : TorrentFileState
    data class DownloadComplete(val fileName: String) : TorrentFileState
    data object DownloadFailed : TorrentFileState
    data object FileNotFound : TorrentFileState
    data object WritingContent : TorrentFileState
    data object WriteComplete : TorrentFileState
}

@KoinViewModel
class TorrentDetailsViewModel(
    private val searchProvidersGateway: SearchProvidersGateway,
    private val torrentFileDownloader: TorrentFileDownloader,
    private val connectivityChecker: ConnectivityChecker,
    settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val detailsPageUrl: String = savedStateHandle["detailsPageUrl"]
        ?: error("TorrentDetailsViewModel can't function without details page URL")

    val providerName: String = savedStateHandle["providerName"]
        ?: error("TorrentDetailsViewModel can't function without provider name")

    private val isRefreshing = MutableStateFlow(false)
    private val detailsState = MutableStateFlow<TorrentDetailsState>(TorrentDetailsState.Loading)
    private val torrentFileState = MutableStateFlow<TorrentFileState>(TorrentFileState.Idle)
    private var pendingTorrentFile: ByteArray? = null

    val uiState = combine(
        detailsState,
        torrentFileState,
        isRefreshing,
        settingsRepository.blurNSFWImages,
        ::TorrentDetailsUiState
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TorrentDetailsUiState(),
    )

    init {
        loadDetails()
    }

    fun loadDetails() {
        viewModelScope.launch {
            detailsState.value = TorrentDetailsState.Loading
            detailsState.value = getTorrentDetails()
        }
    }

    fun refreshDetails() {
        isRefreshing.value = true
        viewModelScope.launch {
            val details = getTorrentDetails()
            if (details is TorrentDetailsState.Available) {
                detailsState.value = details
            }

            isRefreshing.value = false
        }
    }

    private suspend fun getTorrentDetails(): TorrentDetailsState = try {
        val response = searchProvidersGateway.getTorrentDetails(
            detailsPageUrl = detailsPageUrl,
            providerName = providerName,
        )

        when (response) {
            GetTorrentDetailsResponse.Unavailable -> {
                TorrentDetailsState.Unavailable
            }

            GetTorrentDetailsResponse.UnsupportedUrl -> {
                val uri = detailsPageUrl.toUri()
                TorrentDetailsState.UnsupportedTorrentSite(host = uri.host!!)
            }

            is GetTorrentDetailsResponse.Success -> {
                TorrentDetailsState.Available(response.details)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        if (!connectivityChecker.isInternetAvailable()) {
            TorrentDetailsState.NoInternetConnection
        } else {
            TorrentDetailsState.SomethingWentWrong(e.message)
        }
    } catch (e: Throwable) {
        TorrentDetailsState.SomethingWentWrong(e.message)
    }

    fun downloadTorrentFile(url: String?, infoHash: String, torrentName: String) {
        torrentFileState.value = TorrentFileState.Downloading

        viewModelScope.launch {
            val downloadResult = if (url != null) {
                torrentFileDownloader.download(url)
            } else {
                torrentFileDownloader.tryDownloadUsingInfoHash(infoHash)
            }

            when (downloadResult) {
                TorrentFileDownloadResult.Failed -> {
                    torrentFileState.value = TorrentFileState.DownloadFailed
                }

                TorrentFileDownloadResult.FileNotFound -> {
                    torrentFileState.value = TorrentFileState.FileNotFound
                }

                is TorrentFileDownloadResult.Success -> {
                    pendingTorrentFile = downloadResult.content
                    val fileName = torrentName.replace(" ", "_")

                    torrentFileState.value = TorrentFileState.DownloadComplete(fileName)
                }
            }
        }
    }

    fun writeTorrentFileContent(outputStream: OutputStream) {
        viewModelScope.launch {
            torrentFileState.value = TorrentFileState.WritingContent

            outputStream.use {
                val currentPendingFile = pendingTorrentFile ?: return@use

                withContext(Dispatchers.IO) {
                    currentPendingFile.let(it::write)
                }
            }

            torrentFileState.value = TorrentFileState.WriteComplete
        }
    }

    fun resetTorrentFileState() {
        torrentFileState.value = TorrentFileState.Idle
        pendingTorrentFile = null
    }
}