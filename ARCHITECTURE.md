# ARCHITECTURE.md - Connect-Mesh System Architecture

## Architecture Overview

Connect-Mesh is organized into clean, modular layers following standard modern Android architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                       │
│     (ChatsScreen, ChatDetail, Peers, Topology, Diagnostics) │
└──────────────────────────────┬──────────────────────────────┘
                               │ StateFlow / ViewModel
┌──────────────────────────────▼──────────────────────────────┐
│                    Messaging Repository                     │
│               (MessageRepository, MessageState)             │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                   Mesh Foreground Service                   │
│           (MeshForegroundService - Owner of BLE)            │
└───────┬──────────────────────┬──────────────────────┬───────┘
        │                      │                      │
┌───────▼──────┐        ┌──────▼──────┐        ┌──────▼──────┐
│ Mesh Router  │        │ Crypto Engine│        │ Outbox / DB │
│(RouteTable,  │        │ (Noise XX,   │        │ (Room DB,   │
│Deduplication)│        │BouncyCastle) │        │ MessageDao) │
└───────┬──────┘        └─────────────┘        └─────────────┘
        │
┌───────▼─────────────────────────────────────────────────────┐
│                   BLE Transport Stack                       │
│ (BleConnectionManager, BleGattServer, BleOperationQueue)   │
└─────────────────────────────────────────────────────────────┘
```

---

## Core Components Breakdown

### 1. BLE Transport Stack (`com.connectmesh.mesh`)
- **`BleConstants`**: Stores custom 128-bit service and characteristic UUIDs.
- **`BleAdvertiser` & `BleScanner`**: Operates background discovery and service UUID broadcasting.
- **`BleGattServerManager`**: Accepts incoming GATT connections and handles RX/TX characteristic interactions.
- **`BleConnectionState`**: Explicit state machine tracking peer state (`DISCONNECTED` → `CONNECTING` → `CONNECTED` → `DISCOVERING_SERVICES` → `MTU_NEGOTIATING` → `READY`).
- **`BleOperationQueue`**: Enforces sequential execution of GATT write/notify/MTU requests with 5s timeout and auto-retry.

### 2. Protocol Engine (`com.connectmesh.protocol`)
- **`Packet`**: Data model representing fixed 40-byte header + payload + 16-byte Poly1305 MAC tag.
- **`PacketEncoder` & `PacketDecoder`**: High-performance binary serializer/deserializer.

### 3. Cryptographic Layer (`com.connectmesh.crypto`)
- **`NoiseXXSession`**: Manages `Noise_XX_25519_ChaChaPoly_SHA256` handshakes and session key derivation.
- **`CryptoManager`**: Generates and manages X25519 static identity keypairs using BouncyCastle.

### 4. Mesh Routing & Reliability (`com.connectmesh.mesh`)
- **`DeduplicationManager`**: LRU cache storing up to 1000 recent packet IDs for 5 minutes to prevent broadcast loops.
- **`RouteTable`**: Dynamic routing table mapping 8-byte peer IDs to next-hop addresses, hop counts, and RSSI metrics.
- **`MeshRouter`**: Core decision engine: validates TTL, checks deduplication, consumes local packets, or forwards to target next-hop / controlled flood with 10–220ms randomized jitter.
- **`AckManager`**: Generates recipient-signed application delivery ACKs.

### 5. Foreground Service (`com.connectmesh.service`)
- **`MeshForegroundService`**: Android 14 `connectedDevice` foreground service holding ownership of BLE scan/advertise/connections/routing. UI components connect to service state via Kotlin `StateFlow`.
