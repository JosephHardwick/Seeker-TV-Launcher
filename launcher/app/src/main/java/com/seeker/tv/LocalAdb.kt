package com.seeker.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * A minimal adb client that talks to the TV's own debugging port (127.0.0.1:5555, the one a PC uses over
 * Wi-Fi), so the performance monitor can run shell commands like `dumpsys meminfo` as the shell user.
 * Android 14 hides other apps' memory and CPU from normal apps; the shell user can see them.
 *
 * Plain adb protocol (no TLS: that's only for the pairing-based wireless debugging). The first connection
 * shows the TV's "Allow USB debugging?" prompt for Seeker's own RSA key; tick "Always allow" and it never
 * asks again. The key lives in Seeker's private files; revoke it in Developer options like any other.
 * Blocking: call from a background thread.
 */
class LocalAdb(ctx: Context) {
    class NotAllowed(msg: String) : Exception(msg)

    private val dir = File(ctx.filesDir, "adbkey").apply { mkdirs() }

    /** Runs [command] in a shell on the TV and returns its output. Throws on any failure. */
    fun shell(command: String, timeoutMs: Int = 15000): String {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
            s.soTimeout = PROMPT_MS  // the first time, the TV waits for someone to answer its prompt
            val input = DataInputStream(s.getInputStream().buffered())
            val out = s.getOutputStream()
            handshake(input, out)
            s.soTimeout = timeoutMs
            val local = 1
            send(out, OPEN, local, 0, "shell:$command\u0000".toByteArray())
            val result = StringBuilder()
            var remote = 0
            while (true) {
                val m = read(input)
                when (m.cmd) {
                    OKAY -> remote = m.arg0
                    WRTE -> { result.append(String(m.data)); send(out, OKAY, local, m.arg0, ByteArray(0)) }
                    CLSE -> { if (remote != 0) send(out, CLSE, local, remote, ByteArray(0)); return result.toString() }
                }
            }
        }
    }

    private fun handshake(input: DataInputStream, out: OutputStream) {
        send(out, CNXN, VERSION, MAX_DATA, "host::seeker\u0000".toByteArray())
        val key = keyPair()
        var signed = false
        while (true) {
            val m = read(input)
            when (m.cmd) {
                CNXN -> return
                STLS -> throw IllegalStateException("this port wants TLS (wireless debugging pairing); use the classic network debugging port")
                AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) continue
                    if (!signed) {
                        send(out, AUTH, AUTH_SIGNATURE, 0, sign(key, m.data))
                        signed = true
                    } else {
                        // our key isn't trusted yet: offer it, the TV shows its "Allow debugging?" prompt
                        Log.i(TAG, "adb: offering Seeker's key, accept the prompt on the TV")
                        send(out, AUTH, AUTH_RSAPUBLICKEY, 0, (publicKeyString(key) + "\u0000").toByteArray())
                        // wait for CNXN; a refused or ignored prompt ends in a timeout
                        val r = try { read(input) } catch (e: Exception) { throw NotAllowed("debugging not allowed on the TV (prompt refused or not answered)") }
                        if (r.cmd == CNXN) return
                        throw NotAllowed("debugging not allowed on the TV")
                    }
                }
            }
        }
    }

    // ---------- the key: 2048-bit RSA, kept in filesDir ----------
    private fun keyPair(): KeyPair {
        val priv = File(dir, "key"); val pub = File(dir, "key.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        pub.writeBytes(kp.public.encoded)
        priv.writeBytes(kp.private.encoded)
        return kp
    }

    /** adb signs the 20-byte token as if it were a SHA-1 digest: PKCS#1 v1.5 type 1 padding, raw RSA. */
    private fun sign(kp: KeyPair, token: ByteArray): ByteArray {
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token
        val k = 256
        val padded = ByteArray(k)
        padded[0] = 0; padded[1] = 1
        for (i in 2 until k - digestInfo.size - 1) padded[i] = 0xFF.toByte()
        padded[k - digestInfo.size - 1] = 0
        System.arraycopy(digestInfo, 0, padded, k - digestInfo.size, digestInfo.size)
        val c = Cipher.getInstance("RSA/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, kp.private)
        return c.doFinal(padded)
    }

    /** The public key in adb's own format (mincrypt RSAPublicKey, little-endian words), base64 + a name. */
    private fun publicKeyString(kp: KeyPair): String {
        val pub = kp.public as RSAPublicKey
        val words = 64
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32).toLong()
        val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n)
        val buf = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        putWords(buf, n, words)
        putWords(buf, rr, words)
        buf.putInt(pub.publicExponent.toInt())
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP) + " seeker@tv"
    }

    private fun putWords(buf: ByteBuffer, v: BigInteger, words: Int) {
        var x = v
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        repeat(words) { buf.putInt(x.and(mask).toLong().toInt()); x = x.shiftRight(32) }
    }

    // ---------- messages ----------
    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd); h.putInt(arg0); h.putInt(arg1); h.putInt(data.size)
        h.putInt(data.fold(0) { s, b -> s + (b.toInt() and 0xFF) })
        h.putInt(cmd.inv())
        out.write(h.array()); out.write(data); out.flush()
    }

    private fun read(input: DataInputStream): Msg {
        val h = ByteArray(24); input.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len); input.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    private companion object {
        const val TAG = "SeekerTV"
        const val PORT = 5555
        const val PROMPT_MS = 60_000
        const val VERSION = 0x01000001
        const val MAX_DATA = 256 * 1024
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
    }
}
