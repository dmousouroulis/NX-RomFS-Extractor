package io.github.dmousouroulis.nxromfsextractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RomFsExtractorActivity : AppCompatActivity() {

    private var baseUri: Uri? = null
    private var updateUri: Uri? = null
    private var selectedRomFsPath: String? = null
    private var outputFolderUri: Uri? = null
    private var cachedRomFsFiles: List<String> = emptyList()

    private lateinit var baseStatus: TextView
    private lateinit var updateStatus: TextView
    private lateinit var keysStatus: TextView
    private lateinit var selectedFileStatus: TextView
    private lateinit var outputFolderStatus: TextView
    private lateinit var browseButton: Button
    private lateinit var outputFolderButton: Button
    private lateinit var extractButton: Button
    private lateinit var resultStatus: TextView
    private lateinit var progress: ProgressBar

    private val basePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistReadPermission(it)
            baseUri = it
            baseStatus.text = "Base package: ${displayName(it)}"
            invalidateRomFsSelection()
            updateControlState()
        }
    }

    private val updatePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistReadPermission(it)
            updateUri = it
            updateStatus.text = "Update package: ${displayName(it)}"
            invalidateRomFsSelection()
            updateControlState()
        }
    }

    private val keysPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            lifecycleScope.launch {
                keysStatus.text = "Importing prod.keys…"
                val result = withContext(Dispatchers.IO) {
                    ProdKeysManager.getInstance(this@RomFsExtractorActivity).saveProdKeysFile(it)
                }
                keysStatus.text = when (result) {
                    is KeysResult.Success -> "prod.keys: loaded"
                    is KeysResult.InvalidFile -> "prod.keys: invalid file"
                    is KeysResult.NotFoundInZip -> "prod.keys: not found"
                    is KeysResult.Error -> "prod.keys: ${result.message}"
                }
                invalidateRomFsSelection()
                updateControlState()
            }
        }
    }

    private val outputFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            persistFolderPermission(it)
            outputFolderUri = it
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_OUTPUT_FOLDER, it.toString())
                .apply()
            outputFolderStatus.text = "Output folder: ${folderLabel(it)}"
            updateControlState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NX RomFS Extractor"
        supportActionBar?.title = title
        setContentView(buildUi())
        refreshKeysStatus()
        restoreOutputFolder()
        updateControlState()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        content.addView(TextView(this).apply {
            text = "NX RomFS Extractor"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        content.addView(TextView(this).apply {
            text = "Browse reconstructed RomFS contents, choose one source file, and save it directly to an Android folder. If an update package is selected, the updated RomFS is reconstructed on the fly."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(20))
        })

        content.addView(Button(this).apply {
            text = "1. Select base NSP"
            setOnClickListener { basePicker.launch(arrayOf("*/*")) }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        baseStatus = TextView(this).apply {
            text = "Base package: not selected"
            setPadding(0, dp(4), 0, dp(14))
        }
        content.addView(baseStatus)

        content.addView(Button(this).apply {
            text = "2. Select update NSP (optional)"
            setOnClickListener { updatePicker.launch(arrayOf("*/*")) }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        content.addView(Button(this).apply {
            text = "Clear selected update"
            setOnClickListener {
                updateUri = null
                updateStatus.text = "Update package: none (base RomFS only)"
                invalidateRomFsSelection()
                updateControlState()
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        updateStatus = TextView(this).apply {
            text = "Update package: none (base RomFS only)"
            setPadding(0, dp(4), 0, dp(14))
        }
        content.addView(updateStatus)

        content.addView(Button(this).apply {
            text = "3. Select prod.keys"
            setOnClickListener { keysPicker.launch(arrayOf("*/*")) }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        keysStatus = TextView(this).apply {
            text = "prod.keys: not loaded"
            setPadding(0, dp(4), 0, dp(18))
        }
        content.addView(keysStatus)

        content.addView(TextView(this).apply {
            text = "4. Choose the source file inside RomFS"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        content.addView(TextView(this).apply {
            text = "Browse the internal folders until you reach the file you want to extract. Example: content/content0/bundles/xml.bundle"
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, dp(8))
        })

        browseButton = Button(this).apply {
            text = "Browse RomFS"
            setOnClickListener { browseRomFs() }
        }
        content.addView(browseButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        selectedFileStatus = TextView(this).apply {
            text = "Source file: none"
            setPadding(0, dp(6), 0, dp(18))
            setTextIsSelectable(true)
        }
        content.addView(selectedFileStatus)

        content.addView(TextView(this).apply {
            text = "5. Choose output folder"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        content.addView(TextView(this).apply {
            text = "Choose an Android destination folder such as Internal storage → Download."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, dp(8))
        })

        outputFolderButton = Button(this).apply {
            text = "Choose output folder"
            setOnClickListener { outputFolderPicker.launch(downloadsInitialUri()) }
        }
        content.addView(outputFolderButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        outputFolderStatus = TextView(this).apply {
            text = "Output folder: not selected"
            setPadding(0, dp(6), 0, dp(18))
            setTextIsSelectable(true)
        }
        content.addView(outputFolderStatus)

        extractButton = Button(this).apply {
            text = "6. Extract file"
            setOnClickListener { runExtraction() }
        }
        content.addView(extractButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        content.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(16)
        })

        resultStatus = TextView(this).apply {
            text = "Ready."
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
            setTextIsSelectable(true)
        }
        content.addView(resultStatus)

        content.addView(TextView(this).apply {
            text = "Step 4 selects the source file inside RomFS. Step 5 selects the destination folder on Android. All processing is local to this device."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(24), 0, 0)
        })

        return ScrollView(this).apply { addView(content) }
    }

    private fun refreshKeysStatus() {
        val manager = ProdKeysManager.getInstance(this)
        keysStatus.text = if (manager.isKeysLoaded()) "prod.keys: loaded" else "prod.keys: not loaded"
    }

    private fun restoreOutputFolder() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_OUTPUT_FOLDER, null)
        if (saved.isNullOrBlank()) return
        val uri = Uri.parse(saved)
        val stillGranted = contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (stillGranted) {
            outputFolderUri = uri
            outputFolderStatus.text = "Output folder: ${folderLabel(uri)}"
        }
    }

    private fun invalidateRomFsSelection() {
        cachedRomFsFiles = emptyList()
        selectedRomFsPath = null
        if (::selectedFileStatus.isInitialized) selectedFileStatus.text = "Source file: none"
    }

    private fun updateControlState() {
        val readyToBrowse = baseUri != null && ProdKeysManager.getInstance(this).isKeysLoaded()
        if (::browseButton.isInitialized) browseButton.isEnabled = readyToBrowse
        if (::outputFolderButton.isInitialized) outputFolderButton.isEnabled = true
        if (::extractButton.isInitialized) {
            extractButton.isEnabled = readyToBrowse &&
                !selectedRomFsPath.isNullOrBlank() &&
                outputFolderUri != null
        }
    }

    private fun browseRomFs() {
        if (cachedRomFsFiles.isNotEmpty()) {
            showRomFsBrowser(cachedRomFsFiles, "")
            return
        }

        val base = baseUri ?: return
        val update = updateUri
        val keysPath = ProdKeysManager.getInstance(this).getKeysFilePath()
        if (keysPath.isNullOrBlank()) {
            resultStatus.text = "Error: prod.keys is not loaded."
            return
        }

        progress.visibility = View.VISIBLE
        browseButton.isEnabled = false
        extractButton.isEnabled = false
        resultStatus.text = if (update == null) {
            "Reading base RomFS…"
        } else {
            "Reconstructing updated RomFS and reading its files…"
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openFileDescriptor(base, "r")?.use { basePfd ->
                        if (update != null) {
                            contentResolver.openFileDescriptor(update, "r")?.use { updatePfd ->
                                listRomFsFilesNative(
                                    basePfd.fd,
                                    displayName(base),
                                    updatePfd.fd,
                                    displayName(update),
                                    keysPath
                                )
                            } ?: "ERROR|Could not open update package"
                        } else {
                            listRomFsFilesNative(
                                basePfd.fd,
                                displayName(base),
                                -1,
                                "",
                                keysPath
                            )
                        }
                    } ?: "ERROR|Could not open base package"
                } catch (t: Throwable) {
                    "ERROR|${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                }
            }

            progress.visibility = View.GONE
            updateControlState()

            if (result.startsWith("OK|")) {
                val lines = result.lineSequence().toList()
                cachedRomFsFiles = lines.drop(1).filter { it.isNotBlank() }
                resultStatus.text = "RomFS ready: ${cachedRomFsFiles.size} files\nBrowse to the source file you want to extract."
                showRomFsBrowser(cachedRomFsFiles, "")
            } else {
                resultStatus.text = "Could not browse RomFS:\n${result.removePrefix("ERROR|")}"
            }
        }
    }

    private fun showRomFsBrowser(files: List<String>, directory: String) {
        val normalizedDir = directory.trim('/').trim()
        val prefix = if (normalizedDir.isBlank()) "" else "$normalizedDir/"

        val folders = sortedSetOf<String>()
        val directFiles = sortedSetOf<String>()

        for (path in files) {
            if (!path.startsWith(prefix)) continue
            val relative = path.removePrefix(prefix)
            if (relative.isBlank()) continue
            val slash = relative.indexOf('/')
            if (slash >= 0) folders.add(relative.substring(0, slash))
            else directFiles.add(relative)
        }

        data class BrowserEntry(val kind: Int, val name: String)
        val entries = mutableListOf<BrowserEntry>()
        if (normalizedDir.isNotBlank()) entries.add(BrowserEntry(0, ".."))
        folders.forEach { entries.add(BrowserEntry(1, it)) }
        directFiles.forEach { entries.add(BrowserEntry(2, it)) }

        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("RomFS /$normalizedDir")
                .setMessage("This folder is empty.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = entries.map {
            when (it.kind) {
                0 -> "⬅  .."
                1 -> "📁  ${it.name}"
                else -> "📄  ${it.name}"
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(if (normalizedDir.isBlank()) "RomFS /" else "RomFS /$normalizedDir")
            .setItems(labels) { _, which ->
                val entry = entries[which]
                when (entry.kind) {
                    0 -> {
                        val parent = normalizedDir.substringBeforeLast('/', "")
                        showRomFsBrowser(files, parent)
                    }
                    1 -> {
                        val child = if (normalizedDir.isBlank()) entry.name else "$normalizedDir/${entry.name}"
                        showRomFsBrowser(files, child)
                    }
                    else -> {
                        val selected = if (normalizedDir.isBlank()) entry.name else "$normalizedDir/${entry.name}"
                        selectedRomFsPath = selected
                        selectedFileStatus.text = "Source file:\n$selected"
                        resultStatus.text = "Source file selected. Choose an output folder, then extract."
                        updateControlState()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runExtraction() {
        val base = baseUri ?: return
        val update = updateUri
        val requestedPath = selectedRomFsPath ?: return
        val folderUri = outputFolderUri ?: return
        val keysPath = ProdKeysManager.getInstance(this).getKeysFilePath()
        if (keysPath.isNullOrBlank()) {
            resultStatus.text = "Error: prod.keys is not loaded."
            return
        }

        progress.visibility = View.VISIBLE
        browseButton.isEnabled = false
        outputFolderButton.isEnabled = false
        extractButton.isEnabled = false
        resultStatus.text = "Creating ${suggestedOutputName(requestedPath)} in ${folderLabel(folderUri)}…"

        lifecycleScope.launch {
            val extraction = withContext(Dispatchers.IO) {
                try {
                    val outputUri = createOutputDocument(folderUri, suggestedOutputName(requestedPath))
                        ?: return@withContext Pair("ERROR|Could not create the output file in the selected folder", null)

                    val result = contentResolver.openFileDescriptor(base, "r")?.use { basePfd ->
                        if (update != null) {
                            contentResolver.openFileDescriptor(update, "r")?.use { updatePfd ->
                                contentResolver.openFileDescriptor(outputUri, "w")?.use { outputPfd ->
                                    extractRomFsFileNative(
                                        basePfd.fd,
                                        displayName(base),
                                        updatePfd.fd,
                                        displayName(update),
                                        outputPfd.fd,
                                        keysPath,
                                        requestedPath
                                    )
                                } ?: "ERROR|Could not open output file"
                            } ?: "ERROR|Could not open update package"
                        } else {
                            contentResolver.openFileDescriptor(outputUri, "w")?.use { outputPfd ->
                                extractRomFsFileNative(
                                    basePfd.fd,
                                    displayName(base),
                                    -1,
                                    "",
                                    outputPfd.fd,
                                    keysPath,
                                    requestedPath
                                )
                            } ?: "ERROR|Could not open output file"
                        }
                    } ?: "ERROR|Could not open base package"

                    Pair(result, outputUri)
                } catch (t: Throwable) {
                    Pair("ERROR|${t.javaClass.simpleName}: ${t.message ?: "unknown error"}", null)
                }
            }

            progress.visibility = View.GONE
            outputFolderButton.isEnabled = true
            updateControlState()

            val result = extraction.first
            val savedUri = extraction.second
            resultStatus.text = if (result.startsWith("OK|")) {
                val parts = result.split('|')
                val foundPath = parts.getOrNull(1) ?: requestedPath
                val bytes = parts.getOrNull(2)?.toLongOrNull()
                val sizeText = bytes?.let { formatBytes(it) } ?: "unknown size"
                buildString {
                    append("Success!\n")
                    append(foundPath)
                    append("\n")
                    append(sizeText)
                    append("\nSaved to: ${folderLabel(folderUri)} / ${suggestedOutputName(requestedPath)}")
                    if (savedUri != null) append("\n\nThe extracted file is ready.")
                }
            } else {
                "Extraction failed:\n${result.removePrefix("ERROR|")}"
            }
        }
    }

    private fun createOutputDocument(folderUri: Uri, fileName: String): Uri? {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(folderUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, treeDocumentId)
        return DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            "application/octet-stream",
            fileName
        )
    }

    private fun downloadsInitialUri(): Uri {
        return Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
    }

    private fun folderLabel(uri: Uri): String {
        return try {
            val id = Uri.decode(DocumentsContract.getTreeDocumentId(uri))
            when {
                id == "primary:" -> "Internal storage"
                id.startsWith("primary:") -> "Internal storage / ${id.removePrefix("primary:")}"
                else -> id.replace(':', '/')
            }
        } catch (_: Exception) {
            uri.lastPathSegment ?: "selected folder"
        }
    }

    private fun suggestedOutputName(path: String): String {
        val normalized = path.trim().replace('\\', '/').trimEnd('/')
        return normalized.substringAfterLast('/').ifBlank { "romfs_file.bin" }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The current grant is enough when persistence is unavailable.
        }
    }

    private fun persistFolderPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The current grant can still be used when persistence is unavailable.
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "selected-file"
    }

    private external fun listRomFsFilesNative(
        baseFd: Int,
        baseFileName: String,
        updateFd: Int,
        updateFileName: String,
        keysFile: String
    ): String

    private external fun extractRomFsFileNative(
        baseFd: Int,
        baseFileName: String,
        updateFd: Int,
        updateFileName: String,
        outputFd: Int,
        keysFile: String,
        requestedPath: String
    ): String

    companion object {
        private const val PREFS = "nx_romfs_extractor"
        private const val PREF_OUTPUT_FOLDER = "output_folder_uri"

        init {
            System.loadLibrary("nstool")
        }
    }
}
