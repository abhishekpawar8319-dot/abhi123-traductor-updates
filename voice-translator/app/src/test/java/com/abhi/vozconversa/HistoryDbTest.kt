package com.abhi.vozconversa

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.abhi.vozconversa.core.Lang
import com.abhi.vozconversa.core.TranslationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryDbTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun rec(t: Long, src: Lang, o: String, tr: String) = TranslationRecord(0, t, 1, src, o, tr)

    @Test fun savesAndSurvivesReopen() {
        HistoryDb(ctx, "t1.db").use { db ->
            db.save(rec(1, Lang.ES, "Hola", "Hello"))
            db.save(rec(2, Lang.EN, "Bye", "Adiós"))
        }
        HistoryDb(ctx, "t1.db").use { db ->
            val all = db.all()
            assertEquals(listOf("Hola", "Bye"), all.map { it.original })
            assertEquals(Lang.EN, all[1].source)
            assertEquals("Adiós", db.recent(10).first().translated) // newest first
        }
    }

    @Test fun searchDeleteAndRetention() {
        HistoryDb(ctx, "t2.db").use { db ->
            val id = db.save(rec(10, Lang.ES, "Dónde está el camión", "Where is the truck"))
            db.save(rec(20, Lang.EN, "Good morning", "Buenos días"))
            db.save(rec(30, Lang.EN, "The truck is here", "El camión está aquí"))
            assertEquals(2, db.recent(10, "truck").size)
            assertEquals(2, db.recent(10, "camión").size)
            assertEquals(1, db.delete(id))
            assertEquals(1, db.deleteOlderThan(25))
            assertEquals(1, db.count())
            db.deleteAll()
            assertEquals(0, db.count())
        }
    }

    @Test fun storageStaysSmallForAFullDay() {
        HistoryDb(ctx, "t3.db").use { db ->
            // A very busy 24 hours: 5,000 sentences.
            repeat(5000) { i ->
                db.save(rec(i.toLong(), if (i % 2 == 0) Lang.ES else Lang.EN,
                    "Necesito que traigas las herramientas a la torre número $i",
                    "I need you to bring the tools to tower number $i"))
            }
            val size = db.sizeBytes(ctx)
            assertEquals(5000, db.count())
            assertTrue("db size $size", size < 2_000_000) // under 2 MB per busy day
        }
    }
}
