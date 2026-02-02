package org.fossify.voicerecorder.activities

import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivitySettingsBinding
import org.fossify.voicerecorder.dialogs.FilenamePatternDialog
import org.fossify.voicerecorder.dialogs.MoveRecordingsDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.recordingStore
import org.fossify.voicerecorder.extensions.recordingStoreFor
import org.fossify.voicerecorder.helpers.*
import org.fossify.voicerecorder.models.Events
import org.fossify.voicerecorder.store.RecordingFormat
import org.greenrobot.eventbus.EventBus
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private var recycleBinContentSize = 0
    private lateinit var binding: ActivitySettingsBinding

    private val saveRecordingsFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { newUri ->
        if (newUri != null) {
            val oldUri = config.saveRecordingsFolder

            contentResolver.takePersistableUriPermission(
                newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            ensureBackgroundThread {
                val hasRecordings = try {
                    !recordingStore.isEmpty()
                } catch (_: SecurityException) {
                    // The permission to access the store has been revoked (perhaps the providing app has been reinstalled). Swallow this exception to allow the
                    // user to select different store.
                    false
                }

                runOnUiThread {
                    if (newUri != oldUri && hasRecordings) {
                        MoveRecordingsDialog(
                            activity = this, oldFolder = oldUri, newFolder = newUri
                        ) {
                            config.saveRecordingsFolder = newUri
                            updateSaveRecordingsFolder(newUri)
                        }
                    } else {
                        config.saveRecordingsFolder = newUri
                        updateSaveRecordingsFolder(newUri)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupCustomizeWidgetColors()
        setupUseEnglish()
        setupLanguage()
        setupChangeDateTimeFormat()
        setupSaveRecordingsFolder()
        setupFilenamePattern()
        setupExtension()
        setupBitrate()
        setupSamplingRate()
        setupMicrophoneMode()
        setupRecordAfterLaunch()
        setupKeepScreenOn()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        updateTextColors(binding.settingsNestedScrollview)

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsRecordingSectionLabel,
            binding.settingsAudioSectionLabel,
            binding.settingsRecycleBinLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationHolder.setOnClickListener {
            startCustomizationActivity()
        }
    }

    private fun setupCustomizeWidgetColors() {
        binding.settingsWidgetColorCustomizationHolder.setOnClickListener {
            Intent(this, WidgetRecordDisplayConfigureActivity::class.java).apply {
                putExtra(IS_CUSTOMIZING_COLORS, true)
                startActivity(this)
            }
        }
    }

    private fun setupUseEnglish() {
        binding.settingsUseEnglishHolder.beVisibleIf(
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus()
        )
        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        if (isTiramisuPlus()) {
            binding.settingsLanguageHolder.beVisible()
            binding.settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        } else {
            binding.settingsLanguageHolder.beGone()
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupSaveRecordingsFolder() {
        binding.settingsSaveRecordingsLabel.text = addLockedLabelIfNeeded(R.string.save_recordings_in)
        binding.settingsSaveRecordingsHolder.setOnClickListener {
            saveRecordingsFolderPicker.launch(config.saveRecordingsFolder)
        }

        updateSaveRecordingsFolder(config.saveRecordingsFolder)
    }

    private fun updateSaveRecordingsFolder(uri: Uri) {
        val store = recordingStoreFor(uri)
        binding.settingsSaveRecordings.text = store.shortName

        val providerInfo = store.providerInfo

        if (providerInfo != null) {
            val providerIcon = providerInfo.loadIcon(packageManager)
            val providerLabel = providerInfo.loadLabel(packageManager)

            binding.settingsSaveRecordingsProviderIcon.apply {
                visibility = View.VISIBLE
                contentDescription = providerLabel
                setImageDrawable(providerIcon)
            }
        } else {
            binding.settingsSaveRecordingsProviderIcon.visibility = View.GONE
        }

    }

    private fun setupFilenamePattern() {
        binding.settingsFilenamePattern.text = config.filenamePattern
        binding.settingsFilenamePatternHolder.setOnClickListener {
            FilenamePatternDialog(this) { newPattern ->
                binding.settingsFilenamePattern.text = newPattern
            }
        }
    }

    private fun setupExtension() {
        binding.settingsExtension.text = config.recordingFormat.getDescription(this)
        binding.settingsExtensionHolder.setOnClickListener {
            val items = RecordingFormat.entries.map { RadioItem(it.value, it.getDescription(this), it) }.let { ArrayList(it) }

            RadioGroupDialog(this@SettingsActivity, items, config.recordingFormat.value) {
                val checked = it as RecordingFormat

                config.recordingFormat = checked
                binding.settingsExtension.text = checked.getDescription(this)
                adjustBitrate()
                adjustSamplingRate()
            }
        }
    }

    private fun setupBitrate() {
        binding.settingsBitrate.text = getBitrateText(config.bitrate)
        binding.settingsBitrateHolder.setOnClickListener {
            val items = BITRATES[config.recordingFormat]!!.map { RadioItem(it, getBitrateText(it)) } as ArrayList

            RadioGroupDialog(this@SettingsActivity, items, config.bitrate) {
                config.bitrate = it as Int
                binding.settingsBitrate.text = getBitrateText(config.bitrate)
                adjustSamplingRate()
            }
        }
    }

    private fun getBitrateText(value: Int): String {
        return getString(R.string.bitrate_value).format(value / 1000)
    }

    private fun adjustBitrate() {
        val availableBitrates = BITRATES[config.recordingFormat]!!
        if (!availableBitrates.contains(config.bitrate)) {
            val currentBitrate = config.bitrate
            val closestBitrate = availableBitrates.minByOrNull { abs(it - currentBitrate) } ?: DEFAULT_BITRATE

            config.bitrate = closestBitrate
            binding.settingsBitrate.text = getBitrateText(config.bitrate)
        }
    }

    private fun setupSamplingRate() {
        binding.settingsSamplingRate.text = getSamplingRateText(config.samplingRate)
        binding.settingsSamplingRateHolder.setOnClickListener {
            val items = getSamplingRatesArray().map { RadioItem(it, getSamplingRateText(it)) } as ArrayList

            RadioGroupDialog(this@SettingsActivity, items, config.samplingRate) {
                config.samplingRate = it as Int
                binding.settingsSamplingRate.text = getSamplingRateText(config.samplingRate)
            }
        }
    }

    private fun getSamplingRateText(value: Int): String {
        return getString(R.string.sampling_rate_value).format(value)
    }

    private fun getSamplingRatesArray(): ArrayList<Int> {
        val baseRates = SAMPLING_RATES[config.recordingFormat]!!
        val limits = SAMPLING_RATE_BITRATE_LIMITS[config.recordingFormat]!!
        val filteredRates = baseRates.filter {
            config.bitrate in limits[it]!![0]..limits[it]!![1]
        } as ArrayList
        return filteredRates
    }

    private fun adjustSamplingRate() {
        val availableSamplingRates = getSamplingRatesArray()
        if (!availableSamplingRates.contains(config.samplingRate)) {
            if (availableSamplingRates.contains(DEFAULT_SAMPLING_RATE)) {
                config.samplingRate = DEFAULT_SAMPLING_RATE
            } else {
                config.samplingRate = availableSamplingRates.last()
            }
            binding.settingsSamplingRate.text = getSamplingRateText(config.samplingRate)
        }
    }

    private fun setupRecordAfterLaunch() {
        binding.settingsRecordAfterLaunch.isChecked = config.recordAfterLaunch
        binding.settingsRecordAfterLaunchHolder.setOnClickListener {
            binding.settingsRecordAfterLaunch.toggle()
            config.recordAfterLaunch = binding.settingsRecordAfterLaunch.isChecked
        }
    }

    private fun setupKeepScreenOn() {
        binding.settingsKeepScreenOn.isChecked = config.keepScreenOn
        binding.settingsKeepScreenOnHolder.setOnClickListener {
            binding.settingsKeepScreenOn.toggle()
            config.keepScreenOn = binding.settingsKeepScreenOn.isChecked
        }
    }

    private fun setupUseRecycleBin() {
        updateRecycleBinButtons()
        binding.settingsUseRecycleBin.isChecked = config.useRecycleBin
        binding.settingsUseRecycleBinHolder.setOnClickListener {
            binding.settingsUseRecycleBin.toggle()
            config.useRecycleBin = binding.settingsUseRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun updateRecycleBinButtons() {
        binding.settingsEmptyRecycleBinHolder.beVisibleIf(config.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() {
        ensureBackgroundThread {
            try {
                recycleBinContentSize = recordingStore.all(trashed = true).map { it.size }.sum()
            } catch (_: Exception) {
            }

            runOnUiThread {
                binding.settingsEmptyRecycleBinSize.text = recycleBinContentSize.formatSize()
            }
        }

        binding.settingsEmptyRecycleBinHolder.setOnClickListener {
            if (recycleBinContentSize == 0) {
                toast(org.fossify.commons.R.string.recycle_bin_empty)
            } else {
                ConfirmationDialog(
                    activity = this,
                    message = "",
                    messageId = org.fossify.commons.R.string.empty_recycle_bin_confirmation,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        recordingStore.deleteTrashed()
                        runOnUiThread {
                            recycleBinContentSize = 0
                            binding.settingsEmptyRecycleBinSize.text = 0.formatSize()
                            EventBus.getDefault().post(Events.RecordingTrashUpdated())
                        }
                    }
                }
            }
        }
    }

    private fun setupMicrophoneMode() {
        binding.settingsMicrophoneMode.text = config.getMicrophoneModeText(config.microphoneMode)
        binding.settingsMicrophoneModeHolder.setOnClickListener {
            if (config.wasMicModeWarningShown) {
                showMicrophoneModeDialog()
            } else {
                ConfirmationDialog(
                    activity = this,
                    dialogTitle = getString(R.string.microphone_mode),
                    message = getString(R.string.change_microphone_mode_confirmation),
                    negative = 0,
                    positive = org.fossify.commons.R.string.ok
                ) {
                    config.wasMicModeWarningShown = true
                    showMicrophoneModeDialog()
                }
            }
        }
    }

    private fun showMicrophoneModeDialog() {
        val items = getMediaRecorderAudioSources().map { microphoneMode ->
            RadioItem(
                id = microphoneMode, title = config.getMicrophoneModeText(microphoneMode)
            )
        } as ArrayList

        RadioGroupDialog(
            activity = this@SettingsActivity, items = items, checkedItemId = config.microphoneMode
        ) {
            config.microphoneMode = it as Int
            binding.settingsMicrophoneMode.text = config.getMicrophoneModeText(config.microphoneMode)
        }
    }

    private fun getMediaRecorderAudioSources(): List<Int> {
        return buildList {
            add(MediaRecorder.AudioSource.DEFAULT)
            add(MediaRecorder.AudioSource.CAMCORDER)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)

            if (isQPlus()) {
                add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
            }

            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.UNPROCESSED)
        }
    }
}
