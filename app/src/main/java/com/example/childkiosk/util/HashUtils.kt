package com.example.childkiosk.util

import java.security.MessageDigest

object HashUtils {
    /**
     * 计算 SHA-256 哈希值并转为十六进制字符串
     */
    fun sha256(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
