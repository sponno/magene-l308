package l308

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
object BleScan {
    const val NAME_PREFIX = "L308"

    private fun scanner(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: throw IOException("Bluetooth is off")

    private val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()

    /** Every L308 in range, as (address, name). */
    fun discover(context: Context): Flow<Pair<String, String>> = callbackFlow {
        val scanner = scanner(context)
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: return
                if (name.startsWith(NAME_PREFIX)) trySend(r.device.address to name)
            }

            override fun onScanFailed(errorCode: Int) {
                close(IOException("scan failed $errorCode"))
            }
        }
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    /** Suspends until the light with [address] advertises (i.e. is switched on). */
    suspend fun find(context: Context, address: String): BluetoothDevice = suspendCancellableCoroutine { cont ->
        val scanner = scanner(context)
        val once = AtomicBoolean(false)
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                if (once.compareAndSet(false, true)) {
                    runCatching { scanner.stopScan(this) }
                    cont.resume(r.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                if (once.compareAndSet(false, true)) cont.resumeWithException(IOException("scan failed $errorCode"))
            }
        }
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        scanner.startScan(listOf(filter), settings, cb)
        cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
    }
}
