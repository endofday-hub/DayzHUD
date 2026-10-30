package com.dayz.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

// Kivic HUD BLE 연결. 모든 상태는 메인 스레드에서만 만진다 (GATT 콜백은 main.post로 넘김).
// connect() 후에는 close() 전까지 끊김·무응답·검색 실패 시 계속 재연결한다.
@SuppressLint("MissingPermission") // 권한은 MainActivity에서 요청
class KivicLink(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onReady: () -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val chunks = ArrayDeque<ByteArray>()
    private var writing = false
    private val inbox = ByteArrayOutputStream()
    private var inFrame = false
    private var wanted = false // connect() ~ close() 사이
    var ready = false
        private set

    private val scanner get() = ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner

    fun connect() {
        wanted = true
        main.removeCallbacks(retry)
        if (gatt != null) return log("이미 연결 중")
        scan()
    }

    private fun scan() {
        val s = scanner ?: return drop("블루투스가 꺼져 있음")
        log("KIVIC HUD 검색 중…")
        s.startScan(scanCb)
        main.postDelayed(stopScan, 15_000)
    }

    // TMAP 콜백 등 아무 스레드에서 불러도 된다
    fun sendTbt(t: Tbt) {
        main.post {
            if (!ready) return@post log("HUD 미연결")
            log("TBT code=${t.turnCode} ${t.turnDistance}m display=${t.naviDisplayStatus} status=${t.naviStatus}")
            send(KivicPacket.tbt(t))
        }
    }

    fun sendFrames(frames: List<ByteArray>) {
        main.post { if (ready) frames.forEach(::send) }
    }

    fun close() {
        wanted = false
        reset()
    }

    // 연결 상태만 정리하고, 원하던 연결이면 잠시 후 다시 검색
    private fun drop(reason: String) {
        log(reason)
        reset()
        if (!wanted) return
        log("${RETRY_MS / 1000}초 후 재연결")
        main.postDelayed(retry, RETRY_MS)
    }

    private val retry = Runnable { if (wanted && gatt == null) scan() }

    private fun reset() {
        ready = false
        main.removeCallbacks(retry)
        main.removeCallbacks(watchdog)
        main.removeCallbacks(forceMode)
        main.removeCallbacks(stopScan)
        scanner?.stopScan(scanCb)
        gatt?.close()
        gatt = null
        rx = null
        chunks.clear()
        writing = false
    }

    private val stopScan: Runnable = Runnable {
        scanner?.stopScan(scanCb)
        if (gatt == null) drop("검색 시간 초과 — HUD 전원, 원본 Kivic 앱 연결 여부 확인")
    }

    private val scanCb: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.contains("KIVIC HUD") || gatt != null) return
            main.removeCallbacks(stopScan)
            scanner?.stopScan(this)
            log("발견: $name ${result.device.address}")
            gatt = result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) = log("검색 실패 ($errorCode)")
    }

    private val gattCb: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    log("연결됨 → 서비스 탐색")
                    g.discoverServices()
                } else {
                    drop("연결 끊김 (status $status)")
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val svc = g.getService(NUS)
                val tx = svc?.getCharacteristic(NUS_TX)
                rx = svc?.getCharacteristic(NUS_RX)
                val cccd = tx?.getDescriptor(CCCD)
                if (cccd == null || rx == null) {
                    drop("UART 서비스 없음 (status $status)")
                    return@post
                }
                g.setCharacteristicNotification(tx, true)
                val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                // GATT는 요청을 하나씩만 처리한다. HUD 이벤트가 먼저 와도 이 쓰기가 끝날 때까지 전송은 큐에 대기
                writing = true
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, on)
                else { cccd.value = on; g.writeDescriptor(cccd) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                log("수신 알림 활성화 ($status) → 연결 확인 전송")
                writing = false
                send(KivicPacket.uartCheck())
                main.postDelayed(forceMode, 3_000)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                writing = false
                pump()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { receive(value) }
        }

        @Deprecated("Android 12 이하")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val value = c.value ?: return
            main.post { receive(value) }
        }
    }

    // 원본 앱은 UartConnectionEvent 응답을 받고 모드를 보낸다. 응답이 없으면 진단용으로 그냥 진행
    private val forceMode: Runnable = Runnable {
        if (!ready) {
            log("연결 확인 응답 없음 → TBT 모드 강제 전송")
            becomeReady()
        }
    }

    // HUD는 5초마다 UartConnectionEvent를 보낸다 → 12초 끊기면 끊김 콜백이 없어도 정리 (원본 앱과 같은 값)
    private val watchdog = Runnable { if (ready) drop("HUD 응답 없음 → 연결 정리") }

    private fun feedWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, ALIVE_TIMEOUT_MS)
    }

    private fun becomeReady() {
        main.removeCallbacks(forceMode)
        ready = true
        send(KivicPacket.mode(KivicPacket.MODE_TBT))
        feedWatchdog()
        log("준비 완료 — TBT 전송 가능")
        onReady()
    }

    private fun receive(bytes: ByteArray) {
        for (b in bytes) {
            when (b.toInt() and 0xFF) {
                0x02 -> { inbox.reset(); inFrame = true }
                0x03 -> if (inFrame) { inFrame = false; handle(KivicPacket.unescape(inbox.toByteArray())) }
                else -> if (inFrame) inbox.write(b.toInt())
            }
        }
    }

    private fun handle(p: ByteArray) {
        if (p.size < 3) return
        val (cmd, p1, p2) = Triple(p[0].toInt(), p[1].toInt(), p[2].toInt())
        if (cmd == 3 && p1 == 1 && p2 == 1) { // UartConnectionEvent
            if (!ready) { // 5초마다 오므라 첫 응답만 기록
                log("HUD 응답: 연결 확인 ${p.hex()}")
                becomeReady()
            } else {
                // 원본 앱처럼 매 이벤트마다 KeepAlive로 답한다 (10초 타이머로 보내면 HUD에 "phone connected"가 반복됨)
                send(KivicPacket.keepAlive())
                feedWatchdog()
                // 전원 직후엔 BLE 칩이 먼저 붙고 HUD 본체(Android)는 나중에 켜져 모드·설정을 놓친다 → kivicMode -1로 알 수 있음
                val mode = if (p.size >= 7) ByteBuffer.wrap(p, 3, 4).int else KivicPacket.MODE_TBT
                if (mode != KivicPacket.MODE_TBT) {
                    log("HUD 모드 $mode → TBT 모드·설정 다시 전송")
                    send(KivicPacket.mode(KivicPacket.MODE_TBT))
                    onReady()
                }
            }
        } else if (!(cmd == 2 && p1 == 15)) { // KeepAlive 응답은 조용히
            log("HUD 수신 ${p.hex()}")
        }
    }

    private fun send(frame: ByteArray) {
        // 원본 앱과 동일하게 19바이트씩, 이전 조각 write 콜백 후 다음 조각
        for (i in frame.indices step 19) chunks.addLast(frame.copyOfRange(i, minOf(i + 19, frame.size)))
        pump()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        val g = gatt ?: return
        val c = rx ?: return
        if (writing || chunks.isEmpty()) return
        val chunk = chunks.removeFirst()
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writing = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, chunk, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = chunk
            g.writeCharacteristic(c)
        }
        if (!writing) log("쓰기 실패 — 조각 버림")
    }

    companion object {
        private const val RETRY_MS = 5_000L
        private const val ALIVE_TIMEOUT_MS = 12_000L // 원본 앱과 같은 값
        private val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
