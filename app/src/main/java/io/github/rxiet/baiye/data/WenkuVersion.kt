package io.github.rxiet.baiye.data

import io.github.rxiet.baiye.BuildConfig
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Protocol compatibility only; the build-specific client value is never kept in source control. */
internal object WenkuVersion {
    fun value(key: String = BuildConfig.WENKU_RELAY_BUILD_KEY, unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        if (key.isBlank()) throw IOException("中继未配置")
        val version = "1.30"
        val message = "$version|$key|${unixSeconds / 60}"
        val hmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        val hex = hmac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "$version-$key-${hex.substring(12, 20)}"
    }
}
