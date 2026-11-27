package it.mareggio.face

import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class CruscottoProviderService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        return costruisci(Cruscotto.anteprima())
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return NoDataComplicationData()
        return costruisci(Cruscotto.immagine(applicationContext))
    }

    private fun costruisci(bmp: Bitmap): ComplicationData =
        SmallImageComplicationData.Builder(
            SmallImage.Builder(Icon.createWithBitmap(bmp), SmallImageType.PHOTO).build(),
            PlainComplicationText.Builder("Mareggio").build()
        ).build()
}
