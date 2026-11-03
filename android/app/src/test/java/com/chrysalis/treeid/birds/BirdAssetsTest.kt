package com.chrysalis.treeid.birds

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BirdAssetsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Test fun labelsAndBundledModelMatchThePinnedRelease() {
        val catalog = BirdCatalog.load(context)
        assertEquals(6522, catalog.labels.size)
        assertEquals("American Robin", catalog["Turdus migratorius"]?.commonName)
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("birds/birdnet-v2.4-fp16.tflite").use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals("5c64ba3ffab264ccbfafe784896392051a86be63b0cc507e7683db7df114546b", digest.digest().joinToString("") { "%02x".format(it) })
    }
    @Test fun applicationCannotUseInternetAndDisablesBackup() {
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("android.permission.INTERNET" in permissions)
        assertTrue("android.permission.RECORD_AUDIO" in permissions)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
