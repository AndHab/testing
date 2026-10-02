package com.andhab.cubelens.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The picked cube size outlives the app: [PreferencesCubeSizeStore]. */
@RunWith(AndroidJUnit4::class)
class CubeSizeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aPickedSizeIsRememberedAcrossLaunches() {
        assertNull("nothing picked yet", PreferencesCubeSizeStore(context).load())
        PreferencesCubeSizeStore(context).save(6)
        assertEquals(6, PreferencesCubeSizeStore(context).load())
    }

    @Test
    fun anUnsupportedSizeReadsAsNone() {
        PreferencesCubeSizeStore(context).save(9)
        assertNull(PreferencesCubeSizeStore(context).load())
    }

    @Test
    fun theMemoryStoreRemembersForTheSession() {
        val store = InMemoryCubeSizeStore()
        assertNull(store.load())
        store.save(2)
        assertEquals(2, store.load())
    }
}
