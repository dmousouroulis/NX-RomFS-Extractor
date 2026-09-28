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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RomFsExtractorActivity : AppCompatActivity() {

    private var baseUri: Uri? = null
    private var updateUri: Uri? = null
    private var selectedRomFsPath: String? = null
    private var outputFolderUri: Uri? = null
    private var modifiedFileUri: Uri? = null
    private var cachedRomFsFiles: List<String> = emptyList()

    private lateinit var baseStatus: TextView
    private lateinit var updateStatus: TextView
    private lateinit var keysStatus: TextView
    private lateinit var selectedFileStatus: TextView
    private lateinit var outputFolderStatus: TextView
    private lateinit var modifiedFileStatus: TextView
    private lateinit var browseButton: Button
    private lateinit var outputFolderButton: Button
    private lateinit var extractButton: Button
    private lateinit var modifiedFileButton: Button
    private lateinit var exportOverrideButton: Button
    private lateinit var resultStatus: TextView
    private lateinit var progress: ProgressBar

    private val basePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handlePackageSelection(it, selectingBase = true) }
        }

    private val updatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { handlePackageSelection(it, selectingBase = false) }
        }

    private val keysPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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

    private val outputFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
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

    private val modifiedFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                persistReadPermission(it)
                modifiedFileUri = it
                val originalPath = selectedRomFsPath
                val originalName = originalPath?.let { path -> originalFileName(path) }
                modifiedFileStatus.text = buildString {
                    append("Modified replacement: ${displayName(it)}")
                    if (!originalPath.isNullOrBlank() && !originalName.isNullOrBlank()) {
                        append("\nPackage target: $originalPath")
                        append("\nFilename inside package: $originalName")
                    }
                }
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
            text = "Browse reconstructed RomFS contents, choose one source file, and save it directly to an Android folder. NSP and XCI containers are supported."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(20))
        })

        content.addView(Button(this).apply {
            text = "1. SELECT BASE PACKAGE (NSP / XCI)"
            setOnClickListener {
                Toast.makeText(
                    this@RomFsExtractorActivity,
                    "Select the original/base package. NSP and XCI are supported.",
                    Toast.LENGTH_LONG
                ).show()
                basePicker.launch(arrayOf("*/*"))
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        content.addView(TextView(this).apply {
            text = "Choose the original/base package here. If the filename includes a version marker, this is usually v0."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(5), 0, dp(4))
        })

        baseStatus = TextView(this).apply {
            text = "Base package: not selected"
            setPadding(0, 0, 0, dp(14))
            setTextIsSelectable(true)
        }
        content.addView(baseStatus)

        content.addView(Button(this).apply {
            text = "2. SELECT UPDATE PACKAGE (OPTIONAL)"
            setOnClickListener {
                Toast.makeText(
                    this@RomFsExtractorActivity,
                    "Optional: select the matching update package (NSP or XCI).",
                    Toast.LENGTH_LONG
                ).show()
                updatePicker.launch(arrayOf("*/*"))
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        content.addView(TextView(this).apply {
            text = "Optional. Choose the update package that belongs to the selected base package."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(5), 0, dp(4))
        })

        content.addView(Button(this).apply {
            text = "CLEAR SELECTED UPDATE"
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
            setTextIsSelectable(true)
        }
        content.addView(updateStatus)

        content.addView(Button(this).apply {
            text = "3. SELECT PROD.KEYS"
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
            text = "Browse the reconstructed internal folders until you reach the file you want to extract. Its original relative RomFS path is retained automatically for optional override packaging later."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, dp(8))
        })

        browseButton = Button(this).apply {
            text = "BROWSE ROMFS"
            setOnClickListener { browseRomFs() }
        }
        content.addView(
            browseButton,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        content.addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        )

        resultStatus = TextView(this).apply {
            text = "Ready."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            setTextIsSelectable(true)
        }
        content.addView(resultStatus)

        selectedFileStatus = TextView(this).apply {
            text = "Source file: none"
            setPadding(0, dp(4), 0, dp(18))
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
            text = "Choose any writable subfolder in internal storage or on an SD card. Android may block protected folders or a storage root itself. The same folder is also used for an optional override ZIP."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, dp(8))
        })

        outputFolderButton = Button(this).apply {
            text = "CHOOSE OUTPUT FOLDER"
            setOnClickListener { outputFolderPicker.launch(internalStorageInitialUri()) }
        }
        content.addView(
            outputFolderButton,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        outputFolderStatus = TextView(this).apply {
            text = "Output folder: not selected"
            setPadding(0, dp(6), 0, dp(18))
            setTextIsSelectable(true)
        }
        content.addView(outputFolderStatus)

        extractButton = Button(this).apply {
            text = "6. EXTRACT FILE"
            setOnClickListener { runExtraction() }
        }
        content.addView(
            extractButton,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        content.addView(TextView(this).apply {
            text = "7. Export Override Package (optional)"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(6))
        })

        content.addView(TextView(this).apply {
            text = "After modifying the extracted file externally, select that replacement here. The app will restore the original filename and rebuild the remembered RomFS hierarchy inside a ZIP."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, dp(8))
        })

        modifiedFileButton = Button(this).apply {
            text = "SELECT MODIFIED REPLACEMENT FILE"
            setOnClickListener { modifiedFilePicker.launch(arrayOf("*/*")) }
        }
        content.addView(
            modifiedFileButton,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        modifiedFileStatus = TextView(this).apply {
            text = "Modified replacement: not selected"
            setPadding(0, dp(6), 0, dp(12))
            setTextIsSelectable(true)
        }
        content.addView(modifiedFileStatus)

        exportOverrideButton = Button(this).apply {
            text = "EXPORT OVERRIDE PACKAGE"
            setOnClickListener { showOverrideExportOptions() }
        }
        content.addView(
            exportOverrideButton,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        content.addView(TextView(this).apply {
            text = "Package options: romfs/… or Mods/romfs/…. The app only creates the ZIP; it does not install, enable, or configure the resulting override in other software. Keep a backup of the original or previously working file before replacing anything."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(8), 0, 0)
        })

        content.addView(TextView(this).apply {
            text = "Step 4 selects the source file inside RomFS. Step 5 selects where extracted files and exported ZIPs are saved on Android. All processing is local to this device."
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(24), 0, 0)
        })

        return ScrollView(this).apply { addView(content) }
    }

    private fun handlePackageSelection(uri: Uri, selectingBase: Boolean) {
        val name = displayName(uri)
        if (!isSupportedPackageName(name)) {
            AlertDialog.Builder(this)
                .setTitle("Unsupported package")
                .setMessage("Please select an NSP or XCI file.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        persistReadPermission(uri)

        if (selectingBase) {
            baseUri = uri
            baseStatus.text = "Base package: $name"
        } else {
            updateUri = uri
            updateStatus.text = "Update package: $name"
        }

        invalidateRomFsSelection()
        updateControlState()
        warnIfVersionLooksWrong(name, selectingBase)
    }

    private fun isSupportedPackageName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".nsp") || lower.endsWith(".xci")
    }

    private fun refreshKeysStatus() {
        val manager = ProdKeysManager.getInstance(this)
        keysStatus.text =
            if (manager.isKeysLoaded()) "prod.keys: loaded" else "prod.keys: not loaded"
    }

    private fun restoreOutputFolder() {
        val saved =
            getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_OUTPUT_FOLDER, null)
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
        modifiedFileUri = null
        if (::selectedFileStatus.isInitialized) selectedFileStatus.text = "Source file: none"
        if (::modifiedFileStatus.isInitialized) {
            modifiedFileStatus.text = "Modified replacement: not selected"
        }
    }

    private fun updateControlState() {
        val readyToBrowse =
            baseUri != null && ProdKeysManager.getInstance(this).isKeysLoaded()

        if (::browseButton.isInitialized) browseButton.isEnabled = readyToBrowse
        if (::outputFolderButton.isInitialized) outputFolderButton.isEnabled = true
        if (::extractButton.isInitialized) {
            extractButton.isEnabled =
                readyToBrowse &&
                    !selectedRomFsPath.isNullOrBlank() &&
                    outputFolderUri != null
        }
        if (::modifiedFileButton.isInitialized) {
            modifiedFileButton.isEnabled = !selectedRomFsPath.isNullOrBlank()
        }
        if (::exportOverrideButton.isInitialized) {
            exportOverrideButton.isEnabled =
                !selectedRomFsPath.isNullOrBlank() &&
                    modifiedFileUri != null &&
                    outputFolderUri != null
        }
    }

    private fun versionFromName(name: String): Long? {
        val match =
            Regex("\\[v(\\d+)]", RegexOption.IGNORE_CASE).find(name) ?: return null
        return match.groupValues.getOrNull(1)?.toLongOrNull()
    }

    private fun warnIfVersionLooksWrong(name: String, selectingBase: Boolean) {
        val version = versionFromName(name) ?: return
        val looksWrong = if (selectingBase) version > 0 else version == 0L
        if (!looksWrong) return

        val message =
            if (selectingBase) {
                "This filename contains [v$version], so it looks more like an update package. The base package is usually v0. You can keep it if you know this is correct."
            } else {
                "This filename contains [v0], so it looks more like a base package than an update. You can keep it if you know this is correct."
            }

        AlertDialog.Builder(this)
            .setTitle("Check package selection")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun packagesLookReversed(): Boolean {
        val base = baseUri ?: return false
        val update = updateUri ?: return false
        val baseVersion = versionFromName(displayName(base)) ?: return false
        val updateVersion = versionFromName(displayName(update)) ?: return false
        return baseVersion > 0 && updateVersion == 0L
    }

    private fun swapPackages() {
        val oldBase = baseUri
        baseUri = updateUri
        updateUri = oldBase

        baseStatus.text =
            "Base package: ${baseUri?.let { displayName(it) } ?: "not selected"}"
        updateStatus.text =
            updateUri?.let { "Update package: ${displayName(it)}" }
                ?: "Update package: none (base RomFS only)"

        invalidateRomFsSelection()
        updateControlState()
    }

    private fun browseRomFs() {
        if (cachedRomFsFiles.isNotEmpty()) {
            showRomFsBrowser(cachedRomFsFiles, "")
            return
        }

        if (packagesLookReversed()) {
            AlertDialog.Builder(this)
                .setTitle("Packages may be reversed")
                .setMessage(
                    "The selected base filename looks versioned while the selected update filename is v0. Swap them before reconstructing RomFS?"
                )
                .setPositiveButton("Swap and continue") { _, _ ->
                    swapPackages()
                    browseRomFs()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val base = baseUri ?: return
        val update = updateUri
        val keysPath = ProdKeysManager.getInstance(this).getKeysFilePath()

        if (keysPath.isNullOrBlank()) {
            showBrowseError("prod.keys is not loaded.")
            return
        }

        progress.visibility = View.VISIBLE
        browseButton.text = "READING ROMFS…"
        browseButton.isEnabled = false
        extractButton.isEnabled = false
        resultStatus.text =
            if (update == null) {
                "Reading base RomFS… This can take a little while for large packages."
            } else {
                "Reconstructing updated RomFS… This can take a little while for large packages."
            }

        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
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
            browseButton.text = "BROWSE ROMFS"
            updateControlState()

            if (result.startsWith("OK|")) {
                val lines = result.lineSequence().toList()
                cachedRomFsFiles = lines.drop(1).filter { it.isNotBlank() }
                resultStatus.text =
                    "RomFS ready: ${cachedRomFsFiles.size} files. Choose the source file you want to extract."
                showRomFsBrowser(cachedRomFsFiles, "")
            } else {
                val error = result.removePrefix("ERROR|")
                resultStatus.text = "Could not browse RomFS: $error"
                showBrowseError(error)
            }
        }
    }

    private fun showBrowseError(error: String) {
        AlertDialog.Builder(this)
            .setTitle("Could not browse RomFS")
            .setMessage(
                "$error\n\nCheck that the selected base and optional update package belong together and that prod.keys is compatible with them."
            )
            .setPositiveButton("OK", null)
            .show()
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

        val labels =
            entries.map {
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
                        val child =
                            if (normalizedDir.isBlank()) entry.name
                            else "$normalizedDir/${entry.name}"
                        showRomFsBrowser(files, child)
                    }

                    else -> {
                        val selected =
                            if (normalizedDir.isBlank()) entry.name
                            else "$normalizedDir/${entry.name}"
                        selectedRomFsPath = normalizeRelativeRomFsPath(selected)
                        modifiedFileUri = null
                        selectedFileStatus.text = "Source file:\n$selectedRomFsPath"
                        modifiedFileStatus.text = "Modified replacement: not selected"
                        resultStatus.text =
                            "Source file selected. Its original RomFS path is retained. Choose an output folder, then extract."
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
        modifiedFileButton.isEnabled = false
        exportOverrideButton.isEnabled = false
        resultStatus.text =
            "Creating ${suggestedOutputName(requestedPath)} in ${folderLabel(folderUri)}…"

        lifecycleScope.launch {
            val extraction =
                withContext(Dispatchers.IO) {
                    try {
                        val outputUri =
                            createOutputDocument(
                                folderUri,
                                suggestedOutputName(requestedPath),
                                "application/octet-stream"
                            ) ?: return@withContext Pair(
                                "ERROR|Could not create the output file in the selected folder",
                                null
                            )

                        val result =
                            contentResolver.openFileDescriptor(base, "r")?.use { basePfd ->
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
                        Pair(
                            "ERROR|${t.javaClass.simpleName}: ${t.message ?: "unknown error"}",
                            null
                        )
                    }
                }

            progress.visibility = View.GONE
            outputFolderButton.isEnabled = true
            updateControlState()

            val result = extraction.first
            val savedUri = extraction.second
            resultStatus.text =
                if (result.startsWith("OK|")) {
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
                        if (savedUri != null) {
                            append("\n\nOriginal RomFS path retained for optional override packaging.")
                        }
                    }
                } else {
                    "Extraction failed:\n${result.removePrefix("ERROR|")}"
                }
        }
    }

    private fun showOverrideExportOptions() {
        val originalPath = selectedRomFsPath ?: return
        val replacement = modifiedFileUri ?: return
        val folder = outputFolderUri ?: return
        val originalName = originalFileName(originalPath)

        AlertDialog.Builder(this)
            .setTitle("Export Override Package")
            .setMessage(
                "The selected replacement '${displayName(replacement)}' will be stored inside the ZIP using the original filename '$originalName' and original RomFS path:\n\n$originalPath\n\nChoose a package structure."
            )
            .setItems(
                arrayOf(
                    "romfs/… — standard override structure",
                    "Mods/romfs/… — wrapped mod-folder structure"
                )
            ) { _, which ->
                runOverrideExport(folder, replacement, originalPath, wrapped = which == 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runOverrideExport(
        folderUri: Uri,
        replacementUri: Uri,
        originalPath: String,
        wrapped: Boolean
    ) {
        val normalizedPath = normalizeRelativeRomFsPath(originalPath)
        if (normalizedPath.isBlank()) {
            resultStatus.text = "Could not export override package: original RomFS path is empty."
            return
        }

        val entryPath =
            if (wrapped) "Mods/romfs/$normalizedPath" else "romfs/$normalizedPath"
        val zipName = overrideZipName(normalizedPath, wrapped)

        progress.visibility = View.VISIBLE
        browseButton.isEnabled = false
        outputFolderButton.isEnabled = false
        extractButton.isEnabled = false
        modifiedFileButton.isEnabled = false
        exportOverrideButton.isEnabled = false
        resultStatus.text = "Creating $zipName…"

        lifecycleScope.launch {
            val exportResult =
                withContext(Dispatchers.IO) {
                    try {
                        val zipUri =
                            createOutputDocument(folderUri, zipName, "application/zip")
                                ?: return@withContext OverrideResult.Error(
                                    "Could not create the ZIP in the selected output folder"
                                )

                        val bytes =
                            contentResolver.openInputStream(replacementUri)?.use { rawInput ->
                                BufferedInputStream(rawInput).use { input ->
                                    contentResolver.openOutputStream(zipUri, "w")?.use { rawOutput ->
                                        ZipOutputStream(BufferedOutputStream(rawOutput)).use { zip ->
                                            zip.putNextEntry(ZipEntry(entryPath))
                                            val copied = input.copyTo(zip)
                                            zip.closeEntry()
                                            copied
                                        }
                                    } ?: throw IllegalStateException("Could not open ZIP output")
                                }
                            } ?: throw IllegalStateException("Could not open the modified replacement file")

                        OverrideResult.Success(zipName, entryPath, bytes)
                    } catch (t: Throwable) {
                        OverrideResult.Error(
                            "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                        )
                    }
                }

            progress.visibility = View.GONE
            updateControlState()

            when (exportResult) {
                is OverrideResult.Success -> {
                    resultStatus.text = buildString {
                        append("Override ZIP created successfully.\n")
                        append("ZIP: ${exportResult.zipName}\n")
                        append("Contains: ${exportResult.entryPath}\n")
                        append("Replacement size: ${formatBytes(exportResult.bytes)}\n")
                        append("Saved to: ${folderLabel(folderUri)}")
                    }
                    AlertDialog.Builder(this@RomFsExtractorActivity)
                        .setTitle("Override package created")
                        .setMessage(
                            "${exportResult.zipName}\n\nInside the ZIP:\n${exportResult.entryPath}\n\nThe original RomFS path and filename were restored automatically. This app does not install or enable the package in other software. Keep a backup of any previously working file before replacing it."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }

                is OverrideResult.Error -> {
                    resultStatus.text = "Override export failed:\n${exportResult.message}"
                    AlertDialog.Builder(this@RomFsExtractorActivity)
                        .setTitle("Could not export override package")
                        .setMessage(exportResult.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun createOutputDocument(
        folderUri: Uri,
        fileName: String,
        mimeType: String
    ): Uri? {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(folderUri)
        val parentUri =
            DocumentsContract.buildDocumentUriUsingTree(folderUri, treeDocumentId)
        return DocumentsContract.createDocument(
            contentResolver,
            parentUri,
            mimeType,
            fileName
        )
    }

    private fun internalStorageInitialUri(): Uri {
        return Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3A"
        )
    }

    private fun folderLabel(uri: Uri): String {
        return try {
            val id = Uri.decode(DocumentsContract.getTreeDocumentId(uri))
            when {
                id == "primary:" -> "Internal storage"
                id.startsWith("primary:") ->
                    "Internal storage / ${id.removePrefix("primary:")}"
                else -> id.replace(':', '/')
            }
        } catch (_: Exception) {
            uri.lastPathSegment ?: "selected folder"
        }
    }

    private fun normalizeRelativeRomFsPath(path: String): String {
        val pieces =
            path.trim()
                .replace('\\', '/')
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
        return pieces.joinToString("/")
    }

    private fun originalFileName(path: String): String {
        val normalized = normalizeRelativeRomFsPath(path)
        return normalized.substringAfterLast('/').ifBlank { "romfs_file.bin" }
    }

    private fun suggestedOutputName(path: String): String = originalFileName(path)

    private fun overrideZipName(path: String, wrapped: Boolean): String {
        val original = originalFileName(path)
        val stem = original.substringBeforeLast('.', original)
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "romfs-file" }
        return if (wrapped) "$stem-override-wrapped.zip" else "$stem-override.zip"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L ->
                String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L ->
                String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L ->
                String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    private fun persistReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun persistFolderPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
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

    private sealed class OverrideResult {
        data class Success(
            val zipName: String,
            val entryPath: String,
            val bytes: Long
        ) : OverrideResult()

        data class Error(val message: String) : OverrideResult()
    }

    companion object {
        private const val PREFS = "nx_romfs_extractor"
        private const val PREF_OUTPUT_FOLDER = "output_folder_uri"

        init {
            System.loadLibrary("nstool")
        }
    }
}
