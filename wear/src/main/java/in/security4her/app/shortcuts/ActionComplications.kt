package `in`.security4her.app.shortcuts

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import `in`.security4her.app.FakeCallShortcutActivity
import `in`.security4her.app.R
import `in`.security4her.app.SosShortcutActivity

/** Stateless shortcuts: no account information on the watch face, no polling or sensor work. */
abstract class ActionComplication : ComplicationDataSourceService() {
    abstract val fakeCall: Boolean

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(data(request.complicationType, preview = false))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, preview = true)

    private fun data(type: ComplicationType, preview: Boolean): ComplicationData? {
        val intent = Intent(this, if (fakeCall) FakeCallShortcutActivity::class.java else SosShortcutActivity::class.java)
        val tap = if (preview) null else PendingIntent.getActivity(this, if (fakeCall) 51 else 52, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val description = PlainComplicationText.Builder(
            if (fakeCall) "Request fake call on phone" else "Start cancellable 5 second SOS countdown").build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, if (fakeCall) R.drawable.ic_fake_call else R.drawable.ic_shield)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(if (fakeCall) "Call" else "SOS").build(), description)
                .setTitle(PlainComplicationText.Builder(if (fakeCall) "Fake" else "5 sec").build())
                .setMonochromaticImage(icon).setTapAction(tap).build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(icon, description)
                .setTapAction(tap).build()
            else -> null
        }
    }
}

class FakeCallComplication : ActionComplication() { override val fakeCall = true }
class SosComplication : ActionComplication() { override val fakeCall = false }
