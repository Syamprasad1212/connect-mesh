# PROTOCOL.md - Connect-Mesh Binary Protocol Specification

## Overview
Connect-Mesh uses a compact binary protocol designed specifically for Bluetooth Low Energy (BLE) payload and MTU constraints.

---

## Constants & UUIDs
- **Service UUID**: `8B7A2F40-6C1D-4E92-9A31-5D8F0C72B6E1`
- **RX Characteristic UUID**: `8B7A2F41-6C1D-4E92-9A31-5D8F0C72B6E1` (Write / Write Without Response)
- **TX Characteristic UUID**: `8B7A2F42-6C1D-4E92-9A31-5D8F0C72B6E1` (Notify / Indicate)
- **Control Characteristic UUID**: `8B7A2F43-6C1D-4E92-9A31-5D8F0C72B6E1` (Handshake / Control)
- **CCCD Descriptor UUID**: `00002902-0000-1000-8000-00805F9B34FB`

---

## Packet Structure & Byte Layout

### Fixed Base Header (40 Bytes)

| Offset (Bytes) | Field | Type | Size | Description |
|----------------|-------|------|------|-------------|
| `0..1` | `Magic` | Byte Array | 2B | `0x43 0x4D` ("CM") |
| `2` | `Version` | Byte | 1B | Protocol Version (`0x01`) |
| `3` | `PacketType` | Byte | 1B | Opcode (`0x01` ANNOUNCE, `0x02` MESSAGE, `0x03` ACK, `0x04` FRAGMENT, `0x05` VOICE_FRAGMENT) |
| `4..11` | `PacketID` | Long | 8B | Unique 64-bit Packet Identifier |
| `12..19` | `SourceID` | Long | 8B | 64-bit Sender Peer ID |
| `20..27` | `DestinationID` | Long | 8B | 64-bit Target Peer ID (`0L` for Broadcast) |
| `28` | `TTL` | Byte | 1B | Time-To-Live (Initial value: `7`, decremented at relay) |
| `29..36` | `Timestamp` | Long | 8B | Epoch timestamp in milliseconds |
| `37` | `Flags` | Byte | 1B | Bitflags (`0x01` Source Route, `0x02` Encrypted, `0x04` Ack Required) |
| `38..39` | `PayloadLength` | Short | 2B | Unsigned 16-bit Payload Length |

### Authentication Tag (16 Bytes)
Appended directly after the Payload data:
- `Poly1305 MAC Tag`: 16 Bytes

### Total Base Overhead
$$\text{BaseOverhead} = 40\text{B (Header)} + 16\text{B (MAC Tag)} = 56\text{ Bytes}$$

---

## Fragment Packet Header Layout (16 Bytes)
When `PacketType == 0x04 (FRAGMENT)` or `0x05 (VOICE_FRAGMENT)`, a 16-byte `FragmentHeader` precedes the fragment payload:

| Offset (Bytes) | Field | Type | Size | Description |
|----------------|-------|------|------|-------------|
| `0..7` | `FragmentID` | Long | 8B | Message Group Identifier |
| `8..9` | `FragmentIndex` | Short | 2B | Zero-based Fragment Index |
| `10..11` | `TotalFragments` | Short | 2B | Total Fragment Count |
| `12..15` | `CRC32` | Int | 4B | Integrity Checksum of Payload |

### Dynamic Fragment Payload Capacity Formula
$$\text{maxFragmentPayload} = \text{cbNegotiatedATTMTU} - \text{ATT\_HEADER}(3\text{B}) - \text{BASE\_HEADER}(40\text{B}) - \text{FRAGMENT\_HEADER}(16\text{B}) - \text{MAC\_TAG}(16\text{B})$$
$$\text{maxFragmentPayload} = \text{cbNegotiatedATTMTU} - 75\text{ Bytes}$$

---

## Packet Types & Opcodes
1. `0x01` **`ANNOUNCE`**: Broadcasts Peer ID, nickname, version, capabilities.
2. `0x02` **`MESSAGE`**: Encrypted private text message.
3. `0x03` **`ACK`**: Application-level delivery acknowledgement (`messageId`, `status`, `timestamp`).
4. `0x04` **`FRAGMENT`**: Fragmented payload slice for text/data exceeding single MTU.
5. `0x05` **`VOICE_FRAGMENT`**: Fragmented AAC audio payload slice.

---

## Cryptographic Handshake (`Noise_XX_25519_ChaChaPoly_SHA256`)
Standard Noise XX 3-way handshake pattern:
```
<- e
-> e, ee, s, es
<- s, se
```
- Derived session keys: `CipherState (tx)` & `CipherState (rx)` using ChaCha20-Poly1305.
- Relays forward raw ciphertext without key access.
