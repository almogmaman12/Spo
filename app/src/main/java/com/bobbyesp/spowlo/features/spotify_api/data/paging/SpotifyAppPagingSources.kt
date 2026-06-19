package com.bobbyesp.spowlo.features.spotify_api.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.adamratzman.spotify.SpotifyAppApi
import com.adamratzman.spotify.models.Artist
import com.adamratzman.spotify.models.PagingObject
import com.adamratzman.spotify.models.PlaylistTrack
import com.adamratzman.spotify.models.SearchFilter
import com.adamratzman.spotify.models.SimpleAlbum
import com.adamratzman.spotify.models.SimplePlaylist
import com.adamratzman.spotify.models.SimpleTrack
import com.adamratzman.spotify.models.Track
import com.bobbyesp.spowlo.features.spotify_api.data.remote.SpotifyApiRequests
import com.bobbyesp.spowlo.features.spotify_api.data.remote.YTMusicBridge
import com.bobbyesp.spowlo.utils.PreferencesUtil.getString
import com.bobbyesp.spowlo.utils.SEARCH_PROVIDER
import com.bobbyesp.spowlo.utils.SEARCH_PROVIDER_YTMUSIC

class TrackPagingSource(
    private var spotifyApi: SpotifyAppApi? = null,
    private var query: String,
    private val filters: List<SearchFilter> = emptyList(),
) : PagingSource<Int, Track>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val offset = params.key ?: 0


        val searchProvider = SEARCH_PROVIDER.getString()

        return try {
            val response = if (searchProvider == SEARCH_PROVIDER_YTMUSIC) {
                YTMusicBridge.searchTrack(
                    query = query,
                    limit = params.loadSize,
                    offset = offset
                )
            } else {
                if (spotifyApi == null) {
                    val api = SpotifyApiRequests
                    spotifyApi = api.provideSpotifyApi()
                }
                spotifyApi!!.search.searchTrack(
                    query = query,
                    limit = params.loadSize,
                    offset = offset,
                    market = null,
                    filters = filters
                )
            }


            if (response.isNotEmpty()) {
                val tracks = response.items

                LoadResult.Page(
                    data = tracks,
                    prevKey = if (offset > 0) offset - params.loadSize else null,
                    nextKey = if (tracks.isNotEmpty()) offset + params.loadSize else null
                )
            } else {
                LoadResult.Error(IllegalStateException("No tracks found"))
            }
        } catch (exception: Throwable) {
            LoadResult.Error(Exception(exception.message ?: "Unknown error", exception))
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Track>): Int? {
        return state.anchorPosition
    }
}

class SimpleAlbumPagingSource(
    private var spotifyApi: SpotifyAppApi? = null,
    private var query: String,
    private val filters: List<SearchFilter> = emptyList(),
) : PagingSource<Int, SimpleAlbum>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SimpleAlbum> {
        val offset = params.key ?: 0


        val searchProvider = SEARCH_PROVIDER.getString()

        return try {
            val response = if (searchProvider == SEARCH_PROVIDER_YTMUSIC) {
                // YTMusicBridge only maps Tracks for now, return empty for Albums to avoid crash
                PagingObject<SimpleAlbum>(
                    href = "",
                    items = emptyList(),
                    limit = params.loadSize,
                    offset = offset,
                    total = 0,
                    next = null,
                    previous = null
                )
            } else {
                if (spotifyApi == null) {
                    val api = SpotifyApiRequests
                    spotifyApi = api.provideSpotifyApi()
                }
                spotifyApi!!.search.searchAlbum(
                    query = query,
                    limit = params.loadSize,
                    offset = offset,
                    market = null,
                    filters = filters
                )
            }

            if (response.isNotEmpty()) {
                val albums = response.items

                LoadResult.Page(
                    data = albums,
                    prevKey = if (offset > 0) offset - params.loadSize else null,
                    nextKey = if (albums.isNotEmpty()) offset + params.loadSize else null
                )
            } else {
                LoadResult.Error(IllegalStateException("No albums found"))
            }
        } catch (exception: Throwable) {
            LoadResult.Error(Exception(exception.message ?: "Unknown error", exception))
        }
    }

    override fun getRefreshKey(state: PagingState<Int, SimpleAlbum>): Int? {
        return state.anchorPosition
    }
}

class AlbumTracksPagingSource(
    private var spotifyApi: SpotifyAppApi? = null,
    private var albumId: String,
) : PagingSource<Int, SimpleTrack>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SimpleTrack> {
        val offset = params.key ?: 0

        if (spotifyApi == null) {
            val api = SpotifyApiRequests
            spotifyApi = api.provideSpotifyApi()
        }

        return try {
            val response = spotifyApi!!.albums.getAlbumTracks(
                limit = params.loadSize,
                offset = offset,
                album = albumId,
                market = null,
            )

            if (response.isNotEmpty()) {
                val albums = response.items

                LoadResult.Page(
                    data = albums,
                    prevKey = if (offset > 0) offset - params.loadSize else null,
                    nextKey = if (albums.isNotEmpty()) offset + params.loadSize else null
                )
            } else {
                LoadResult.Error(IllegalStateException("No album tracks found"))
            }
        } catch (exception: Throwable) {
            LoadResult.Error(Exception(exception.message ?: "Unknown error", exception))
        }
    }

    override fun getRefreshKey(state: PagingState<Int, SimpleTrack>): Int? {
        return state.anchorPosition
    }
}

class ArtistsPagingSource(
    private var spotifyApi: SpotifyAppApi? = null,
    private var query: String,
    private val filters: List<SearchFilter> = emptyList(),
) : PagingSource<Int, Artist>() {
    override fun getRefreshKey(state: PagingState<Int, Artist>): Int? {
        return state.anchorPosition
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Artist> {
        val offset = params.key ?: 0


        val searchProvider = SEARCH_PROVIDER.getString()

        return try {
            val response = if (searchProvider == SEARCH_PROVIDER_YTMUSIC) {
                // YTMusicBridge only maps Tracks for now, return empty for Artists to avoid crash
                PagingObject<Artist>(
                    href = "",
                    items = emptyList(),
                    limit = params.loadSize,
                    offset = offset,
                    total = 0,
                    next = null,
                    previous = null
                )
            } else {
                if (spotifyApi == null) {
                    val api = SpotifyApiRequests
                    spotifyApi = api.provideSpotifyApi()
                }
                spotifyApi!!.search.searchArtist(
                    query = query,
                    limit = params.loadSize,
                    offset = offset,
                    market = null,
                    filters = filters
                )
            }

            if (response.isNotEmpty()) {
                val artists = response.items

                LoadResult.Page(
                    data = artists,
                    prevKey = if (offset > 0) offset - params.loadSize else null,
                    nextKey = if (artists.isNotEmpty()) offset + params.loadSize else null
                )
            } else {
                LoadResult.Error(IllegalStateException("No artists found"))
            }
        } catch (exception: Throwable) {
            LoadResult.Error(Exception(exception.message ?: "Unknown error", exception))
        }
    }
}

class SimplePlaylistPagingSource(
    private var spotifyApi: SpotifyAppApi? = null,
    private var query: String,
    private val filters: List<SearchFilter> = emptyList(),
) : PagingSource<Int, SimplePlaylist>() {
    override fun getRefreshKey(state: PagingState<Int, SimplePlaylist>): Int? {
        return state.anchorPosition
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SimplePlaylist> {
        val offset = params.key ?: 0


        val searchProvider = SEARCH_PROVIDER.getString()

        return try {
            val response = if (searchProvider == SEARCH_PROVIDER_YTMUSIC) {
                // YTMusicBridge only maps Tracks for now, return empty for Playlists to avoid crash
                PagingObject<SimplePlaylist>(
                    href = "",
                    items = emptyList(),
                    limit = params.loadSize,
                    offset = offset,
                    total = 0,
                    next = null,
                    previous = null
                )
            } else {
                if (spotifyApi == null) {
                    val api = SpotifyApiRequests
                    spotifyApi = api.provideSpotifyApi()
                }
                spotifyApi!!.search.searchPlaylist(
                    query = query,
                    limit = params.loadSize,
                    offset = offset,
                    market = null,
                    filters = filters
                )
            }

            if (response.isNotEmpty()) {
                val playlists = response.items

                LoadResult.Page(
                    data = playlists,
                    prevKey = if (offset > 0) offset - params.loadSize else null,
                    nextKey = if (playlists.isNotEmpty()) offset + params.loadSize else null
                )
            } else {
                LoadResult.Error(IllegalStateException("No playlists found"))
            }
        } catch (exception: Exception) {
            LoadResult.Error(exception)
        }
    }

}

class PlaylistTracksPagingSource(
    private var spotifyApi: SpotifyAppApi? = null,
    private var playlistId: String,
) : PagingSource<Int, PlaylistTrack>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PlaylistTrack> {
        val offset = params.key ?: 0

        if (spotifyApi == null) {
            val api = SpotifyApiRequests
            spotifyApi = api.provideSpotifyApi()
        }

        return try {
            val response = spotifyApi!!.playlists.getPlaylistTracks(
                limit = params.loadSize,
                offset = offset,
                playlist = playlistId,
                market = null,
            )

            if (response.isNotEmpty()) {
                val tracks = response.items

                LoadResult.Page(
                    data = tracks,
                    prevKey = if (offset > 0) offset - params.loadSize else null,
                    nextKey = if (tracks.isNotEmpty()) offset + params.loadSize else null
                )
            } else {
                LoadResult.Error(IllegalStateException("No album tracks found"))
            }
        } catch (exception: Exception) {
            LoadResult.Error(exception)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, PlaylistTrack>): Int? {
        return state.anchorPosition
    }
}

class PlaylistTracksAsTracksPagingSource(
    private var spotifyApi: SpotifyAppApi? = null,
    private var playlistId: String,
) : PagingSource<Int, Track>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Track> {
        val offset = params.key ?: 0

        if (spotifyApi == null) {
            val api = SpotifyApiRequests
            spotifyApi = api.provideSpotifyApi()
        }

        return try {
            val response = spotifyApi!!.playlists.getPlaylistTracks(
                limit = params.loadSize,
                offset = offset,
                playlist = playlistId,
                market = null,
            )

            if (response.isNotEmpty()) {
                val tracks = response.items.mapNotNull { it.track?.asTrack }

                LoadResult.Page(
                    data = tracks,
                    prevKey = if (offset > 0) offset - params.loadSize else null,
                    nextKey = if (tracks.isNotEmpty()) offset + params.loadSize else null
                )
            } else {
                LoadResult.Error(IllegalStateException("No album tracks found"))
            }
        } catch (exception: Exception) {
            LoadResult.Error(exception)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Track>): Int? {
        return state.anchorPosition
    }
}

class CustomPagingSource<T : Any>(
    private var pagingObject: PagingObject<T>,
) : PagingSource<Int, T>() {
    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        return state.anchorPosition
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val offset = params.key ?: 0

        return try {
            val items = pagingObject.items

            LoadResult.Page(
                data = items,
                prevKey = if (offset > 0) offset - params.loadSize else null,
                nextKey = if (items.isNotEmpty()) offset + params.loadSize else null
            )
        } catch (exception: Exception) {
            LoadResult.Error(exception)
        }
    }
}
