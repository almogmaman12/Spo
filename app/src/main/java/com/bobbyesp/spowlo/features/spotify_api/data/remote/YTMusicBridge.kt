package com.bobbyesp.spowlo.features.spotify_api.data.remote

import android.util.Log
import com.adamratzman.spotify.models.PagingObject
import com.adamratzman.spotify.models.Track
import com.bobbyesp.library.SpotDL
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

object YTMusicBridge {
    private val TAG = "YTMusicBridge"

    private val script = """
import sys
import json
import socket
from ytmusicapi import YTMusic

def main():
    if len(sys.argv) < 3:
        return
    
    # Prevent infinite hangs which exhaust IO threads
    socket.setdefaulttimeout(15)
    
    query = sys.argv[1]
    out_file = sys.argv[2]
    yt = YTMusic()
    try:
        results = yt.search(query, filter="songs")
        with open(out_file, 'w', encoding='utf-8') as f:
            json.dump(results, f)
    except Exception as e:
        with open(out_file, 'w', encoding='utf-8') as f:
            json.dump({"error": str(e)}, f)

if __name__ == "__main__":
    main()
"""

    private val jsonParser = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        coerceInputValues = true
    }

    fun searchTrack(query: String, limit: Int, offset: Int): PagingObject<Track> {
        val output = runScript(query)
        val ytTracks = JSONArray(output)
        
        val spotifyTracksJsonArray = JSONArray()
        
        for (i in 0 until ytTracks.length()) {
            val ytTrack = ytTracks.getJSONObject(i)
            val videoId = ytTrack.optString("videoId", "")
            if (videoId.isBlank()) continue
            
            val title = ytTrack.optString("title", "Unknown Track")
            
            // Map Artists
            val ytArtists = ytTrack.optJSONArray("artists") ?: JSONArray()
            val spotifyArtists = JSONArray()
            var primaryArtistName = "Unknown Artist"
            for (j in 0 until ytArtists.length()) {
                val ytArtist = ytArtists.getJSONObject(j)
                val artistName = ytArtist.optString("name", "Unknown Artist")
                if (j == 0) primaryArtistName = artistName
                val artistId = ytArtist.optString("id", videoId + "_artist")
                
                val spotifyArtist = JSONObject()
                spotifyArtist.put("id", artistId)
                spotifyArtist.put("name", artistName)
                spotifyArtist.put("type", "artist")
                spotifyArtist.put("uri", "spotify:artist:$artistId")
                spotifyArtist.put("href", "https://api.spotify.com/v1/artists/$artistId")
                spotifyArtist.put("external_urls", JSONObject().put("spotify", ""))
                spotifyArtists.put(spotifyArtist)
            }
            
            // Map Album
            val ytAlbum = ytTrack.optJSONObject("album")
            val albumName = ytAlbum?.optString("name") ?: title
            val albumId = ytAlbum?.optString("id") ?: (videoId + "_album")
            
            val spotifyAlbum = JSONObject()
            spotifyAlbum.put("id", albumId)
            spotifyAlbum.put("name", albumName)
            spotifyAlbum.put("type", "album")
            spotifyAlbum.put("album_type", "single")
            spotifyAlbum.put("uri", "spotify:album:$albumId")
            spotifyAlbum.put("href", "https://api.spotify.com/v1/albums/$albumId")
            spotifyAlbum.put("artists", spotifyArtists)
            spotifyAlbum.put("external_urls", JSONObject().put("spotify", ""))
            
            // Map Images
            val ytThumbnails = ytTrack.optJSONArray("thumbnails") ?: JSONArray()
            val spotifyImages = JSONArray()
            if (ytThumbnails.length() > 0) {
                // Get the best thumbnail (usually the last one)
                val bestThumb = ytThumbnails.getJSONObject(ytThumbnails.length() - 1)
                val img = JSONObject()
                img.put("url", bestThumb.optString("url"))
                img.put("width", bestThumb.optInt("width", 640))
                img.put("height", bestThumb.optInt("height", 640))
                spotifyImages.put(img)
            }
            spotifyAlbum.put("images", spotifyImages)
            
            val trackObj = JSONObject()
            trackObj.put("id", videoId)
            trackObj.put("name", title)
            trackObj.put("type", "track")
            trackObj.put("uri", "spotify:track:$videoId") // Dummy URI
            trackObj.put("href", "https://api.spotify.com/v1/tracks/$videoId")
            trackObj.put("artists", spotifyArtists)
            trackObj.put("album", spotifyAlbum)
            trackObj.put("duration_ms", ytTrack.optInt("duration_seconds", 180) * 1000)
            trackObj.put("explicit", ytTrack.optBoolean("isExplicit", false))
            trackObj.put("popularity", 50)
            trackObj.put("track_number", 1)
            trackObj.put("disc_number", 1)
            trackObj.put("is_playable", true)
            trackObj.put("external_ids", JSONObject().put("isrc", "YTMUSIC_$videoId"))
            // VERY IMPORTANT: By putting music.youtube.com URL in external_urls.spotify,
            // the downloader will directly pass this to spotDL, which parses YT Music natively!
            trackObj.put("external_urls", JSONObject().put("spotify", "https://music.youtube.com/watch?v=$videoId"))

            spotifyTracksJsonArray.put(trackObj)
        }
        
        val pagingObject = JSONObject()
        pagingObject.put("items", spotifyTracksJsonArray)
        pagingObject.put("limit", 20)
        pagingObject.put("offset", offset)
        pagingObject.put("total", spotifyTracksJsonArray.length())
        pagingObject.put("href", "")
        pagingObject.put("next", null)
        pagingObject.put("previous", null)
        
        return jsonParser.decodeFromString(pagingObject.toString())
    }

    private fun runScript(query: String): String {
        Log.d(TAG, "Searching YTMusic for $query")
        
        // We write the output to a temp file because printing a massive 100KB+ JSON string
        // to stdout triggers catastrophic backtracking in SpotDLCore's StreamProcessExtractor Regex,
        // which causes the Android VM to instantly crash and die (black screen).
        val tempFile = java.io.File.createTempFile("ytmusic_search", ".json")
        
        try {
            SpotDL.getInstance().executePythonScript(
                script,
                query,
                tempFile.absolutePath
            )
            
            val output = tempFile.readText()
            if (output.contains("\"error\":")) {
                throw Exception("YTMusic error: $output")
            }
            if (output.isBlank()) return "[]"
            return output
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
