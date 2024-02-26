package org.fossify.voicerecorder.activities

import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import org.fossify.commons.dialogs.*
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.RadioItem
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.ActivitySettingsBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.extensions.emptyTheRecycleBin
import org.fossify.voicerecorder.extensions.getAllRecordings
import org.fossify.voicerecorder.helpers.BITRATES
import org.fossify.voicerecorder.helpers.EXTENSION_M4A
import org.fossify.voicerecorder.helpers.EXTENSION_MP3
import org.fossify.voicerecorder.helpers.EXTENSION_OGG
import org.fossify.voicerecorder.models.Events
import org.greenrobot.eventbus.EventBus
import java.util.Locale
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    private var recycleBinContentSize = 0
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateMaterialActivityViews(binding.settingsCoordinator, binding.settingsHolder, useTransparentNavigation = true, useTopSearchMenu = false)
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
        setupHideNotification()
        setupSaveRecordingsFolder()
        setupExtension()
        setupBitrate()
        setupAudioSource()
        setupRecordAfterLaunch()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        updateTextColors(binding.settingsNestedScrollview)

        arrayOf(binding.settingsColorCustomizationSectionLabel, binding.settingsGeneralSettingsLabel, binding.settingsRecycleBinLabel).forEach {
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
        binding.settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
        binding.settingsUseEnglish.isChecked = config.useEnglish
        binding.settingsUseEnglishHolder.setOnClickListener {
            binding.settingsUseEnglish.toggle()
            config.useEnglish = binding.settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() {
        binding.settingsLanguage.text = Locale.getDefault().displayLanguage
        binding.settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
        binding.settingsLanguageHolder.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupHideNotification() {
        binding.settingsHideNotification.isChecked = config.hideNotification
        binding.settingsHideNotificationHolder.setOnClickListener {
            binding.settingsHideNotification.toggle()
            config.hideNotification = binding.settingsHideNotification.isChecked
        }
    }

    private fun setupSaveRecordingsFolder() {
        binding.settingsSaveRecordingsLabel.text = addLockedLabelIfNeeded(R.string.save_recordings_in)
        binding.settingsSaveRecordings.text = humanizePath(config.saveRecordingsFolder)
        binding.settingsSaveRecordingsHolder.setOnClickListener {
            if (isOrWasThankYouInstalled()) {
                FilePickerDialog(this, config.saveRecordingsFolder, false, showFAB = true) {
                    val path = it
                    handleSAFDialog(path) { grantedSAF ->
                        if (!grantedSAF) {
                            return@handleSAFDialog
                        }

                        handleSAFDialogSdk30(path) { grantedSAF30 ->
                            if (!grantedSAF30) {
                                return@handleSAFDialogSdk30
                            }

                            config.saveRecordingsFolder = path
                            binding.settingsSaveRecordings.text = humanizePath(config.saveRecordingsFolder)
                        }
                    }
                }
            } else {
                FeatureLockedDialog(this) { }
            }
        }
    }

    private fun setupExtension() {
        binding.settingsExtension.text = config.getExtensionText()
        binding.settingsExtensionHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(EXTENSION_MP3, getString(R.string.mp3)),
                RadioItem(EXTENSION_M4A, getString(R.string.m4a))
            )

            if (isQPlus()) {
                items.add(RadioItem(EXTENSION_OGG, getString(R.string.ogg_opus)))
            }

            RadioGroupDialog(this@SettingsActivity, items, config.extension) {
                config.extension = it as Int
                binding.settingsExtension.text = config.getExtensionText()
            }
        }
    }

    private fun setupBitrate() {
        binding.settingsBitrate.text = getBitrateText(config.bitrate)
        binding.settingsBitrateHolder.setOnClickListener {
            val items = BITRATES.map { RadioItem(it, getBitrateText(it)) } as ArrayList

            RadioGroupDialog(this@SettingsActivity, items, config.bitrate) {
                config.bitrate = it as Int
                binding.settingsBitrate.text = getBitrateText(config.bitrate)
            }
        }
    }

    private fun getBitrateText(value: Int): String = getString(R.string.bitrate_value).format(value / 1000)

    private fun setupRecordAfterLaunch() {
        binding.settingsRecordAfterLaunch.isChecked = config.recordAfterLaunch
        binding.settingsRecordAfterLaunchHolder.setOnClickListener {
            binding.settingsRecordAfterLaunch.toggle()
            config.recordAfterLaunch = binding.settingsRecordAfterLaunch.isChecked
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
                recycleBinContentSize = getAllRecordings(trashed = true).sumByInt {
                    it.size
                }
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
                    this,
                    "",
                    org.fossify.commons.R.string.empty_recycle_bin_confirmation,
                    org.fossify.commons.R.string.yes,
                    org.fossify.commons.R.string.no
                ) {
                    emptyTheRecycleBin()
                    recycleBinContentSize = 0
                    binding.settingsEmptyRecycleBinSize.text = 0.formatSize()
                    EventBus.getDefault().post(Events.RecordingTrashUpdated())
                }
            }
        }
    }

    private fun setupAudioSource() {
        binding.settingsAudioSource.text = config.getAudioSourceText(config.audioSource)
        binding.settingsAudioSourceHolder.setOnClickListener {
            val items = getAudioSources().map { RadioItem(it, config.getAudioSourceText(it)) } as ArrayList

            RadioGroupDialog(this@SettingsActivity, items, config.audioSource) {
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
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        if (isNougatPlus()) {
            availableSources.add(MediaRecorder.AudioSource.UNPROCESSED)
        }

        if (isQPlus()) {
            availableSources.add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
        }

        return availableSources
    }
}
