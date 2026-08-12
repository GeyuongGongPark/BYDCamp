package com.bydcamp.api

import android.content.Context
import android.util.Base64

class BangcleCodec(context: Context) {

    private data class Tables(
        val invRound: ByteArray,    // 0x28000 bytes
        val invXor: ByteArray,      // 0x3C000 bytes
        val invFirst: ByteArray,    // 0x1000 bytes
        val round: ByteArray,       // 0x28000 bytes
        val xor: ByteArray,         // 0x3C000 bytes
        val finalTable: ByteArray,  // 0x1000 bytes
        val permDecrypt: ByteArray, // 8 bytes
        val permEncrypt: ByteArray  // 8 bytes
    )

    sealed class BangcleError(msg: String) : Exception(msg) {
        class TableFileNotFound : BangcleError("bangcle_tables.bin not found in assets")
        class BadMagic : BangcleError("Bad magic bytes in table file")
        class UnsupportedVersion(v: Int) : BangcleError("Unsupported version: $v")
        class WrongTableCount(n: Int) : BangcleError("Wrong table count: $n")
        class InvalidPadding : BangcleError("Invalid PKCS7 padding")
        class InvalidEnvelope : BangcleError("Envelope must start with 'F'")
        class Base64DecodeError : BangcleError("Base64 decode failed")
        class Utf8DecodeError : BangcleError("UTF-8 decode failed")
    }

    private val tables: Tables

    init {
        val bytes = context.assets.open("bangcle_tables.bin").use { it.readBytes() }
        tables = loadTables(bytes)
    }

    private fun loadTables(bytes: ByteArray): Tables {
        var offset = 0

        fun readU16(): Int {
            val v = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return v
        }

        fun readU32(): Int {
            val v = (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            offset += 4
            return v
        }

        val magic = bytes.slice(0..3).map { it.toInt() and 0xFF }
        if (magic != listOf(0x42, 0x47, 0x54, 0x42)) throw BangcleError.BadMagic()
        offset = 4

        val version = readU16()
        if (version != 1) throw BangcleError.UnsupportedVersion(version)

        val count = readU16()
        if (count != 8) throw BangcleError.WrongTableCount(count)

        val offsets = IntArray(8)
        val lengths = IntArray(8)
        for (i in 0 until 8) {
            offsets[i] = readU32()
            lengths[i] = readU32()
        }

        fun extract(i: Int): ByteArray = bytes.copyOfRange(offsets[i], offsets[i] + lengths[i])

        return Tables(
            invRound    = extract(0),
            invXor      = extract(1),
            invFirst    = extract(2),
            round       = extract(3),
            xor         = extract(4),
            finalTable  = extract(5),
            permDecrypt = extract(6),
            permEncrypt = extract(7)
        )
    }

    private fun prepareMatrix(block: ByteArray): ByteArray {
        val state = ByteArray(32)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                state[col * 8 + row] = block[col + row * 4]
            }
        }
        return state
    }

    private fun extractBlock(state: ByteArray): ByteArray {
        val output = ByteArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                output[col + row * 4] = state[col * 8 + row]
            }
        }
        return output
    }

    private fun encryptBlock(block: ByteArray, roundEnd: Int): ByteArray {
        val state = prepareMatrix(block)
        val temp64 = ByteArray(64)

        val rounds = minOf(9, maxOf(0, roundEnd))

        for (rnd in 0 until rounds) {
            val lVar21 = rnd * 4
            var permPtr = 0

            for (i in 0 until 4) {
                val bVar4 = tables.permEncrypt[permPtr].toInt() and 0xFF
                val lVar16 = i * 8
                val base = i * 16

                for (j in 0 until 4) {
                    val uVar8 = (bVar4 + j) and 3
                    val byteVal = state[lVar16 + uVar8].toInt() and 0xFF
                    val idx = (byteVal + (i + (lVar21 + uVar8) * 4) * 256) * 4
                    temp64[base + j * 4]     = tables.round[idx]
                    temp64[base + j * 4 + 1] = tables.round[idx + 1]
                    temp64[base + j * 4 + 2] = tables.round[idx + 2]
                    temp64[base + j * 4 + 3] = tables.round[idx + 3]
                }
                permPtr += 2
            }

            var iVar16 = 1
            for (lVar22 in 0 until 4) {
                var pbOffset = lVar22
                for (lVar10 in 0 until 4) {
                    val local10 = temp64[pbOffset].toInt() and 0xFF
                    var uVar7   = local10 and 0xF
                    var uVar26  = local10 and 0xF0
                    val f0 = temp64[pbOffset + 0x10].toInt() and 0xFF
                    val f1 = temp64[pbOffset + 0x20].toInt() and 0xFF
                    val f2 = temp64[pbOffset + 0x30].toInt() and 0xFF
                    val lVar2 = lVar10 * 0x18 + rnd * 0x60
                    var iVar25 = iVar16
                    for (lVar17 in 0 until 3) {
                        val inner = when (lVar17) { 0 -> f0; 1 -> f1; else -> f2 }
                        val uVar1  = (inner shl 4) and 0xFF
                        val uVar27 = uVar7 or uVar1
                        uVar26 = ((uVar26 shr 4) or ((inner shr 4) shl 4)) and 0xFF
                        uVar7  = (tables.xor[(lVar2 + (iVar25 - 1)) * 0x100 + uVar27].toInt() and 0xFF) and 0xF
                        val newByte = tables.xor[(lVar2 + iVar25) * 0x100 + uVar26].toInt() and 0xFF
                        uVar26 = (newByte and 0xF) shl 4
                        iVar25 += 2
                    }
                    state[lVar10 + lVar22 * 8] = ((uVar26 or uVar7) and 0xFF).toByte()
                    pbOffset += 4
                }
                iVar16 += 6
            }
        }

        if (roundEnd == 10) {
            val tmp32 = state.copyOfRange(0, 32)
            for (row in 0 until 4) {
                state[row]        = tables.finalTable[(tmp32[(0 + row) and 3].toInt() and 0xFF)     + ((0 + row) and 3) * 0x400]
                state[8  + row]   = tables.finalTable[(tmp32[8  + ((1 + row) and 3)].toInt() and 0xFF) + ((1 + row) and 3) * 0x400 + 0x100]
                state[0x10 + row] = tables.finalTable[(tmp32[0x10 + ((2 + row) and 3)].toInt() and 0xFF) + ((2 + row) and 3) * 0x400 + 0x200]
                state[0x18 + row] = tables.finalTable[(tmp32[0x18 + ((3 + row) and 3)].toInt() and 0xFF) + ((3 + row) and 3) * 0x400 + 0x300]
            }
        }

        return extractBlock(state)
    }

    private fun decryptBlock(block: ByteArray, roundStart: Int): ByteArray {
        val state = prepareMatrix(block)
        val temp64 = ByteArray(64)

        val stopBound = maxOf(0, roundStart)
        for (rnd in 9 downTo stopBound) {
            val lVar21 = rnd * 4
            var permPtr = 0

            for (i in 0 until 4) {
                val bVar3 = tables.permDecrypt[permPtr].toInt() and 0xFF
                val lVar16 = i * 8
                val base = i * 16
                for (j in 0 until 4) {
                    val uVar7 = (bVar3 + j) and 3
                    val byteVal = state[lVar16 + uVar7].toInt() and 0xFF
                    val idx = (byteVal + (i + (lVar21 + uVar7) * 4) * 256) * 4
                    temp64[base + j * 4]     = tables.invRound[idx]
                    temp64[base + j * 4 + 1] = tables.invRound[idx + 1]
                    temp64[base + j * 4 + 2] = tables.invRound[idx + 2]
                    temp64[base + j * 4 + 3] = tables.invRound[idx + 3]
                }
                permPtr += 2
            }

            var iVar15 = 1
            for (lVar21x in 0 until 4) {
                var pbOffset = lVar21x
                for (lVar9 in 0 until 4) {
                    val local10 = temp64[pbOffset].toInt() and 0xFF
                    var uVar6   = local10 and 0xF
                    var uVar26  = local10 and 0xF0
                    val f0 = temp64[pbOffset + 0x10].toInt() and 0xFF
                    val f1 = temp64[pbOffset + 0x20].toInt() and 0xFF
                    val f2 = temp64[pbOffset + 0x30].toInt() and 0xFF
                    val lVar2 = lVar9 * 0x18 + rnd * 0x60
                    var iVar25 = iVar15
                    for (lVar16 in 0 until 3) {
                        val inner = when (lVar16) { 0 -> f0; 1 -> f1; else -> f2 }
                        val uVar1  = (inner shl 4) and 0xFF
                        val uVar27 = uVar6 or uVar1
                        uVar26 = ((uVar26 shr 4) or ((inner shr 4) shl 4)) and 0xFF
                        uVar6  = (tables.invXor[(lVar2 + (iVar25 - 1)) * 0x100 + uVar27].toInt() and 0xFF) and 0xF
                        val newByte = tables.invXor[(lVar2 + iVar25) * 0x100 + uVar26].toInt() and 0xFF
                        uVar26 = (newByte and 0xF) shl 4
                        iVar25 += 2
                    }
                    state[lVar9 + lVar21x * 8] = ((uVar26 or uVar6) and 0xFF).toByte()
                    pbOffset += 4
                }
                iVar15 += 6
            }
        }

        if (roundStart == 1) {
            val tmp32 = state.copyOfRange(0, 32)
            var u8 = 1; var u10 = 3; var u12 = 2
            for (row in 0 until 4) {
                state[row]        = tables.invFirst[(tmp32[row].toInt() and 0xFF)             + row * 0x400]
                state[8  + row]   = tables.invFirst[(tmp32[8  + (u10 and 3)].toInt() and 0xFF) + (u10 and 3) * 0x400 + 0x100]
                state[0x10 + row] = tables.invFirst[(tmp32[0x10 + (u12 and 3)].toInt() and 0xFF) + (u12 and 3) * 0x400 + 0x200]
                state[0x18 + row] = tables.invFirst[(tmp32[0x18 + (u8  and 3)].toInt() and 0xFF) + (u8  and 3) * 0x400 + 0x300]
                u8++; u10++; u12++
            }
        }

        return extractBlock(state)
    }

    fun encryptCBC(data: ByteArray, iv: ByteArray = ByteArray(16)): ByteArray {
        if (data.size % 16 != 0) throw BangcleError.InvalidPadding()
        val result = ByteArray(data.size)
        var prev = iv.copyOf()
        var offset = 0
        while (offset < data.size) {
            val block = data.copyOfRange(offset, offset + 16)
            for (i in 0 until 16) block[i] = (block[i].toInt() xor prev[i].toInt()).toByte()
            val enc = encryptBlock(block, roundEnd = 10)
            enc.copyInto(result, offset)
            prev = enc
            offset += 16
        }
        return result
    }

    fun decryptCBC(data: ByteArray, iv: ByteArray = ByteArray(16)): ByteArray {
        if (data.size % 16 != 0) throw BangcleError.InvalidPadding()
        val result = ByteArray(data.size)
        var prev = iv.copyOf()
        var offset = 0
        while (offset < data.size) {
            val block = data.copyOfRange(offset, offset + 16)
            val dec = decryptBlock(block, roundStart = 1)
            for (i in 0 until 16) dec[i] = (dec[i].toInt() xor prev[i].toInt()).toByte()
            dec.copyInto(result, offset)
            prev = block
            offset += 16
        }
        return result
    }

    private fun pkcs7Pad(data: ByteArray): ByteArray {
        val pad = 16 - (data.size % 16)
        return data + ByteArray(pad) { pad.toByte() }
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        val last = data.last().toInt() and 0xFF
        if (last < 1 || last > 16 || last > data.size) throw BangcleError.InvalidPadding()
        val padStart = data.size - last
        for (i in padStart until data.size) {
            if ((data[i].toInt() and 0xFF) != last) throw BangcleError.InvalidPadding()
        }
        return data.copyOfRange(0, padStart)
    }

    fun encodeEnvelope(plaintext: String): String {
        val padded = pkcs7Pad(plaintext.toByteArray(Charsets.UTF_8))
        val cipher = encryptCBC(padded)
        return "F" + Base64.encodeToString(cipher, Base64.NO_WRAP)
    }

    fun decodeEnvelope(envelope: String): String {
        var s = envelope
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .replace("-", "+")
            .replace("_", "/")

        if (!s.startsWith("F")) throw BangcleError.InvalidEnvelope()
        s = s.substring(1)

        val rem = s.length % 4
        if (rem != 0) s += "=".repeat(4 - rem)

        val cipherData = Base64.decode(s, Base64.DEFAULT) ?: throw BangcleError.Base64DecodeError()
        val decrypted = decryptCBC(cipherData)
        val unpadded = pkcs7Unpad(decrypted)
        return String(unpadded, Charsets.UTF_8)
    }
}
