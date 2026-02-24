package com.whatisseimo.doing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryManagerCatalogSyncUtilsTest {
    @Test
    fun `parseRootPackageEntriesFromPmList parses apk path containing equals`() {
        val stdout = """
            package:/data/app/~~abc==/com.tencent.mm-xx==/base.apk=com.tencent.mm
            package:com.example.plain
            package:
            random line
        """.trimIndent()

        val entries = parseRootPackageEntriesFromPmList(stdout)

        assertEquals(2, entries.size)
        assertEquals("com.tencent.mm", entries[0].packageName)
        assertEquals("/data/app/~~abc==/com.tencent.mm-xx==/base.apk", entries[0].apkPath)
        assertEquals("com.example.plain", entries[1].packageName)
        assertNull(entries[1].apkPath)
    }

    @Test
    fun `mergeInstalledAppsForCatalogSync keeps primary order and appends missing root apps`() {
        val primary = listOf(
            InstalledAppInfo(
                packageName = "com.tencent.mm",
                appName = "com.tencent.mm",
                isSystemApp = false,
                apkPath = "/data/app/mm/base.apk",
            ),
            InstalledAppInfo(
                packageName = "com.twitter.android",
                appName = "x",
                isSystemApp = false,
                apkPath = "/data/app/twitter/base.apk",
            ),
        )
        val supplement = listOf(
            InstalledAppInfo(
                packageName = "com.tencent.mm",
                appName = "wechat",
                isSystemApp = false,
                apkPath = "/mnt/expand/mm/base.apk",
            ),
            InstalledAppInfo(
                packageName = "com.zhihu.android",
                appName = "zhihu",
                isSystemApp = false,
                apkPath = "/mnt/expand/zhihu/base.apk",
            ),
        )

        val merged = mergeInstalledAppsForCatalogSync(primary, supplement)

        assertEquals(3, merged.size)
        assertEquals("com.tencent.mm", merged[0].packageName)
        assertEquals("wechat", merged[0].appName)
        assertEquals("/data/app/mm/base.apk", merged[0].apkPath)
        assertEquals("com.twitter.android", merged[1].packageName)
        assertEquals("x", merged[1].appName)
        assertEquals("com.zhihu.android", merged[2].packageName)
    }
}
