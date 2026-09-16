# DEVELOPMENT_LOG.md - Connect-Mesh Development & Physical Debug Log

## Root Cause Analysis & Fixes (BLE Discovery & GATT Server)

### 1. BLE Advertising Payload Size Exceeded 31-Byte Limit
- **Problem**: `BleAdvertiser` put both `addServiceUuid` and `addServiceData` in primary `AdvertiseData`, producing a 44-byte payload. Android BLE enforces a 31-byte limit. `startAdvertising()` failed with error `ADVERTISE_FAILED_DATA_TOO_LARGE` (1).
- **Fix**: Primary payload contains ONLY `MESH_SERVICE_UUID` (18B). 8-byte Device ID is placed in `scanResponse`. Added `onStartSuccess` and `onStartFailure(errorCode)` logging.

### 2. Service Started Before Runtime Permission Grant
- **Problem**: `MainActivity` started `MeshForegroundService` immediately at startup while permission dialog was active. Querying `bluetoothAdapter.isEnabled` without `BLUETOOTH_CONNECT` permission returned `false` on Android 12+, causing `SCANNER_ERROR` and `ADVERTISER_ERROR`. Service ran `onCreate()` once and never retried.
- **Fix**:
  - `MainActivity` uses `ActivityResultContracts.RequestMultiplePermissions()`.
  - Service exposes `startBleStack()` called on permission grant and `onResume()`.
  - Added `BroadcastReceiver` listening for `BluetoothAdapter.ACTION_STATE_CHANGED` (`STATE_OFF` → `STATE_ON`). Manually toggling Bluetooth ON in Airplane Mode now automatically restarts advertising, scanning, and GATT server.

### 3. Peripheral GATT Server Initialization Failure
- **Problem**: `BleGattServerManager.startGattServer()` was previously uninitialized or failed due to permission timing.
- **Fix**: `startGattServer()` is explicitly invoked in `startBleStack()` after permissions are verified and Bluetooth is ON.

### 4. Removal of Fake Local Message Loopback
- **Problem**: `sendMessage()` was calling `meshRouter.handleIncomingPacket(packet)` locally, causing sent messages to mirror into sender UI as received messages.
- **Fix**:
  - `sendMessage()` ONLY encodes and dispatches packet over BLE (`connectionManager.sendPacket(...)`).
  - Receiver displays message ONLY when GATT RX callback receives real BLE bytes.
  - Delivery status updates to `Delivered ✓` ONLY when recipient generates ACK packet over BLE and sender receives it.

---

## Technical Status Matrix

| Component | Status | Verification Details |
|---|---|---|
| Runtime Permissions | FIXED | Registered `permissionLauncher`, `BLUETOOTH_SCAN/CONNECT/ADVERTISE` verified before BLE start |
| BLE Advertising | FIXED | Primary payload $\le$ 31B, scan response includes Device ID |
| BLE Scanning | FIXED | `ScanFilter` + fallback scan logging `SCAN_RESULT` |
| Bluetooth State Listener | FIXED | `BroadcastReceiver` listens for `ACTION_STATE_CHANGED` (`STATE_ON` restarts stack) |
| GATT Server | FIXED | Started in `startBleStack()`, logs `GATT_SERVER_STATUS` |
| GATT Connection | FIXED | Stateful `DISCONNECTED` → `READY` transition logged |
| Real Message Routing | FIXED | Local loopback removed; BLE RX triggers message UI |
| Application ACK | FIXED | Receiver generates ACK over BLE, sender updates `Delivered ✓` |
