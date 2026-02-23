package com.whatisseimo.doing.util

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val chinaZone = ZoneId.of("Asia/Shanghai")
private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

fun chinaDate(epochMillis: Long = System.currentTimeMillis()): String {
    return Instant.ofEpochMilli(epochMillis).atZone(chinaZone).toLocalDate().format(dateFormatter)
}

fun md5Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5")
    val hash = digest.digest(bytes)
    return hash.joinToString("") { each -> "%02x".format(each) }
}
