@file:Suppress("MagicNumber")

package org.fossify.voicerecorder.helpers

const val REPOSITORY_NAME = "Voice-Recorder"

const val RECORDER_RUNNING_NOTIF_ID = 10000

private const val PATH = "com.fossify.voicerecorder.action."
const val GET_RECORDER_INFO = PATH + "GET_RECORDER_INFO"
const val STOP_AMPLITUDE_UPDATE = PATH + "STOP_AMPLITUDE_UPDATE"
const val TOGGLE_PAUSE = PATH + "TOGGLE_PAUSE"
const val CANCEL_RECORDING = PATH + "CANCEL_RECORDING"

const val EXTENSION_M4A = 0
const val EXTENSION_MP3 = 1
const val EXTENSION_OGG = 2

val BITRATES_MP3 = arrayListOf(
    8000, 16000, 24000, 32000, 64000, 96000, 128000, 160000, 192000, 256000, 320000
)
val BITRATES_M4A = arrayListOf(
    8000, 14000, 24000, 28000, 32000, 64000, 96000, 128000, 160000, 192000, 288000
)
val BITRATES_OPUS = arrayListOf(
    8000, 16000, 24000, 32000, 64000, 96000, 128000, 160000, 192000, 256000, 320000
)
val BITRATES = mapOf(
    EXTENSION_M4A to BITRATES_M4A,
    EXTENSION_MP3 to BITRATES_MP3,
    EXTENSION_OGG to BITRATES_OPUS
)
const val DEFAULT_BITRATE = 96000

val SAMPLING_RATES_MP3 = arrayListOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
val SAMPLING_RATES_M4A = arrayListOf(11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
val SAMPLING_RATES_OPUS = arrayListOf(8000, 12000, 16000, 24000, 48000)
val SAMPLING_RATES = mapOf(
    EXTENSION_M4A to SAMPLING_RATES_M4A,
    EXTENSION_MP3 to SAMPLING_RATES_MP3,
    EXTENSION_OGG to SAMPLING_RATES_OPUS
)
const val DEFAULT_SAMPLING_RATE = 48000

// sampling rate -> [min bitrate, max bitrate]
// according to https://redmine.digispot.ru/projects/support-eng/wiki/Recommended_Sampling_Rate_and_Bitrate_Combinations_for_AAC_codec
val SAMPLING_RATE_BITRATE_LIMITS_M4A = mapOf(
    11025 to arrayListOf(8000, 15999),
    12000 to arrayListOf(8000, 15999),
    16000 to arrayListOf(8000, 31999),
    22050 to arrayListOf(24000, 31999),
    24000 to arrayListOf(24000, 31999),
    32000 to arrayListOf(32000, 160000),
    44100 to arrayListOf(56000, 160000),
    48000 to arrayListOf(56000, 288000)
)

// according to https://svn.code.sf.net/p/lame/svn/trunk/lame/doc/html/detailed.html#b
val SAMPLING_RATE_BITRATE_LIMITS_MP3 = mapOf(
    8000 to arrayListOf(8000, 64000),
    11025 to arrayListOf(8000, 64000),
    12000 to arrayListOf(8000, 64000),
    16000 to arrayListOf(8000, 160000),
    22050 to arrayListOf(8000, 160000),
    24000 to arrayListOf(8000, 160000),
    32000 to arrayListOf(32000, 320000),
    44100 to arrayListOf(32000, 320000),
    48000 to arrayListOf(32000, 320000)
)

// OPUS has only recommendations for bitrate, no limits: https://www.rfc-editor.org/rfc/rfc7587#section-3.1.1
// only minimum value is set according to them
val SAMPLING_RATE_BITRATE_LIMITS_OPUS = mapOf(
    8000 to arrayListOf(8000, 320000),
    12000 to arrayListOf(16000, 320000),
    16000 to arrayListOf(28000, 320000),
    24000 to arrayListOf(48000, 320000),
    48000 to arrayListOf(64000, 320000)
)

val SAMPLING_RATE_BITRATE_LIMITS = mapOf(
    EXTENSION_M4A to SAMPLING_RATE_BITRATE_LIMITS_M4A,
    EXTENSION_MP3 to SAMPLING_RATE_BITRATE_LIMITS_MP3,
    EXTENSION_OGG to SAMPLING_RATE_BITRATE_LIMITS_OPUS
)

const val RECORDING_RUNNING = 0
const val RECORDING_STOPPED = 1
const val RECORDING_PAUSED = 2

const val IS_RECORDING = "is_recording"
const val TOGGLE_WIDGET_UI = "toggle_widget_ui"

// shared preferences
const val SAVE_RECORDINGS = "save_recordings"
const val EXTENSION = "extension"
const val MICROPHONE_MODE = "microphone_mode"
const val BITRATE = "bitrate"
const val SAMPLING_RATE = "sampling_rate"
const val CHANNEL_COUNT = "channel_count"
const val RECORD_AFTER_LAUNCH = "record_after_launch"
const val USE_RECYCLE_BIN = "use_recycle_bin"
const val LAST_RECYCLE_BIN_CHECK = "last_recycle_bin_check"
const val KEEP_SCREEN_ON = "keep_screen_on"
const val WAS_MIC_MODE_WARNING_SHOWN = "was_mic_mode_warning_shown"

const val DEFAULT_RECORDINGS_FOLDER = "Recordings"

// Audio channel configuration
const val RECORD_AUDIO_MONO = 1
const val RECORD_AUDIO_STEREO = 2
const val DEFAULT_CHANNEL_COUNT = RECORD_AUDIO_STEREO
