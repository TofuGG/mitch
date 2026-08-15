package garden.appl.mitch.ui

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.widget.addTextChangedListener
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.get
import com.mikepenz.aboutlibraries.LibsBuilder
import tofu.gg.mitchy.BuildConfig
import garden.appl.mitch.Mitch
import garden.appl.mitch.PREF_BROWSER_DATA_EXPORT
import garden.appl.mitch.PREF_BROWSER_DATA_IMPORT
import garden.appl.mitch.PREF_BROWSE_START_PAGE
import garden.appl.mitch.PREF_DEBUG_WEB_GAMES_IN_BROWSE_TAB
import garden.appl.mitch.PREF_PENDING_BROWSER_DATA_IMPORT
import garden.appl.mitch.PREF_START_PAGE_EXCLUDE
import garden.appl.mitch.PREF_START_PAGE_EXCLUDE_DISPLAY_STRING
import garden.appl.mitch.PREF_WEB_CACHE_ENABLE
import garden.appl.mitch.PreferenceWebCacheEnable
import tofu.gg.mitchy.R
import garden.appl.mitch.client.ItchTag
import garden.appl.mitch.client.ItchTagsParser
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.DatabaseCleanup
import garden.appl.mitch.database.installation.Installation
import tofu.gg.mitchy.databinding.DialogTagSelectBinding
import garden.appl.mitch.files.Downloader
import garden.appl.mitch.files.WebViewDataBackup
import garden.appl.mitch.install.Installations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : PreferenceFragmentCompat(), CoroutineScope by MainScope() {
    companion object {
        private const val LOGGING_TAG = "SettingsFrag"
    }

    private val browserDataImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null)
            return@registerForActivityResult
        val context = requireContext()
        launch(Dispatchers.IO) {
            val dest = File(context.filesDir, WebViewDataBackup.PENDING_BROWSER_DATA_ZIP)
            val copied = runCatching {
                dest.outputStream().use { out ->
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                }
            }.isSuccess && dest.length() > 0
            if (!copied)
                dest.delete()
            withContext(Dispatchers.Main) {
                if (copied) {
                    preferenceManager.sharedPreferences!!.edit(commit = true) {
                        putBoolean(PREF_PENDING_BROWSER_DATA_IMPORT, true)
                    }
                    Toast.makeText(context, R.string.settings_browser_data_import_scheduled,
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, R.string.settings_browser_data_import_failed,
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val prefs = preferenceManager.sharedPreferences!!
        migrateOldPreferences(prefs)

        setPreferencesFromResource(R.xml.preferences, rootKey)
        updatePrefExcludeTag(prefs.getString(PREF_BROWSE_START_PAGE, "main"))

        findPreference<Preference>(PREF_BROWSE_START_PAGE)?.setOnPreferenceChangeListener { _, newValue ->
            updatePrefExcludeTag(newValue as String)
            true
        }
        findPreference<Preference>(PREF_DEBUG_WEB_GAMES_IN_BROWSE_TAB)?.isVisible = BuildConfig.DEBUG
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "cancel_all_downloads") {
            val dialog = AlertDialog.Builder(requireContext()).run {
                setTitle(R.string.settings_cancel_downloads_title)
                setMessage(R.string.settings_cancel_downloads_message)
                setIcon(R.drawable.ic_baseline_warning_24)

                setPositiveButton(R.string.dialog_yes) { _, _ ->
                    runBlocking(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(requireContext())

                        val readyToInstall = ArrayList<Installation>()
                        for (install in db.installDao.getAllKnownInstallationsSync()) {
                            if (install.status == Installation.STATUS_INSTALLING ||
                                install.status == Installation.STATUS_READY_TO_INSTALL) {

                                Installations.cancelPending(context, install)
                            }
                        }
                        db.installDao.delete(readyToInstall)

                        Downloader.cancelAll(context)

                        DatabaseCleanup(requireContext()).cleanAppDatabase(db)
                    }
                }
                setNegativeButton(R.string.dialog_no) { _, _ -> /* No-op */ }

                create()
            }

            dialog.show()
            return true

        } else if (preference.key == PREF_START_PAGE_EXCLUDE) {
            val binding = DialogTagSelectBinding.inflate(layoutInflater)
            val dialog = Dialog(requireContext()).apply {
                setTitle(R.string.settings_exclude_tags)
                setContentView(binding.root)
            }

            val loadTagsJob = launch {
                val tags = try {
                    ItchTagsParser.parseTags(ItchTag.Classification.GAME)
                } catch (e: Exception) {
                    binding.tagLoadingBar.visibility = View.GONE
                    binding.tagLoadingError.visibility = View.VISIBLE
                    Log.e(LOGGING_TAG, "Could not load tags", e)
                    return@launch
                }

                data class TagOption(val displayName: String, val tag: ItchTag?) {
                    override fun toString() = displayName
                }
                val options = mutableListOf(TagOption(
                    getString(R.string.settings_exclude_tags_show_all),
                    null
                ))
                options.addAll(tags.map { tag -> TagOption(tag.name, tag) })
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options)

                binding.apply {
                    searchTags.addTextChangedListener { editable ->
                        adapter.filter.filter(editable)
                    }
                    tagLoadingBar.visibility = View.GONE
                    tagsList.visibility = View.VISIBLE
                    tagsList.adapter = adapter
                    tagsList.setOnItemClickListener { _, _, position, _ ->
                        val option = adapter.getItem(position)

                        val selectedTagDisplayString = if (option?.tag == null) {
                            getString(R.string.settings_exclude_tags_show_all)
                        } else {
                            option.tag.name
                        }

                        preferenceManager.sharedPreferences!!.edit(commit = true) {
                            putStringSet(PREF_START_PAGE_EXCLUDE, option?.tag?.let { setOf(it.tag) })
                            putString(PREF_START_PAGE_EXCLUDE_DISPLAY_STRING, selectedTagDisplayString)
                        }
                        this@SettingsFragment.updatePrefExcludeTag()
                        dialog.dismiss()
                    }
                }
            }

            dialog.setOnDismissListener { loadTagsJob.cancel() }
            dialog.show()
            return true

        } else if (preference.key == "mitch.clear_all_data") {
            val dialog = AlertDialog.Builder(requireContext()).run {
                setTitle(R.string.settings_clear_all_title)
                setMessage(R.string.settings_clear_all_message)
                setIcon(R.drawable.ic_baseline_warning_24)

                setPositiveButton(R.string.dialog_yes) { _, _ ->
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    manager.clearApplicationUserData()
                }
                setNegativeButton(R.string.dialog_no) { _, _ -> /* no-op */ }
                setOnDismissListener { /* no-op */ }
                create()
            }
            dialog.show()
            return true
        } else if (preference.key == PREF_BROWSER_DATA_EXPORT) {
            val context = requireContext()
            launch(Dispatchers.IO) {
                val destZip = File(context.cacheDir, WebViewDataBackup.EXPORT_ZIP_NAME)
                destZip.delete()
                val exported = WebViewDataBackup.exportData(context, destZip)
                val (uri, displayName) = if (exported)
                    Mitch.externalFileManager.doMoveToDownloads(context, destZip)
                else
                    Pair(null, null)
                destZip.delete()
                withContext(Dispatchers.Main) {
                    if (uri == null) {
                        Toast.makeText(context, R.string.settings_browser_data_export_failed,
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context,
                            getString(R.string.settings_browser_data_exported,
                                displayName ?: WebViewDataBackup.EXPORT_ZIP_NAME),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            return true
        } else if (preference.key == PREF_BROWSER_DATA_IMPORT) {
            browserDataImportLauncher.launch(arrayOf(
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
            ))
            return true
        } else if (preference.key == "mitch.about_libraries") {
            LibsBuilder()
                .withAboutDescription("""MIT License<br/><a href="https://gardenapple.itch.io/mitch">https://gardenapple.itch.io/mitch</a>""")
                .withAboutAppName(getString(R.string.app_name))
                .withAboutIconShown(true)
                .withAboutVersionShown(true)
                .withActivityTitle(getString(R.string.settings_about_libraries))
                .start(requireContext())
            return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        //Re-attaching a fragment will redraw its UI
        //This takes care of changing day/night theme
        val manager = requireActivity().supportFragmentManager

        manager.beginTransaction().let {
            it.detach(this)
            it.commit()
        }
        manager.executePendingTransactions()
        manager.beginTransaction().let {
            it.attach(this)
            it.commit()
        }
    }

    private fun updatePrefExcludeTag(prefBrowseStartPage: String? = null) {
        val sharedPrefs = preferenceManager.sharedPreferences!!

        val startPageExcludePreference = preferenceScreen.get<Preference>(PREF_START_PAGE_EXCLUDE)!!
        startPageExcludePreference.summary = sharedPrefs.getString(
            PREF_START_PAGE_EXCLUDE_DISPLAY_STRING,
            getString(R.string.settings_exclude_tags_show_all)
        )

        if (prefBrowseStartPage != null) {
            startPageExcludePreference.isSelectable = when (prefBrowseStartPage) {
                "android", "web", "web_touch", "all_games" -> true
                else -> false
            }
        }
    }

    private fun migrateOldPreferences(prefs: SharedPreferences) {
        try {
            prefs.getString(PREF_WEB_CACHE_ENABLE, PreferenceWebCacheEnable.DEFAULT)
        } catch (_: ClassCastException) {
            val booleanValue = prefs.getBoolean(PREF_WEB_CACHE_ENABLE, false)
            val oldDialogHideKey = "mitch.web_cache_dialog_hide"
            prefs.edit(commit = true) {
                this.remove(PREF_WEB_CACHE_ENABLE)
                if (prefs.getBoolean(oldDialogHideKey, false))
                    this.putString(PREF_WEB_CACHE_ENABLE, PreferenceWebCacheEnable.ASK)
                else
                    this.putString(PREF_WEB_CACHE_ENABLE, booleanValue.toString())
                this.remove(oldDialogHideKey)
            }
        }
    }
}