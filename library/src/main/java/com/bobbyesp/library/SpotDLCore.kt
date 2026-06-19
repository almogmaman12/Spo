package com.bobbyesp.library

import android.content.Context
import android.os.Build
import android.util.Log
import com.bobbyesp.library.data.local.streams.StreamGobbler
import com.bobbyesp.library.data.local.streams.StreamProcessExtractor
import com.bobbyesp.library.data.remote.SpotDLDependencyUpdater
import com.bobbyesp.library.data.remote.SpotDLUpdater
import com.bobbyesp.library.domain.UpdateStatus
import com.bobbyesp.library.domain.model.SpotifySong
import com.bobbyesp.library.util.exceptions.CanceledException
import com.bobbyesp.library.util.exceptions.SpotDLException
import com.bobbyesp.spotdl_common.Constants
import com.bobbyesp.spotdl_common.Constants.LIBRARY_NAME
import com.bobbyesp.spotdl_common.Constants.PACKAGES_ROOT_NAME
import com.bobbyesp.spotdl_common.SharedPrefsHelper
import com.bobbyesp.spotdl_common.domain.Dependency
import com.bobbyesp.spotdl_common.domain.model.DownloadedDependencies
import com.bobbyesp.spotdl_common.utils.dependencies.dependencyDownloadCallback
import com.bobbyesp.spotdl_common.utils.files.FilesUtil.ensure
import com.bobbyesp.spotdl_common.utils.json
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID

abstract class SpotDLCore {
    private var initialized = false
    protected lateinit var binariesDirectory: File

    private var pythonPath: File? = null
    private var ffmpegPath: File? = null
    private lateinit var spotdlPath: File

    // Custom site‑packages directory used for injected Python packages
    private lateinit var customSitePackages: File

    /* ENVIRONMENT VARIABLES */
    private lateinit var ENV_LD_LIBRARY_PATH: String
    private lateinit var ENV_SSL_CERT_FILE: String
    private lateinit var ENV_PYTHONHOME: String
    private lateinit var ENV_PYTHONPATH: String
    private lateinit var HOME: String
    private lateinit var LDFLAGS: String
    private lateinit var TMPDIR: String

    private val pythonLibVersion = "pythonLibVersion"

    //Map of process id associated with the process
    protected open val idProcessMap: MutableMap<String, Process> =
        Collections.synchronizedMap(HashMap<String, Process>())

    internal val isDebug = BuildConfig.DEBUG
    private val TAG = "SpowloDebug"

    open fun init(context: Context) {
        if (initialized) return
        if (isDebug) Log.d(TAG, ">>> SpotDLCore: Initializing...")

        val baseDirectory = File(context.noBackupFilesDir, LIBRARY_NAME).ensure()
        val packagesDir = File(baseDirectory, PACKAGES_ROOT_NAME)

        //Here are all the binaries provided by the jniLibs folder
        binariesDirectory = File(context.applicationInfo.nativeLibraryDir)

        // Dependencies binaries path (.so files)
        pythonPath = File(binariesDirectory, Constants.BinariesName.PYTHON)
        ffmpegPath = File(binariesDirectory, Constants.BinariesName.FFMPEG)

        // Dependencies packages directory (where they are extracted - .zip files)
        val pythonDir = File(packagesDir, Constants.DirectoriesName.PYTHON)
        val ffmpegDir = File(packagesDir, Constants.DirectoriesName.FFMPEG)

        val spotdlDir = File(baseDirectory, Constants.DirectoriesName.SPOTDL)
        spotdlPath = File(spotdlDir, Constants.BinariesName.SPOTDL)

        // Set environment variables
        ENV_LD_LIBRARY_PATH =
            pythonDir.absolutePath + "/usr/lib" + ":" + ffmpegDir.absolutePath + "/usr/lib"
        ENV_SSL_CERT_FILE = pythonDir.absolutePath + "/usr/etc/tls/cert.pem"
        ENV_PYTHONHOME = pythonDir.absolutePath + "/usr"
        
        val usrLib = File(pythonDir, "usr/lib")
        val pythonVerDir = usrLib.listFiles { f -> f.isDirectory && f.name.startsWith("python3") }?.firstOrNull() 
            ?: File(usrLib, "python3.11") // Fallback
        val baseSitePackages = File(pythonVerDir, "site-packages")
        
        // Define Custom Site Packages (Safe from resets)
        val baseLibDir = File(context.noBackupFilesDir, LIBRARY_NAME)
        customSitePackages = File(baseLibDir, "custom_site_packages")

        if (isDebug) {
            Log.d(TAG, ">>> SpotDLCore: Base Python Dir: ${pythonVerDir.absolutePath}")
            Log.d(TAG, ">>> SpotDLCore: Custom SitePackages: ${customSitePackages.absolutePath}")
        }
        
        // Add custom site-packages FIRST so updated libs override built-in ones
        ENV_PYTHONPATH = customSitePackages.absolutePath + ":" + 
                         baseSitePackages.absolutePath + ":" + 
                         pythonVerDir.absolutePath
        
        HOME = baseDirectory.absolutePath
        TMPDIR = context.cacheDir.absolutePath
        LDFLAGS = "-L" + pythonDir.absolutePath + "/usr/lib -rdynamic"

        try {
            initPython(context, pythonDir)
            initSpotDL(context, spotdlDir)
        } catch (e: Exception) {
            Log.e(TAG, ">>> SpotDLCore: Error initializing", e)
            throw SpotDLException("Error initializing SpotDLCore", e)
        }
        initialized = true
        if (isDebug) Log.d(TAG, ">>> SpotDLCore: Initialization Complete.")
    }

    @Throws(IllegalStateException::class)
    abstract fun ensureDependencies(
        appContext: Context,
        skipDependencies: List<Dependency> = emptyList(),
        callback: dependencyDownloadCallback? = null
    ): DownloadedDependencies?

    internal abstract fun initPython(appContext: Context, pythonDir: File)

    @Throws(SpotDLException::class)
    fun initSpotDL(appContext: Context, spotDlDir: File) {
        if (!spotDlDir.exists()) spotDlDir.mkdirs()
        val spotDlBinary = File(spotDlDir, Constants.BinariesName.SPOTDL)
        
        // CHECK IF FILE EXISTS AND SIZE IS 0 (CORRUPT)
        if (!spotDlBinary.exists() || spotDlBinary.length() == 0L) {
            try {
                if (isDebug) Log.d(TAG, ">>> SpotDLCore: Extracting internal spotDL binary...")
                val inputStream =
                    appContext.resources.openRawResource(R.raw.spotdl)
                FileUtils.copyInputStreamToFile(inputStream, spotDlBinary)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(spotDlDir)
                throw SpotDLException("Error extracting SpotDL source files", e)
            }
        }
    }

    /**
     * Checks if a process with the given id is running and destroys it
     * @param id the process id
     * @return true if the process was destroyed successfully, false otherwise
     */
    fun destroyProcessById(id: String): Boolean {
        if (isDebug) {
            Log.d("SpotDL", "Destroying process $id")
        }
        if (idProcessMap.containsKey(id)) {
            val p = idProcessMap[id]
            var alive = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                alive = p!!.isAlive
            }
            if (alive) {
                p!!.destroy()
                idProcessMap.remove(id)
                return true
            }
        }
        return false
    }

    @Throws(SpotDLException::class)
    fun updateSpotDL(appContext: Context): UpdateStatus? {
        assertInit()
        return try {
            SpotDLUpdater.update(appContext)
        } catch (e: IOException) {
            throw SpotDLException("Failed to update the spotDL library.", e)
        }
    }

    @Throws(SpotDLException::class)
    suspend fun updatePythonDependencies(appContext: Context): String {
        assertInit()
        return try {
            SpotDLDependencyUpdater.updateVolatileDependencies(appContext)
        } catch (e: Exception) {
            throw SpotDLException("Failed to update Python dependencies.", e)
        }
    }

    fun version(appContext: Context): String? {
        return SpotDLUpdater.version(appContext)
    }

    @JvmOverloads
    @Throws(SpotDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: SpotDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null,
    ): SpotDLResponse {
        assertInit()
        if (processId != null && idProcessMap.containsKey(processId)) throw SpotDLException("Process ID already exists")

        request.addOption("--ffmpeg", ffmpegPath!!.absolutePath)
        
        if (!request.hasOption("--cache-path") || request.getOption("--cache-dir") == null || request.hasOption(
                "--use-cache-file"
            )
        ) {
            request.addOption("--no-cache")
        }

        val spotdlResponse: SpotDLResponse
        val process: Process
        val exitCode: Int
        val outBuffer = StringBuilder() //stdout
        val errBuffer = StringBuilder() //stderr
        val startTime = System.currentTimeMillis()
        val args = request.buildCommand()
        val command: MutableList<String?> = ArrayList()

        // --- CRITICAL FIX: INVOKE AS MODULE ---
        // Instead of executing the spotdl binary directly (which forces sys.path prepending),
        // execute python with "-m spotdl" to respect our custom PYTHONPATH.
        command.add(pythonPath!!.absolutePath)
        command.add("-m")
        command.add("spotdl")
        
        command.addAll(args)

        val processBuilder = ProcessBuilder(command)

        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
            this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
            this["PATH"] = System.getenv("PATH") + ":" + binariesDirectory.absolutePath
            this["PYTHONHOME"] = ENV_PYTHONHOME
            this["PYTHONPATH"] = ENV_PYTHONPATH
            this["HOME"] = HOME 
            this["LDFLAGS"] = LDFLAGS
            this["TERM"] = "xterm-256color"
            this["FORCE_COLOR"] = "true"
        }
        
        if (isDebug) {
            Log.d(TAG, ">>> EXECUTE CMD: ${command.joinToString(" ")}")
            Log.d(TAG, ">>> EXECUTE ENV PYTHONPATH: $ENV_PYTHONPATH")
        }

        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw SpotDLException(e)
        }

        if (processId != null) {
            idProcessMap[processId] = process
        }
        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdOutProcessor = StreamProcessExtractor(outBuffer, outStream, callback)
        val stdErrProcessor = StreamGobbler(errBuffer, errStream)

        exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroy()
            if (processId != null) idProcessMap.remove(processId)
            throw e
        }

        val out = outBuffer.toString()
        val err = errBuffer.toString()

        if (exitCode > 0) {
            if (processId != null && !idProcessMap.containsKey(processId)) throw CanceledException()
            if (!ignoreErrors(request, out)) {
                idProcessMap.remove(processId)
                Log.e("SpotDL", "Error occurred. $err, $out, $exitCode")
                throw SpotDLException(err)
            }
        }
        idProcessMap.remove(processId)

        val elapsedTime = System.currentTimeMillis() - startTime
        spotdlResponse = SpotDLResponse(command, exitCode, elapsedTime, out, err)

        return spotdlResponse
    }

    @Throws(SpotDLException::class, InterruptedException::class, CanceledException::class)
    fun executePythonScript(script: String, vararg args: String): String {
        assertInit()

        val process: Process
        val exitCode: Int
        val outBuffer = StringBuilder() //stdout
        val errBuffer = StringBuilder() //stderr

        val command: MutableList<String?> = ArrayList()
        command.add(pythonPath!!.absolutePath)
        command.add("-c")
        command.add(script)
        command.addAll(args)

        val processBuilder = ProcessBuilder(command)

        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
            this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
            this["PATH"] = System.getenv("PATH") + ":" + binariesDirectory.absolutePath
            this["PYTHONHOME"] = ENV_PYTHONHOME
            this["PYTHONPATH"] = ENV_PYTHONPATH
            this["HOME"] = HOME 
            this["LDFLAGS"] = LDFLAGS
            this["TERM"] = "xterm-256color"
            this["FORCE_COLOR"] = "true"
        }

        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw SpotDLException(e)
        }

        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdOutProcessor = StreamProcessExtractor(outBuffer, outStream, null)
        val stdErrProcessor = StreamGobbler(errBuffer, errStream)

        exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroy()
            throw e
        }

        val out = outBuffer.toString()
        val err = errBuffer.toString()

        if (exitCode > 0) {
            Log.e("SpotDLCore", "Python script error: $err")
            throw SpotDLException(err)
        }

        return out
    }

    @Throws(SpotDLException::class, InterruptedException::class, CanceledException::class)
    fun getSongInfo(
        url: String,
        songId: String = UUID.randomUUID().toString(),
        extraArguments: Map<String, String>? = null
    ): List<SpotifySong> {
        assertInit()
        val metadataDirectory = File("$HOME/.spotdl/meta_info/").ensure()
        val metadataFile = File(metadataDirectory, "$songId.spotdl")
        
        val request = SpotDLRequest()
        request.addOption("save", url)
        request.addOption("--save-file", metadataFile.absolutePath)
        extraArguments?.forEach { (key, value) -> request.addOption(key, value) }

        if (!request.hasOption("--client-id") || !request.hasOption("--client-secret")) {
            request.addOption("--client-id", BuildConfig.CLIENT_ID)
            request.addOption("--client-secret", BuildConfig.CLIENT_SECRET)
        }
        execute(request, songId, null)

        val spotifySongInfo: List<SpotifySong>?

        try {
            spotifySongInfo = json.decodeFromString<List<SpotifySong>>(metadataFile.readText())
        } catch (e: Exception) {
            throw SpotDLException("Failed to read/parse the metadata file", e)
        }

        return spotifySongInfo
    }

    private fun ignoreErrors(request: SpotDLRequest, out: String): Boolean {
        return out.isNotEmpty() && !request.hasOption("--print-errors")
    }

    @Throws(SpotDLException::class)
    private fun assertInit() {
        check(initialized) { "The SpotDL instance is not initialized" }

        // --- 1. Create dummy SpotipyFree stub ---
        try {
            val spotipyDir = java.io.File(customSitePackages, "SpotipyFree")
            if (spotipyDir.exists()) spotipyDir.deleteRecursively()
            spotipyDir.mkdirs()
            java.io.File(spotipyDir, "__init__.py").writeText("from .Spotify import Spotify\n__all__ = ['Spotify']\n# Dummy SpotipyFree package\n")
            java.io.File(spotipyDir, "Spotify.py").writeText(
                "class Spotify:\n" +
                "    def __init__(self, *args, **kwargs):\n" +
                "        pass\n" +
                "\n" +
                "    def track(self, url):\n" +
                "        track_id = url.split('/')[-1].split('?')[0]\n" +
                "        return {\n" +
                "            'id': track_id,\n" +
                "            'name': 'Unknown Track',\n" +
                "            'duration_ms': 180000,\n" +
                "            'disc_number': 1,\n" +
                "            'track_number': 1,\n" +
                "            'explicit': False,\n" +
                "            'popularity': 0,\n" +
                "            'is_playable': True,\n" +
                "            'external_ids': {'isrc': None},\n" +
                "            'external_urls': {'spotify': url},\n" +
                "            'artists': [{\n" +
                "                'id': 'mock_artist_id',\n" +
                "                'name': 'Unknown Artist',\n" +
                "                'external_urls': {'spotify': ''},\n" +
                "            }],\n" +
                "            'album': {\n" +
                "                'id': 'mock_album_id',\n" +
                "                'name': 'Unknown Album',\n" +
                "                'album_type': 'album',\n" +
                "                'release_date': '2020-01-01',\n" +
                "                'total_tracks': 1,\n" +
                "                'images': [{'url': None, 'height': 640, 'width': 640}],\n" +
                "                'artists': [{'name': 'Unknown Artist', 'id': 'mock_artist_id'}],\n" +
                "                'copyrights': [],\n" +
                "                'label': '',\n" +
                "            },\n" +
                "        }\n" +
                "\n" +
                "    def artist(self, artist_id):\n" +
                "        return {\n" +
                "            'id': artist_id or 'mock_artist_id',\n" +
                "            'name': 'Unknown Artist',\n" +
                "            'genres': [],\n" +
                "            'popularity': 0,\n" +
                "            'external_urls': {'spotify': ''},\n" +
                "        }\n" +
                "\n" +
                "    def album(self, album_id):\n" +
                "        return {\n" +
                "            'id': album_id or 'mock_album_id',\n" +
                "            'name': 'Unknown Album',\n" +
                "            'album_type': 'album',\n" +
                "            'release_date': '2020-01-01',\n" +
                "            'total_tracks': 1,\n" +
                "            'images': [{'url': None, 'height': 640, 'width': 640}],\n" +
                "            'artists': [{'name': 'Unknown Artist', 'id': 'mock_artist_id'}],\n" +
                "            'copyrights': [],\n" +
                "            'label': '',\n" +
                "            'tracks': {'items': [], 'total': 1},\n" +
                "        }\n"
            )
        } catch (e: Exception) {
            android.util.Log.e("SpotDLCore", "Failed to create SpotipyFree stub", e)
        }

        // --- 2. Delete spotapi (uses curl_cffi) ---
        try {
            val spotapiPath = java.io.File(customSitePackages, "spotapi")
            if (spotapiPath.exists()) {
                if (spotapiPath.isDirectory) spotapiPath.deleteRecursively()
                else spotapiPath.delete()
            }
        } catch (_: Exception) {}

        // --- 3. Create dummy curl_cffi stub (safety net for any remaining imports) ---
        try {
            val curlCffiDir = java.io.File(customSitePackages, "curl_cffi")
            // Always recreate to ensure __version__ is present (silences yt_dlp warning)
            if (!curlCffiDir.exists()) curlCffiDir.mkdirs()
            java.io.File(curlCffiDir, "__init__.py")
                .writeText("# Dummy curl_cffi - Android compatibility\n__version__ = '0.0.0'\n")
            java.io.File(curlCffiDir, "requests.py")
                .writeText(
                    "class Session:\n" +
                    "    def __init__(self, *args, **kwargs): pass\n" +
                    "    def get(self, *args, **kwargs):\n" +
                    "        raise NotImplementedError('curl_cffi unavailable on Android')\n" +
                    "    def post(self, *args, **kwargs):\n" +
                    "        raise NotImplementedError('curl_cffi unavailable on Android')\n"
                )
        } catch (e: Exception) {
            android.util.Log.e("SpotDLCore", "Failed to create curl_cffi stub", e)
        }

        // --- 4. Patch spotdl source files on every init ---
        // The patch is idempotent (safe to run multiple times).
        // Running it every time ensures corrupted files from previous bad runs are always fixed.
        try {
            val spotdlDir = java.io.File(customSitePackages, "spotdl")
            if (spotdlDir.exists()) {
                patchSpotdlSourceFiles(customSitePackages)
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotDLCore", "Failed to patch spotdl source files", e)
        }

    }

    /**
     * Patches spotdl Python source files to remove providers that require curl_cffi.
     * Safe to call multiple times (idempotent).
     */
    private fun patchSpotdlSourceFiles(sitePackages: java.io.File) {
        // --- audio/__init__.py: remove SoundCloud import + __all__ entry ---
        try {
            val audioInit = java.io.File(sitePackages, "spotdl/providers/audio/__init__.py")
            if (audioInit.exists()) {
                var text = audioInit.readText()
                val original = text
                text = text.replace(
                    Regex("""^from spotdl\.providers\.audio\.soundcloud import SoundCloud\s*\n""", RegexOption.MULTILINE), ""
                )
                text = text.replace(
                    Regex("""^\s*["']SoundCloud["']\s*,?\s*\n""", RegexOption.MULTILINE), ""
                )
                if (text != original) {
                    audioInit.writeText(text)
                    android.util.Log.d("SpotDLCore", "Patched audio/__init__.py")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotDLCore", "Failed to patch audio/__init__.py", e)
        }

        // --- console/web.py: stub out the entire web UI module ---
        // spotdl/console/web.py imports datastar_py + fastapi (web server deps) that don't
        // exist on Android. We replace it with a minimal stub that exports the `web` function
        // that entry_point.py expects, so the import chain doesn't crash.
        try {
            val consoleWebFile = java.io.File(sitePackages, "spotdl/console/web.py")
            if (consoleWebFile.exists()) {
                val stub = "# Stubbed for Android - web UI not supported\n" +
                    "def web(*args, **kwargs):\n" +
                    "    raise NotImplementedError('Web UI is not supported on Android')\n"
                // Only overwrite if not already stubbed (idempotent)
                if (!consoleWebFile.readText().startsWith("# Stubbed for Android")) {
                    consoleWebFile.writeText(stub)
                    android.util.Log.d("SpotDLCore", "Stubbed console/web.py")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotDLCore", "Failed to stub console/web.py", e)
        }

        // --- download/downloader.py: recover + rewrite import block + remove dict entries ---
        try {
            val downloader = java.io.File(sitePackages, "spotdl/download/downloader.py")
            if (downloader.exists()) {
                var text = downloader.readText()

                // STEP 1: Recover from old safety-pass corruption.
                // Previous code prepended "# " and appended "  # DISABLED: Android" to any line
                // containing SoundCloud/Piped — even inside multi-line expressions, breaking indentation.
                // Restore those lines to their original content first.
                text = text.lines().joinToString("\n") { line ->
                    if (line.trimEnd().endsWith("# DISABLED: Android")) {
                        // Strip the leading "# " prefix and trailing "  # DISABLED: Android"
                        line.replaceFirst("# ", "").removeSuffix("  # DISABLED: Android")
                    } else {
                        line
                    }
                }

                // STEP 2: Rewrite the entire audio import block cleanly (removes SoundCloud + Piped).
                text = text.replace(
                    Regex("""from spotdl\.providers\.audio import \((.*?)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
                ) { match ->
                    val names = match.groupValues[1].lines()
                        .map { it.trim().removeSuffix(",").trim() }
                        .filter { n -> n.isNotEmpty() && n != "SoundCloud" && n != "Piped" && !n.startsWith("#") }
                    "from spotdl.providers.audio import (\n" +
                        names.joinToString(",\n") { "    $it" } + ",\n)"
                }

                // STEP 3: Remove AUDIO_PROVIDERS dict entries for removed providers.
                // Match the whole line including its newline to avoid blank lines.
                text = text.replace(
                    Regex("""^[^\S\n]*["']soundcloud["'][^\S\n]*:[^\S\n]*SoundCloud[^\S\n]*,?[^\S\n]*\n""", RegexOption.MULTILINE), ""
                )
                text = text.replace(
                    Regex("""^[^\S\n]*["']piped["'][^\S\n]*:[^\S\n]*Piped[^\S\n]*,?[^\S\n]*\n""", RegexOption.MULTILINE), ""
                )

                downloader.writeText(text)
                android.util.Log.d("SpotDLCore", "Patched download/downloader.py")
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotDLCore", "Failed to patch downloader.py", e)
        }

        // --- song.py: write a complete safe replacement (avoids all indentation/regex issues) ---
        try {
            val songFile = java.io.File(sitePackages, "spotdl/types/song.py")
            if (songFile.exists()) {
                val nl = "\n"
                val i1 = "    "      // 4 spaces
                val i2 = "        "  // 8 spaces
                val i3 = "            " // 12 spaces
                val safeSongPy = buildString {
                    append("\"\"\"${nl}Song module that holds the Song and SongList classes.${nl}\"\"\"${nl}${nl}")
                    append("import json${nl}")
                    append("from dataclasses import asdict, dataclass${nl}")
                    append("from typing import Any, Dict, List, Optional, Tuple, Set${nl}${nl}")
                    append("from spotdl.utils.spotify import SpotifyClient${nl}${nl}")
                    append("__all__ = [\"Song\", \"SongList\", \"SongError\", \"SongListError\"]${nl}${nl}${nl}")

                    // SongError
                    append("class SongError(Exception):${nl}")
                    append("${i1}\"\"\"Base class for all exceptions related to songs.\"\"\"${nl}${nl}${nl}")

                    // SongListError
                    append("class SongListError(Exception):${nl}")
                    append("${i1}\"\"\"Base class for all exceptions related to song lists.\"\"\"${nl}${nl}${nl}")

                    // Song dataclass
                    append("@dataclass${nl}")
                    append("class Song:${nl}")
                    append("${i1}name: str${nl}")
                    append("${i1}artists: List[str]${nl}")
                    append("${i1}artist: str${nl}")
                    append("${i1}genres: List[str]${nl}")
                    append("${i1}disc_number: int${nl}")
                    append("${i1}disc_count: int${nl}")
                    append("${i1}album_name: str${nl}")
                    append("${i1}album_artist: str${nl}")
                    append("${i1}duration: int${nl}")
                    append("${i1}year: int${nl}")
                    append("${i1}date: str${nl}")
                    append("${i1}track_number: int${nl}")
                    append("${i1}tracks_count: int${nl}")
                    append("${i1}song_id: str${nl}")
                    append("${i1}explicit: bool${nl}")
                    append("${i1}publisher: str${nl}")
                    append("${i1}url: str${nl}")
                    append("${i1}isrc: Optional[str]${nl}")
                    append("${i1}cover_url: Optional[str]${nl}")
                    append("${i1}copyright_text: Optional[str]${nl}")
                    append("${i1}download_url: Optional[str] = None${nl}")
                    append("${i1}lyrics: Optional[str] = None${nl}")
                    append("${i1}popularity: Optional[int] = None${nl}")
                    append("${i1}album_id: Optional[str] = None${nl}")
                    append("${i1}list_name: Optional[str] = None${nl}")
                    append("${i1}list_url: Optional[str] = None${nl}")
                    append("${i1}list_position: Optional[int] = None${nl}")
                    append("${i1}list_length: Optional[int] = None${nl}")
                    append("${i1}artist_id: Optional[str] = None${nl}")
                    append("${i1}album_type: Optional[str] = None${nl}${nl}")

                    // from_url classmethod
                    append("${i1}@classmethod${nl}")
                    append("${i1}def from_url(cls, url: str) -> \"Song\":${nl}")
                    append("${i2}spotify_client = SpotifyClient()${nl}")
                    append("${i2}raw_track_meta = spotify_client.track(url)${nl}")
                    append("${i2}if raw_track_meta is None:${nl}")
                    append("${i3}raise SongError(\"Couldn't get metadata\")${nl}")
                    append("${i2}artists_list = raw_track_meta.get(\"artists\") or []${nl}")
                    append("${i2}primary_artist_id = artists_list[0].get(\"id\", \"\") if artists_list else \"\"${nl}")
                    append("${i2}raw_artist_meta = spotify_client.artist(primary_artist_id) if primary_artist_id else {}${nl}")
                    append("${i2}embedded_album = raw_track_meta.get(\"album\") or {}${nl}")
                    append("${i2}album_id = embedded_album.get(\"id\", \"\")${nl}")
                    append("${i2}raw_album_meta = spotify_client.album(album_id) if album_id else {}${nl}")
                    append("${i2}album_name = raw_album_meta.get(\"name\") or embedded_album.get(\"name\") or \"Unknown Album\"${nl}")
                    append("${i2}album_artists = raw_album_meta.get(\"artists\") or embedded_album.get(\"artists\") or []${nl}")
                    append("${i2}album_artist = album_artists[0].get(\"name\", \"Unknown Artist\") if album_artists else \"Unknown Artist\"${nl}")
                    append("${i2}release_date = raw_album_meta.get(\"release_date\") or embedded_album.get(\"release_date\") or \"2020-01-01\"${nl}")
                    append("${i2}images = raw_album_meta.get(\"images\") or embedded_album.get(\"images\") or []${nl}")
                    append("${i2}cover_url = images[0].get(\"url\") if images else None${nl}")
                    append("${i2}artists = [a.get(\"name\", \"\") for a in artists_list]${nl}")
                    append("${i2}return cls(${nl}")
                    append("${i3}name=raw_track_meta.get(\"name\", \"Unknown Track\"), artists=artists, artist=artists[0] if artists else \"Unknown Artist\",${nl}")
                    append("${i3}genres=raw_artist_meta.get(\"genres\") or [], disc_number=raw_track_meta.get(\"disc_number\") or 1,${nl}")
                    append("${i3}disc_count=raw_album_meta.get(\"total_tracks\") or 1, album_name=album_name, album_artist=album_artist,${nl}")
                    append("${i3}duration=(raw_track_meta.get(\"duration_ms\") or 0) // 1000, year=int(str(release_date)[:4]), date=str(release_date),${nl}")
                    append("${i3}track_number=raw_track_meta.get(\"track_number\") or 1, tracks_count=raw_album_meta.get(\"total_tracks\") or 1,${nl}")
                    append("${i3}song_id=raw_track_meta.get(\"id\", \"\"), explicit=bool(raw_track_meta.get(\"explicit\", False)), publisher=\"\", url=url,${nl}")
                    append("${i3}isrc=raw_track_meta.get(\"external_ids\", {}).get(\"isrc\"), cover_url=cover_url, copyright_text=None, album_id=album_id, artist_id=primary_artist_id${nl}")
                    append("${i2})${nl}${nl}")

                    // from_dict
                    append("${i1}@classmethod${nl}")
                    append("${i1}def from_dict(cls, data: Dict[str, Any]) -> \"Song\":${nl}")
                    append("${i2}return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})${nl}${nl}")

                    // to_dict
                    append("${i1}def to_dict(self) -> Dict[str, Any]:${nl}")
                    append("${i2}return asdict(self)${nl}${nl}")

                    // json property
                    append("${i1}@property${nl}")
                    append("${i1}def json(self) -> str:${nl}")
                    append("${i2}return json.dumps(self.to_dict(), indent=4, ensure_ascii=False)${nl}${nl}")

                    // MISSING STUB METHODS TO PREVENT SUB-MODULE CRASHES
                    append("${i1}@classmethod${nl}")
                    append("${i1}def from_search(cls, query: str) -> \"Song\":${nl}")
                    append("${i2}raise NotImplementedError(\"Search not supported via direct song types\")${nl}${nl}")
                    append("${i1}@classmethod${nl}")
                    append("${i1}def from_missing(cls, name: str, artist: str) -> \"Song\":${nl}")
                    append("${i2}raise NotImplementedError(\"Missing track compilation not supported\")${nl}${nl}${nl}")

                    // MISSING SONGLIST CLASS DEFINITION
                    append("class SongList:${nl}")
                    append("${i1}def __init__(self, name: str, url: str, songs: List[Song]) -> None:${nl}")
                    append("${i2}self.name = name${nl}")
                    append("${i2}self.url = url${nl}")
                    append("${i2}self.songs = songs${nl}")
                    append("${i2}self.urls = [song.url for song in songs]${nl}")
                }
                songFile.writeText(safeSongPy)
                android.util.Log.d("SpotDLCore", "Rewrote song.py safely with all classes included")
            }
        } catch (e: Exception) {
            android.util.Log.e("SpotDLCore", "Failed to rewrite song.py", e)
        }
    }

    fun updatePython(appContext: Context, version: String) {
        SharedPrefsHelper.update(
            appContext, pythonLibVersion, version
        )
    }

    fun shouldUpdatePython(appContext: Context, version: String): Boolean {
        return version != SharedPrefsHelper[appContext, pythonLibVersion]
    }
}