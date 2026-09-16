# DEBUGGING.md - BLE Mesh Troubleshooting & Diagnostics Guide

## Common BLE Failure Modes & Solutions

### 1. GATT Write Timeout / Queue Lockup
- **Symptom**: `BleOperationQueue` stalls and stops sending packets.
- **Cause**: Android OS missed a `onCharacteristicWrite` callback or GATT status `133` occurred.
- **Fix**: `BleOperationQueue` enforces a 5-second watchdog timer per write operation. If no callback arrives within 5 seconds, the operation times out, logs `GATT_WRITE_TIMEOUT`, cancels the operation, and advances to the next queued item.

### 2. Disconnect During Multi-Hop Relay
- **Symptom**: Peer drops connection during packet forwarding.
- **Cause**: Distance or RF interference between nodes.
- **Fix**: `BleConnectionManager` moves connection state to `DISCONNECTED`, cleans queue, updates `RouteTable` to mark link offline, and schedules `BleScanner` reconnection. Pending packets in outbox will retry when peer reconnects.

### 3. Duplicate Broadcast Loops
- **Symptom**: Packets repeatedly bounce between A and B (`A → B → A → B`).
- **Cause**: Missing or un-synchronized duplicate packet cache.
- **Fix**: `DeduplicationManager` checks incoming `PacketID` against an in-memory LRU cache (1000 items, 5-minute TTL). Duplicates are dropped immediately before any relay logic triggers.

### 4. Android 14 GATT MTU Behavior
- **Symptom**: MTU request returns `false` or callback never fires.
- **Cause**: Android 14 only permits the first GATT client MTU request; subsequent requests are ignored.
- **Fix**: `BleConnectionState` records the initial MTU from `onMtuChanged()` callback and passes `cbNegotiatedMTU` to `FragmentationManager`.

---

## Real-Time Protocol Event Log Guide
The in-app Diagnostics screen streams raw protocol events:
- `SCAN_STARTED`: BLE scanner initialized.
- `PEER_DISCOVERED [id]`: Discovered BLE advertisement with Connect-Mesh service UUID.
- `GATT_CONNECTED [id]`: GATT layer connected.
- `MTU_UPDATED [mtu]`: Negotiated ATT MTU callback triggered.
- `CCCD_ENABLED`: Client Characteristic Configuration Descriptor written.
- `HANDSHAKE_SUCCESS`: Noise XX handshake completed successfully.
- `TRANSPORT_READY`: Connection moved to `READY` state.
- `PACKET_SENT [type, id]`: Outgoing packet written to BLE characteristic.
- `PACKET_RECEIVED [type, id]`: Incoming packet parsed from RX characteristic.
- `PACKET_RELAYED [id, route]`: Multi-hop forwarding executed by relay node.
- `ACK_RECEIVED [id]`: End-to-end delivery acknowledgement received.
