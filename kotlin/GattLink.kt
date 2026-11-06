package l308

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * One BLE connection with Android's one-operation-at-a-time GATT rule
 * wrapped as suspend calls. Notifications go to [onNotify].
 */
@SuppressLint("MissingPermission")
class GattLink(private val context: Context, private val device: BluetoothDevice) {
    var onNotify: (UUID, ByteArray) -> Unit = { _, _ -> }

    /** Completes when the link drops (or [close] is called). */
    val disconnected = CompletableDeferred<Unit>()

    /** Negotiated ATT MTU. */
    var mtu = 23
        private set

    private val ops = Mutex()
    @Volatile private var pending: CompletableDeferred<ByteArray?>? = null
    private val connected = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val e = IOException("disconnected (gatt status $status)")
                connected.completeExceptionally(e)
                pending?.completeExceptionally(e)
                disconnected.complete(Unit)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattLink.mtu = mtu
            done(status, null)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = done(status, null)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = done(status, null)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = done(status, null)

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            done(status, c.value?.copyOf())

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            done(status, value)

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { onNotify(c.uuid, it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onNotify(c.uuid, value)
    }

    private fun done(status: Int, value: ByteArray?) {
        val d = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) d.complete(value) else d.completeExceptionally(IOException("gatt status $status"))
    }

    suspend fun connect(timeoutMs: Long = 20_000) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        withTimeout(timeoutMs) { connected.await() }
    }

    suspend fun requestMtu(size: Int) {
        op("mtu") { it.requestMtu(size) }
    }

    suspend fun discoverServices() {
        op("discover") { it.discoverServices() }
    }

    fun has(service: UUID, char: UUID) = gatt?.getService(service)?.getCharacteristic(char) != null

    suspend fun enableNotify(service: UUID, char: UUID) {
        val c = characteristic(service, char)
        val g = gatt ?: throw IOException("closed")
        if (!g.setCharacteristicNotification(c, true)) throw IOException("notify refused for $char")
        val cccd = c.getDescriptor(CCCD) ?: throw IOException("no CCCD on $char")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("cccd") {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                it.writeDescriptor(cccd)
            }
        }
    }

    /** Write without response — what the light uses for every command. */
    suspend fun write(service: UUID, char: UUID, data: ByteArray) {
        val c = characteristic(service, char)
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op("write") {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                it.writeCharacteristic(c)
            }
        }
    }

    suspend fun read(service: UUID, char: UUID): ByteArray? {
        val c = characteristic(service, char)
        return op("read") { it.readCharacteristic(c) }
    }

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        disconnected.complete(Unit)
    }

    private fun characteristic(service: UUID, char: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(char) ?: throw IOException("missing $service/$char")

    private suspend fun op(name: String, start: (BluetoothGatt) -> Boolean): ByteArray? = ops.withLock {
        val g = gatt ?: throw IOException("closed")
        if (disconnected.isCompleted) throw IOException("disconnected")
        val d = CompletableDeferred<ByteArray?>()
        pending = d
        try {
            if (!start(g)) throw IOException("$name refused")
            withTimeout(OP_TIMEOUT_MS) { d.await() }
        } finally {
            pending = null
        }
    }

    private companion object {
        const val OP_TIMEOUT_MS = 5_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
