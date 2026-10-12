# Noray — Security review (internal)

**What this is.** A review of the transport cryptography and key handling by the people who wrote the code, done by reading
it against the Noise specification and the RFCs it cites, writing tests that pin each property, and fixing what was found.
**What this is not.** An independent audit. The author of a protocol is the worst person to find its flaws; everything below
should be read as "these problems existed and are fixed", never as "there are no others". The handshake is Noise IKpsk2
with an extra ML-KEM-768 step assembled from BouncyCastle primitives; it is custom and has had no formal analysis
(ProVerif / Tamarin / cryptographic proof). If the project is going to protect anyone who is in danger, pay for an external review first.

Scope: `net.overlay` (handshake, session, framing, PSK rotation), `net.overlay.StaticKeys`, `data.crypto` (keyring, sealed
files), the new onion layer (`net.onion`) and the process sandbox (`sandbox`). Out of scope: BouncyCastle itself, the JVM,
the OS, the erasure-coding and chunk AEAD design in `data/` beyond how keys are derived and sealed.

## Findings and fixes

| # | Finding | Fix | Pinned by |
|---|---|---|---|
| F1 | **Handshake message 1 had no freshness.** A recorded message 1 could be replayed: the responder spent CPU, replaced its session state and could be made to reset a live session. | Message 1 carries an encrypted payload `pskTag(1) \| timestamp_ms(8)`. The responder accepts it only inside ±300 s of its own clock **and** only if the stamp is greater than the last accepted one from that peer. Replays get no answer and change nothing (metric `net.handshakes.replayed`). | `HandshakeHardeningTest.aReplayedMessage1GetsNoAnswerAndChangesNothing` |
| F2 | **The ML-KEM ciphertext was not bound into the handshake transcript.** An attacker able to alter it could not learn keys, but the two sides did not authenticate the same transcript, which is exactly the kind of gap hybrid constructions get wrong. | The ciphertext is mixed into the hash (`mixHash(ct)`) after the KEM secret is mixed into the chaining key, on both sides. | `NoiseSecurityTest.everyTamperedByteOfMessage1IsCaughtByTheResponderOrByTheInitiator` (every byte position) |
| F3 | **Lengths were not validated before work.** Wrong-sized handshake messages reached the DH / KEM code. | Exact lengths (`MSG1_LEN`, `MSG2_LEN`, KEM ciphertext length) are checked first; anything else is dropped. | `messagesOfTheWrongLengthAreRefusedBeforeAnyWork` |
| F4 | **PSK selection leaked or was fragile during rotation.** | The PSK tag travels inside the encrypted payload of message 1, so a passive observer cannot tell which key a handshake uses; the responder selects the key by tag. | `PskRotationTest` |
| F5 | **Sessions never expired.** A session lived until a peer restarted; the nonce counter was the only bound. | Initiators re-handshake after 120 s or 2^30 packets, whichever comes first. A hard cap of 2^60 messages per session key remains as a backstop. | `HandshakeHardeningTest.sessionsAreReplacedAfterTheirLifetime` |
| F6 | **Long-term network keys and the node identity were stored in clear (mode 0600).** Seizure of the disk gave the node's identity. | `local.keys.sealed` and the node identity are sealed under the keyring (Argon2id-derived master key, authenticated encryption, label-bound). Without a keyring the old plain file is still used (and a sealed file is refused to be ignored). A plain file from before is sealed on first start. | `FsModuleInitTest`, `ManagedBootTest` |
| F7 | **Weak Argon2 parameters were accepted silently.** | `KeyringProvider` refuses memory < 19 MiB or iterations < 2 for new keyrings unless `noray.keyring.argon2.insecure=true` (tests only). | `IdentityCommandTest`, `PoolIntegrationTest` (use the insecure flag explicitly) |
| F8 | Dead second key-mixing helper (`mixKey2`) that nothing used but that suggested an alternative derivation. | Removed. | compile |

Primitives are checked against independent references rather than against themselves: BLAKE2s (RFC 7693 vector),
HMAC-BLAKE2s and HKDF (rebuilt independently), X25519 (RFC 7748 vector, plus refusal of low-order points), ChaCha20-Poly1305
nonce layout (compared with the JDK implementation). Both sessions are checked to talk in both directions, a different mesh
key never completes, an impostor without the static key cannot answer, two handshakes never share keys, and the sliding
replay window refuses duplicates, altered packets and anything older than the window, and a forged packet does not move it.

## New code reviewed the same way

- **Onion cells (`net.onion`).** Per-hop ephemeral X25519, HKDF-derived AEAD key and length mask, additional data binds the
  ephemeral key, the node key and the length; a relay that cannot authenticate a layer drops the cell without reply.
  Tests: constant size at every hop and for every route length, no common 16-byte window between consecutive hops, plaintext
  never visible on a link, tampering anywhere is refused as invalid or as a replay, replay filter (fails closed when full),
  expiry, a cell addressed to someone else cannot be peeled, bounded reassembly. Known gaps: the padding of the first cell is
  not authenticated (it is discarded, and a change there is caught as a replay); timing and volume correlation; classical
  X25519 for the layers; no replies.
- **Namespace rules.** Rules are enforced at commit time under the lock, not only at preparation, so a rule changed while a
  slow write is in flight still applies (`NamespaceRulesTest`, `NamespaceConcurrencyTest`).
- **Process sandbox (`sandbox`).** The tests do not trust the launcher's flags: the program reads `/proc/self/status` and
  fails the run unless the kernel reports no effective or bounding capabilities, `NoNewPrivs` set and a seccomp filter in
  mode 2, and it also tries (and must fail) to reach the host loopback, resolve a name, see the host's home, write to `/usr`,
  fill `/tmp` past its quota, see other processes, create a mount namespace or mount something.

## Residual risks (known and not fixed)

1. No independent review, no formal analysis of the hybrid handshake.
2. Timing side channels: BouncyCastle's ML-KEM and X25519 are used as a library; this project does not verify that they are
   constant-time on the JVM in use.
3. The clock is trusted within ±300 s for handshake freshness and certificate checks; a node whose clock an attacker controls
   can be made to refuse or accept stale handshakes (the monotonic stamp limits the second case).
4. Compromise of a running, unlocked node exposes the keyring and every key in memory; sealing protects data at rest only.
5. Traffic analysis beyond padding, cover traffic, the optional onion delay and obfuscation: a global passive observer
   correlating timing and volume is not defeated.
6. The mesh PSK is shared by every node and distributed manually; it keeps strangers out and blunts scanning, it is not
   per-peer authentication (certificates and enrolled static keys are).
7. The process sandbox is Linux-only, relies on user namespaces and a deny-list seccomp filter, and does not defend against
   kernel bugs reachable through the system calls it leaves open.
