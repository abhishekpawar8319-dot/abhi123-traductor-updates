package com.abhi.vozconversa

import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.TranslationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeakerButtonTest {

    @Test fun appIsNamedAbhishek() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals("Abhishek", activity.getString(R.string.app_name))
        assertEquals("Abhishek", activity.applicationInfo.loadLabel(activity.packageManager).toString())
    }

    @Test fun tapTurnsSpeakerOffAndOnAndIsRemembered() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs(ctx).speakAloud = true
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val button = activity.findViewById<Button>(R.id.speaker)
        assertTrue(button.text.contains("Speaker ON"))
        assertTrue(button.isActivated)

        button.performClick()
        assertFalse(Prefs(ctx).speakAloud)
        assertTrue(button.text.contains("Speaker OFF"))
        assertFalse(button.isActivated)

        button.performClick()
        assertTrue(Prefs(ctx).speakAloud)
        assertTrue(button.text.contains("Speaker ON"))

        // Still on after the app is closed and opened again.
        val again = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue(again.findViewById<Button>(R.id.speaker).text.contains("Speaker ON"))
    }

    @Test fun everySavedTranslationHasAPlayButton() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        HistoryDb.get(ctx).save(TranslationRecord(0, 1000, 1, Lang.ES, "La escalera está rota", "The ladder is broken"))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        Thread.sleep(300) // history loads in the background
        shadowOf(Looper.getMainLooper()).idle()
        val list = activity.findViewById<RecyclerView>(R.id.list)
        list.measure(0, 0); list.layout(0, 0, 1080, 2000)
        assertEquals(1, list.adapter!!.itemCount)
        val play = list.getChildAt(0).findViewById<Button>(R.id.play)
        assertEquals(android.view.View.VISIBLE, play.visibility)
        play.performClick() // reads it aloud; must not crash when the translator is stopped
    }
}
