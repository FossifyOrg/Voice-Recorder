package org.fossify.voicerecorder.activities

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.SeekBar
import org.fossify.commons.dialogs.ColorPickerDialog
import org.fossify.commons.dialogs.FeatureLockedDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.setFillWithStroke
import org.fossify.commons.helpers.IS_CUSTOMIZING_COLORS
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.databinding.WidgetRecordDisplayConfigBinding
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.MyWidgetRecordDisplayProvider

class WidgetRecordDisplayConfigureActivity : SimpleActivity() {
    private var mWidgetAlpha = 0f
    private var mWidgetId = 0
    private var mWidgetColor = 0
    private var mWidgetColorWithoutTransparency = 0
    private var mFeatureLockedDialog: FeatureLockedDialog? = null
    private lateinit var binding: WidgetRecordDisplayConfigBinding

    public override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        binding = WidgetRecordDisplayConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initVariables()

        val isCustomizingColors = intent.extras?.getBoolean(IS_CUSTOMIZING_COLORS) ?: false
        mWidgetId = intent.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (mWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID && !isCustomizingColors) {
            finish()
        }

        binding.configSave.setOnClickListener { saveConfig() }
        binding.configWidgetColor.setOnClickListener { pickBackgroundColor() }

        val primaryColor = getProperPrimaryColor()
        binding.configWidgetSeekbar.setColors(getProperTextColor(), primaryColor, primaryColor)

        if (!isCustomizingColors && !isOrWasThankYouInstalled()) {
            mFeatureLockedDialog = FeatureLockedDialog(this) {
                if (!isOrWasThankYouInstalled()) {
                    finish()
                }
            }
        }

        binding.configSave.backgroundTintList = ColorStateList.valueOf(getProperPrimaryColor())
        binding.configSave.setTextColor(getProperPrimaryColor().getContrastColor())
    }

    override fun onResume() {
        super.onResume()
        window.decorView.setBackgroundColor(0)

        if (mFeatureLockedDialog != null && isOrWasThankYouInstalled()) {
            mFeatureLockedDialog?.dismissDialog()
        }
    }

    private fun initVariables() {
        mWidgetColor = config.widgetBgColor
        @Suppress("DEPRECATION")
        if (mWidgetColor == resources.getColor(R.color.default_widget_bg_color) && config.isUsingSystemTheme) {
            mWidgetColor = resources.getColor(org.fossify.commons.R.color.you_primary_color, theme)
        }

        mWidgetAlpha = Color.alpha(mWidgetColor) / 255.toFloat()

        mWidgetColorWithoutTransparency = Color.rgb(
            Color.red(mWidgetColor),
            Color.green(mWidgetColor),
            Color.blue(mWidgetColor)
        )

        binding.configWidgetSeekbar.setOnSeekBarChangeListener(seekbarChangeListener)
        binding.configWidgetSeekbar.progress = (mWidgetAlpha * 100).toInt()
        updateColors()
    }

    private fun saveConfig() {
        config.widgetBgColor = mWidgetColor
        requestWidgetUpdate()

        Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mWidgetId)
            setResult(Activity.RESULT_OK, this)
        }
        finish()
    }

    private fun pickBackgroundColor() {
        ColorPickerDialog(this, mWidgetColorWithoutTransparency) { wasPositivePressed, color ->
            if (wasPositivePressed) {
                mWidgetColorWithoutTransparency = color
                updateColors()
            }
        }
    }

    private fun requestWidgetUpdate() {
        Intent(
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            null,
            this,
            MyWidgetRecordDisplayProvider::class.java
        ).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(mWidgetId))
            sendBroadcast(this)
        }
    }

    private fun updateColors() {
        mWidgetColor = mWidgetColorWithoutTransparency.adjustAlpha(mWidgetAlpha)
        binding.configWidgetColor.setFillWithStroke(mWidgetColor, mWidgetColor)
        binding.configImage.background.mutate().applyColorFilter(mWidgetColor)
    }

    private val seekbarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            mWidgetAlpha = progress.toFloat() / 100.toFloat()
            updateColors()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}

        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }
}
