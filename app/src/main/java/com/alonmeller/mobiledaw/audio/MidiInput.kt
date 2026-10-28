package com.alonmeller.mobiledaw.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * MIDI keyboards and controllers plugged in by USB, or Bluetooth ones the
 * phone has connected: REAPER's MIDI inputs. Every one is opened as it
 * appears; its notes and sustain pedal reach [onNote] and [onSustain] on
 * the main thread while [listening] says its name may play.
 */
class MidiInput(
    context: Context,
    private val listening: (name: String) -> Boolean,
    private val onDevices: (names: List<String>) -> Unit,
    private val onNote: (pitch: Int, velocity: Int) -> Unit,
    private val onSustain: (down: Boolean) -> Unit,
) {
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(MidiManager::class.java) else null
    private val main = Handler(Looper.getMainLooper())

    private class Opened(val name: String, val device: MidiDevice, val ports: List<MidiOutputPort>)

    /** Open devices by their id. */
    private val opened = HashMap<Int, Opened>()
    private var closed = false

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = close(device.id)
    }

    @Suppress("DEPRECATION") // The executor versions need Android 13.
    fun start() {
        val manager = manager ?: return
        runCatching {
            manager.registerDeviceCallback(callback, main)
            manager.devices.forEach(::open)
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        closed = true
        runCatching { manager?.unregisterDeviceCallback(callback) }
        opened.keys.toList().forEach(::close)
    }

    private fun open(info: MidiDeviceInfo) {
        // A device's output ports are what it plays into the phone.
        if (closed || info.outputPortCount == 0 || info.id in opened) return
        val props = info.properties
        val name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI device ${info.id}"
        runCatching {
            manager?.openDevice(info, { device ->
                if (device == null) return@openDevice
                if (closed || info.id in opened) {
                    runCatching { device.close() }
                    return@openDevice
                }
                val ports = (0 until info.outputPortCount).mapNotNull { i ->
                    device.openOutputPort(i)?.also { it.connect(Receiver(name)) }
                }
                opened[info.id] = Opened(name, device, ports)
                publish()
            }, main)
        }
    }

    private fun close(id: Int) {
        val o = opened.remove(id) ?: return
        o.ports.forEach { runCatching { it.close() } }
        runCatching { o.device.close() }
        publish()
    }

    private fun publish() = onDevices(opened.values.map { it.name }.distinct().sorted())

    /** Reads one device's bytes, which come on a thread of their own, into notes on the main thread. */
    private inner class Receiver(private val name: String) : MidiReceiver() {
        private val parser = MidiParser { status, data1, data2 ->
            main.post {
                if (closed || !listening(name)) return@post
                when (status and 0xF0) {
                    0x90 -> onNote(data1, data2)
                    0x80 -> onNote(data1, 0)
                    0xB0 -> if (data1 == SUSTAIN) onSustain(data2 >= 64)
                }
            }
        }

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) parser.feed(msg[i].toInt() and 0xFF)
        }
    }

    private companion object {
        const val SUSTAIN = 64
    }
}
