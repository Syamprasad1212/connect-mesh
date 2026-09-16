# TESTING.md - Connect-Mesh Automated & Physical Verification Guide

## Automated Unit Tests (`app/src/test/java/com/connectmesh/`)

### Test Suite Structure
1. `PacketEncoderDecoderTest.kt`:
   - Validates encoding and decoding of `ANNOUNCE`, `MESSAGE`, `ACK`, `FRAGMENT`, `VOICE_FRAGMENT`.
   - Verifies 56B base header overhead and 16B fragment header structure.
   - Tests invalid packet rejection (bad magic, corrupted payload length, short buffer).
2. `NoiseXXTest.kt`:
   - Tests Noise XX handshake initialization, mutual peer authentication, key agreement, and ChaCha20-Poly1305 encryption/decryption.
3. `BleOperationQueueTest.kt`:
   - Tests FIFO operation ordering, execution callbacks, 5-second timeouts, exponential backoff retries, and cancellation on disconnect.
4. `DeduplicationTest.kt`:
   - Verifies LRU cache insertion, duplicate suppression, and 5-minute time window expiration.
5. `MeshRouterTest.kt`:
   - Verifies TTL decrement logic, destination matching (local delivery vs forwarding), controlled flooding, and source route execution.
6. `RouteTableTest.kt`:
   - Tests multi-hop route lookup, metric update, and route invalidation on link drop.

---

## Physical 3-Phone Verification Test (M6 Hard Gate)

### Physical Setup
- **Phone A**: Sender
- **Phone B**: Intermediate Mesh Relay Node
- **Phone C**: Target Recipient

### Step-by-Step Test Procedure
1. On all three phones, enable **Airplane Mode ON**, enable **Bluetooth ON** manually, turn **Wi-Fi OFF**, and turn **Mobile Data OFF**.
2. Position Phone A and Phone C far enough apart so they cannot directly discover or communicate with each other. Position Phone B in the center.
3. Open Connect-Mesh on all three phones.
4. Verify Diagnostics Screen on Phone B:
   - `Connected peers: A, C`
   - `Packets relayed: 0`
   - `Route: A → B → C`
5. On Phone A, open chat with Phone C and type `Hello C`. Tap Send.
6. Observe real-time flow:
   - Packet transmitted from Phone A to Phone B (`A → B`).
   - Phone B receives packet, validates TTL, decrements TTL, checks deduplication cache, and forwards to Phone C (`B → C`).
   - Phone B diagnostics update: `Packets relayed: >0`.
   - Phone C receives `Hello C` and displays message in chat UI.
7. Application ACK flow:
   - Phone C generates `ACK(packetId)`.
   - ACK routes back `C → B → A`.
   - Phone A receives ACK and updates status tick to `Delivered ✓`.
