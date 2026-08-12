package com.bydcamp.api

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.uppercase()
    }

    fun pwdLoginKey(password: String): String = md5Hex(md5Hex(password))

    fun sha1Mixed(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))

        // 홀수 인덱스 대문자, 짝수 인덱스 소문자 혼합
        val mixed = buildString {
            for ((i, byte) in bytes.withIndex()) {
                val hex = "%02x".format(byte.toInt() and 0xFF)
                append(if (i % 2 == 0) hex.uppercase() else hex.lowercase())
            }
        }

        // 짝수 위치의 '0' 제거
        return buildString {
            for ((j, ch) in mixed.withIndex()) {
                if (ch == '0' && j % 2 == 0) continue
                append(ch)
            }
        }
    }

    fun computeCheckcode(jsonStr: String): String {
        val md5 = md5Hex(jsonStr).lowercase()
        return md5.substring(24, 32) + md5.substring(8, 16) + md5.substring(16, 24) + md5.substring(0, 8)
    }

    fun aesEncryptHex(plaintext: String, keyHex: String): String {
        val keyBytes = hexToBytes(keyHex)
        val iv = ByteArray(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun aesDecryptUTF8(cipherHex: String, keyHex: String): String {
        val keyBytes = hexToBytes(keyHex)
        val cipherData = hexToBytes(cipherHex)
        val iv = ByteArray(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(cipherData)
        return String(decrypted, Charsets.UTF_8)
    }

    fun buildSignString(fields: Map<String, String>, password: String): String {
        val pairs = fields.entries.sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        return "$pairs&password=$password"
    }

    fun hexToBytes(hex: String): ByteArray {
        val s = hex.lowercase()
        val len = s.length / 2
        return ByteArray(len) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
