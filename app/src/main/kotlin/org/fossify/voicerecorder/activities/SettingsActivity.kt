package org.fossify.voicerecorder.activities

import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.addLockedLabelIfNeeded
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getCustomizeColorsString
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.humanizePath
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.launchPurchaseThankYouIntent
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.IS_CUSTOMIZING_COLORS
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.helpers.sumByInt
import org.fossify.commons.models.RadioItem
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivitySettingsBinding
import org.fossify.voicerecorder.dialogs.MoveRecordingsDialog
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.deleteTrashedRecordings
import org.fossify.voicerecorder.extensions.getAllRecordings
import org.fossify.voicerecorder.extensions.hasRecordings
import org.fossify.voicerecorder.extensions.launchFolderPicker
import org.fossify.voicerecorder.helpers.BITRATES
import org.fossify.voicerecorder.helpers.DEFAULT_BITRATE
import org.fossify.voicerecorder.helpers.DEFAULT_SAMPLING_RATE
import org.fossify.voicerecorder.helpers.EXTENSION_M4A
import org.fossify.voicerecorder.helpers.EXTENSION_MP3
import org.fossify.voicerecorder.helpers.EXTENSION_OGG
import org.fossify.voicerecorder.helpers.SAMPLING_RATES
import org.fossify.voicerecorder.helpers.SAMPLING_RATE_BITRATE_LIMITS
import org.fossify.voicerecorder.models.Events
import org.greenrobot.eventbus.EventBus
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private var recycleBinContentSize = 0
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateMaterialActivityViews(
            mainCoordinatorLayout = binding.settingsCoordinator,
            nestedView = binding.settingsHolder,
            useTransparentNavigation = true,
            useTopSearchMenu = false
        )
        setupMaterialScrollListener(binding.settingsNestedScrollview, binding.settingsToolbar)
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.settingsToolbar, NavigationIcon.Arrow)

        setupPurchaseThankYou()
        setupCustomizeColors()
        setupCustomizeWidgetColors()
        setupUseEnglish()
        setupLanguage()
        setupChangeDateTimeFormat()
        setupSaveRecordingsFolder()
        setupExtension()
        setupBitrate()
        setupSamplingRate()
        setupAudioSource()
        setupRecordAfterLaunch()
        setupKeepScreenOn()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        updateTextColors(binding.settingsNestedScrollview)

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsRecordingSectionLabel,
            binding.settingsRecycleBinLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupPurchaseThankYou() {
        binding.settingsPurchaseThankYouHolder.beGoneIf(isOrWasThankYouInstalled())
        binding.settingsPurchaseThankYouHolder.setOnClickListener {
            launchPurchaseThankYouIntent()
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationLabel.text = getCustomizeColorsString()
        binding.settingsColorCustomizationHolder.setOnClickListener {
            handleCustomizeColorsClick()
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
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
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
        binding.settingsSaveRecordingsLabel.text =
            addLockedLabelIfNeeded(R.string.save_recordings_in)
        binding.settingsSaveRecordings.text = humanizePath(config.saveRecordingsFolder)
        binding.settingsSaveRecordingsHolder.setOnClickListener {
            val currentFolder = config.saveRecordingsFolder
            launchFolderPicker(currentFolder) { newFolder ->
                if (!newFolder.isNullOrEmpty()) {
                    ensureBackgroundThread {
                        val hasRecordings = hasRecordings()
                        runOnUiThread {
                            if (newFolder != currentFolder && hasRecordings) {
                                MoveRecordingsDialog(
                                    activity = this,
                                    previousFolder = currentFolder,
                                    newFolder = newFolder
                                ) {
                                    config.saveRecordingsFolder = newFolder
                                    binding.settingsSaveRecordings.text =
                                        humanizePath(config.saveRecordingsFolder)
                                }
                            } else {
                                config.saveRecordingsFolder = newFolder
                                binding.settingsSaveRecordings.text =
                                    humanizePath(config.saveRecordingsFolder)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupExtension() {
        binding.settingsExtension.text = config.getExtensionText()
        binding.settingsExtensionHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(EXTENSION_M4A, getString(R.string.m4a)),
                RadioItem(EXTENSION_MP3, getString(R.string.mp3_experimental))
            )

            if (isQPlus()) {
                items.add(RadioItem(EXTENSION_OGG, getString(R.string.ogg_opus)))
            }

            RadioGroupDialog(this@SettingsActivity, items, config.extension) {
                config.extension = it as Int
                binding.settingsExtension.text = config.getExtensionText()
                adjustBitrate()
                adjustSamplingRate()
            }
        }
    }

    private fun setupBitrate() {
        binding.settingsBitrate.text = getBitrateText(config.bitrate)
        binding.settingsBitrateHolder.setOnClickListener {
            val items = BITRATES[config.extension]!!
                .map { RadioItem(it, getBitrateText(it)) } as ArrayList

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
        val availableBitrates = BITRATES[config.extension]!!
        if (!availableBitrates.contains(config.bitrate)) {
            val currentBitrate = config.bitrate
            val closestBitrate = availableBitrates.minByOrNull { abs(it - currentBitrate) }
                ?: DEFAULT_BITRATE
            
            config.bitrate = closestBitrate
            binding.settingsBitrate.text = getBitrateText(config.bitrate)
        }
    }

    private fun setupSamplingRate() {
        binding.settingsSamplingRate.text = getSamplingRateText(config.samplingRate)
        binding.settingsSamplingRateHolder.setOnClickListener {
            val items = getSamplingRatesArray()
                .map { RadioItem(it, getSamplingRateText(it)) } as ArrayList

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
        val baseRates = SAMPLING_RATES[config.extension]!!
        val limits = SAMPLING_RATE_BITRATE_LIMITS[config.extension]!!
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
                recycleBinContentSize = getAllRecordings(trashed = true).sumByInt { it.size }
            } catch (ignored: Exception) {
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
                        deleteTrashedRecordings()
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

    private fun setupAudioSource() {
        binding.settingsAudioSource.text = config.getAudioSourceText(config.audioSource)
        binding.settingsAudioSourceHolder.setOnClickListener {
            val items = getAudioSources()
                .map {
                    RadioItem(
                        id = it,
                        title = config.getAudioSourceText(it)
                    )
                } as ArrayList

            RadioGroupDialog(
                activity = this@SettingsActivity,
                items = items,
                checkedItemId = config.audioSource
            ) {
                config.audioSource = it as Int
                binding.settingsAudioSource.text = config.getAudioSourceText(config.audioSource)
            }
        }
    }

    private fun getAudioSources(): ArrayList<Int> {
        val availableSources = arrayListOf(
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.UNPROCESSED
        )

        if (isQPlus()) {
            availableSources.add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
        }

        return availableSources
    }
}
