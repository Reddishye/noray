# Noray — Architecture & Design Decisions

Noray is a **data-centric core**: a modular Java runtime whose job is to store, replicate and
serve data safely on hostile infrastructure. Everything else (agents, file repositories, static
web hosting, scraping, search) is a *module* built on top of the core. This document records the
decisions that shape the foundations. It is the reference for what to build and in which order.

## 1. Goals and non-goals

**Goals**

- Resist: node compromise, physical seizure of a node, network MITM, raids, surveillance.
- Scale from a single node to thousands; objects from a few bytes to terabytes.
- Be fully modular: storage drivers, transports, crypto suites, and feature modules are pluggable.
- Stay Java-only at the core, with stable protocols/APIs so other tools can talk to it.

**Non-goals (for the core)**

- Agents, web hosting, scraping and search are separate modules. The core only has to expose the
  foundations they need (identities, capabilities, storage, naming, transport).
- Global consensus. See §4.

**Honest limits.** No system protects against everything. Traffic analysis, a compromised
*unlocked* endpoint (keys in RAM) and a determined global observer can only be mitigated, not
eliminated. Each mitigation is listed in §6 so the residual risk is explicit.

## 2. Layers

```
 modules (agents, repo, web, scraping, search)         <- built on top, not part of the core
 ----------------------------------------------------
 capabilities / identities / audit                      <- who may do what
 naming: signed version log per namespace               <- mutable names
 data model: chunked, encrypted, content-addressed DAG  <- immutable data
 object stores / volumes / replication (+ erasure code) <- where bytes live
 mesh transport (Noise IK + ML-KEM, pluggable endpoints)<- how nodes talk
 kernel: modules, services, events, config              <- lifecycle
```

## 3. Data model

- **Immutable blobs.** Data is split into chunks. Each chunk is encrypted on the client side with a
  random per-file key. The blob id is the hash of the **ciphertext**, not of the plaintext, so a node
  (or an attacker holding its disk) cannot confirm that a known file is stored there.
- **Merkle DAG.** Files are trees of chunks; directories are manifests of names → child ids. This gives
  streaming, resumable transfer, partial reads, deduplication of identical encrypted chunks and TB-sized
  files with the same mechanism as tiny ones.
- **Manifests are encrypted** too: names, sizes and structure are not visible to storage nodes. Chunk
  sizes are padded to hide exact lengths.
- Storage nodes only ever hold ciphertext. A compromised or seized node leaks nothing readable.

### 3.1 Data engine: chunks, storage classes, erasure coding, placement (implemented in `com.redactado.noray.data`)

**Temperature belongs to the data, not to the nodes.** A first version dedicated nodes to hot or cold
data; that wastes bandwidth (cold nodes idle while hot ones saturate) and cannot follow data whose
access pattern changes. The model is now:

- *Nodes* describe capabilities only: `capacityWeight`, `speedWeight`, failure `domain`, and an optional
  opt-out (`accepts(Tier)`) for dedicated boxes. Every node can hold every class of data.
- *Data* carries a storage class: each chunk is stored as one or more **variants** (class, codec,
  redundancy) chosen by a `StoragePolicy`. The policy is picked per file at `put`, can be assigned per
  folder/partition by the caller, and can change later per file or per range of chunks (`retier`).
- *Placement* ties them together: HOT variants are spread over all accepting nodes weighted by **speed**,
  COLD variants weighted by **capacity**. Reads of hot data therefore draw bandwidth from every fast node,
  and big nodes absorb most cold data.

`StoragePolicy.tiered()` is the recommended layout: a HOT variant (deflate-fast, 2 full replicas: one fetch,
cheap decode) plus a COLD variant (deflate-max, Reed-Solomon 4+4: survives 4 lost nodes at 2x overhead).
`archive(k, m)` keeps only the COLD variant; `single()` only one hot copy (development).

Write path per chunk (4 MiB by default), executed on the client (the holder of the file key):

```
plaintext -> compress (variant codec; NONE if < 5% gained) -> frame [len][data][zero padding to size bucket]
          -> AEAD seal (ChaCha20-Poly1305, one-time key per chunk and variant, AAD binds index/salt/class/codec/length)
          -> replicate, or split into k shards + m parity (systematic Reed-Solomon over GF(256))
          -> place every blob with weighted rendezvous hashing, preferring nodes that do not already hold
             another variant of the same chunk
```

Design rules and why:

- **Compress before encrypt, on the client.** Nodes only see ciphertext and cannot recompress it, so the
  fast and the aggressive copy are two independently produced *variants*, not one blob transcoded in
  place. Creating or changing a variant therefore needs the file key.
- **Compression leaks length** (CRIME-style). Compressed chunks are padded to size buckets (8 steps per
  octave, at most 12.5% overhead), keys are random per file (no convergent encryption, hence no
  deduplication across files; deliberate trade-off).
- **Nonce safety.** Chunk keys derive from the file key with HKDF over `(index, variant salt)`; each variant
  carries a random public 64-bit salt, so variants created at different times (re-tiering) never share a key.
  Manifest page keys derive from `(tree salt, level, index)`, so a re-tiered tree never reuses a page key.
  Every key encrypts exactly one message, which is why the constant zero nonce is safe.
- **The manifest must survive as many nodes as the data.** Pages are small and kept in `metadataReplicas` copies per class
  of data; erasure-coded policies use `m + 1` (the `tiered` default is 5), because an erasure-coded file whose root page sat on
  three nodes was lost with those three although its shards tolerate four losses (found by a randomised test: 3.6 % of runs).
  Consequence: on a cluster with fewer nodes than copies, every node holds a page, and a node that rots permanently cannot
  be "moved off" by scrub.
- **Blob id = SHA-256 of what is stored.** Every read verifies it before using the bytes and the AEAD tag
  authenticates afterwards: a corrupt or malicious node can only make a copy unusable, never wrong.
- **Erasure coding only when worth it.** Chunks whose ciphertext is below `ecMinCipherLen` use `m+1`
  replicas (same loss tolerance, no tiny shards).
- **Weighted rendezvous hashing** (`-ln(u)/weight`): no shared placement table, adding or removing a node
  only moves its own share, and the first candidates come from distinct failure domains. Placement is a
  *hint*: the manifest records the actual nodes, so writes spill to the next candidate when a node fails
  and readers never recompute anything.
- **Manifest = encrypted Merkle tree** (fanout 512: a 1 TB file needs 3 levels). A `FileRef` (root id, file
  key, tree salt) is the only handle and is a **secret**. Pages are copied for every class the file uses,
  on fast nodes, so losing the nodes of one class does not strand the other.
- **Read path.** HOT variants first, then COLD; the first variant that yields authenticated plaintext wins.
  Up to `window` chunks are fetched concurrently, replicas are picked at random (load spreads over the
  copies), erasure-coded chunks fetch their data shards in parallel and pull parity only to fill gaps (no
  matrix work in the common case). Range reads descend the tree and fetch only the overlapping chunks.
- **Writes** run the same window of chunks in parallel. A failed put (not enough nodes, I/O error) removes what
  it wrote, best effort, and fails with `QUORUM_FAILED` when a class cannot be satisfied.
- **Re-tiering** (`retier(ref, policy, segments)`): for each chunk, variants that already satisfy the target are
  kept untouched, missing ones are created from the decrypted chunk, and variants no longer wanted are
  deleted after the new manifest is stored. It returns a new `FileRef`; the old one should be dropped
  (`deleteManifest`); any leftover pages are orphans that garbage collection removes. Segments allow, for example, a
  hot head and index of a large file with a cold body.

### 3.2 Keyring, encrypted namespace and pool mounts (implemented)

- **Keyring** (`data.crypto.Keyring`). A passphrase is stretched with Argon2id (default 64 MiB, 3 iterations;
  `noray.keyring.argon2.*`) into a master key; the file stores only salt, parameters and a sealed verifier,
  never the key. Purpose keys are HKDF subkeys (`subKey("journal")`, `subKey("heat")`, ...). A wrong passphrase
  gives `DENIED`. The passphrase comes from `noray.keyring.passphrase.file`, `NORAY_PASSPHRASE` or
  `noray.keyring.passphrase`; if a keyring exists and no passphrase is supplied, **boot fails** instead of
  silently running without encryption.
- **Encrypted journal (v2).** Each record is sealed with ChaCha20-Poly1305 and a random nonce; the AAD holds the
  record's sequence number, so reordering or splicing is detected. The CRC covers the sealed bytes, which tells
  a torn tail (crash, tolerated) from tampering (`DENIED`). A plaintext v1 journal is migrated in place when a
  key is available; a v2 journal without a key refuses to open. Limitation: dropping the *end* of the file
  cannot be detected without an external anchor (the signed version log will provide it).
- **Pool mounts.** A volume with `kind=pool` mounts files that are stored by the `DataEngine`: the inode keeps
  the secret `FileRef` (encrypted in the journal) and the storage class. Pools require a keyring. Policy is
  resolved from the file's directory upwards (`storage policy set DIR NAME`), then the volume's `policy`
  metadata, then `tiered`. Unlinking or overwriting releases the old blobs.
- **Engine nodes.** Volumes become engine nodes when they carry `engine=true` (plus optional `weight`, `speed`,
  `accepts=hot,cold`, `domain`). Such volumes must not be shared with legacy mounts.

### 3.3 Repair, garbage collection and automatic tiering (implemented)

- **Repair** (`DataEngine.repair`, `storage repair`). Audits every blob of a file; damaged chunks are rebuilt:
  replicas are copied from a survivor, erasure-coded shards are recomputed from any `k` others (no key needed),
  and a variant too damaged to rebuild is recreated from another variant (needs the file key). The tree is
  rewritten, so a repair returns a new `FileRef`; manifest page copies are restored in place by id. `NodeWatch`
  distinguishes a blip from a lost node: a node unreachable for longer than `noray.fs.node.grace.minutes`
  (default 30) counts as lost; `--now` skips the wait. `--scrub` reads every blob back and verifies its hash;
  nodes that serve corrupt data are excluded as targets during that repair.
- **Garbage collection** (`storage gc [--dry-run]`). The live set is the union of every pool file's blobs and
  manifest pages. Safety rules: the pass **aborts without deleting anything** if any manifest cannot be read
  (an incomplete live set would destroy data); blobs written within `noray.fs.gc.grace.minutes` (default 60)
  are protected, as are blobs this engine wrote recently; only volumes with `engine=true` are ever collected;
  remote nodes are collected only if this node holds an `operator` certificate for them (§4.2); otherwise they are skipped.
- **Automatic tiering.** An *auto profile* (`PolicyCatalog.AutoProfile(hot, cold, promoteAbovePerDay,
  demoteBelowPerDay, demoteAfterIdle)`, default `auto` = `tiered` ⇄ `archive`, 3.0 / 0.5 per day, 7 days idle)
  moves a file by how much it is used. `AccessTracker` keeps an exponentially decayed access count (half-life
  `noray.fs.tier.halflife.hours`, default 7 days) and the time of the last access; two thresholds give
  hysteresis (no flapping) and a file must have been observed for the idle period before it can be demoted,
  so a freshly started node never demotes everything. Statistics are persisted encrypted (`fs/heat.bin`).
- **Measured speed.** `MeasuredCluster` wraps the nodes and keeps an EWMA of MB/s per node, scaled down by its
  failure rate. After 8 samples it replaces the configured speed in HOT placement and in the order replicas are
  read (fastest first, with randomness so slower copies still serve).
- **Maintenance loop** (`MaintenanceService`, `noray.fs.maintenance.interval.s`, default 0 = off). Each cycle:
  observe nodes → repair → auto-tier → save statistics; GC runs every `noray.fs.maintenance.gc.every`-th cycle
  and only if `noray.fs.maintenance.gc=true`. Everything is also available on demand with the `storage` command
  (`policy`, `class`, `repair`, `gc`, `tier`, `nodes`).

Known limitations of the engine: no deduplication across
files (deliberate: it would leak whether another file contains the same data); remote nodes can delete and list only with an `operator` certificate (§4.2); stronger codecs (zstd/xz) need a dependency, the codec registry
already supports them by id.

### 3.4 Namespace: concurrency, rules and quotas (implemented in `filesystem.vfs.DefaultFileSystemService`)

**Locking.** The service monitor is held only for short metadata commits; no engine or network I/O happens under it.
A write is *prepare* (under the lock: validate path, rules, quota) → *store* (outside: erasure coding, network) →
*commit* (under the lock: check that the file is still the same inode, re-check rules and quota, append the journal
record, swap the handle). The replaced file is deleted after the lock is released. Two writers to the same file
both finish; the last commit wins and the loser's data is dropped. A file unlinked while it is being written is not
resurrected (the write fails with `CONFLICT` and its blobs are dropped). Repair and re-tiering work per file with a
compare-and-set against the handle they started from, so a concurrent write is never overwritten by stale repair
output. Garbage collection is the one operation that needs a quiet moment: operations that write blobs hold the read
side of a `ReentrantReadWriteLock` (many at once), GC takes the write side (it waits for in-flight writes, blocks new
*data* writes while it establishes the live set and deletes, and never blocks reads, `mkdir`, `readdir`, `stat` or
`unlink`). Lock order is always gate, then monitor.

**Journal at boot.** `NamespaceJournal.replay(Consumer)` streams records through a 1 MiB window with positional reads;
the namespace no longer needs the journal in memory (only the live inode table, which is the namespace itself).
`load()` is kept for tools and tests. A one-time plaintext → encrypted migration still holds the records once.
Records store the file size (`SetFile`/`SetData` carry it as a trailing field; older journals decode with "unknown",
counted as 0 in quotas).

**Rules and quotas** (`NamespaceAdmin`, shell `ns`). Attributes on directories (journaled as `SetAttr`, replicated by
the vault, kept by compaction):

| Key | Effect |
|---|---|
| `quota.bytes N`, `quota.files N` | the whole subtree may hold at most N bytes / files; cumulative (every ancestor with a quota must have room); usage is kept as a running counter and rebuilt at boot |
| `max.file.bytes N` | no single file larger than N |
| `readonly true` | nothing can be created, written or deleted below |
| `deny.delete true` | nothing can be deleted below |
| `deny.overwrite true` | a file that already has content cannot be replaced (write-once) |

`readonly`, `deny.*` and `max.file.bytes` are inherited and the nearest definition wins (a subdirectory may set
`readonly false`). Violations are `READ_ONLY` or `QUOTA_EXCEEDED`. Lowering a quota below current usage never touches data;
it only blocks growth. `deny.delete` + `deny.overwrite` gives an evidence directory that can only receive new files.
Maintenance (repair, re-tiering, GC) is not subject to these rules. Limits: quotas count logical bytes, not stored bytes
(erasure coding overhead is not charged; `ns usage` shows an estimate of the stored bytes); usage is exact only while the journal is intact (a crash between a data write
and its journal record can leave a counter off until the next restart rebuilds it); the rules bind everyone using the
filesystem service, including the operator's own shell, so an operator must clear a rule to change protected data.

**Finer grants for modules.** Beside `fs.read` / `fs.write` there are `fs.create:/p` (create entries and fill each file
once; no overwrite, delete or read: a drop box) and `fs.delete:/p` (delete only). `fs.write` implies all of them.

## 4. Naming and consistency (decision)

No global consensus: it does not fit thousands of hostile nodes.

- Data is immutable, so replicas of a blob can never conflict.
- Names are mutable and are modelled as an **append-only, signed version log** per namespace (like Git
  refs). Each namespace has an owner key; each entry is signed and points to a root manifest and to the
  previous entry. Single writer per key means no conflicts by construction.
- Multiple writers: divergent logs are surfaced as explicit *forks* that a policy (or CRDT, later)
  resolves. The core never silently picks a winner.
- Replication quorums (W/R) apply to blobs; the version log is replicated by gossip/anti-entropy.

### Local namespace journal (implemented)

Until the signed log exists, the local namespace (inodes, directories, file → blob mapping) is made
durable with a **write-ahead journal** (`NamespaceJournal`). It is deliberately shaped like the future
log: append-only, framed, checksummed, replayable, compactable. The journal is a storage SPI so an
encrypting or signed implementation can replace the plain file one without touching the filesystem
service.

### 4.1 Persistent configuration (implemented)

- **Volumes.** `volume add kind=local path=...` (or `FsConfigService.addVolume`) attaches a volume and records
  its definition in `<noray.data.dir>/fs/volumes.properties` (atomic write, owner-only). Volumes attached any
  other way (hot-plug, memory) are deliberately not persisted.
- **Mounts.** Every mount/unmount is persisted automatically, but only for volumes that have a persistent
  definition. If the configuration cannot be written, the mount is rolled back.
- **Boot.** `FsConfigService.restore()` re-attaches the defined volumes and re-creates the mounts. A volume that
  cannot attach (disk not plugged in) does not stop the others and stays defined. A corrupt configuration file
  stops the boot instead of being overwritten.
- **Namespace.** The journal (`fs/namespace.journal`) only records paths on volumes whose data survives a restart
  (`LOCAL`, `REMOTE`), so volatile volumes never leave ghost files behind. Paths of a volume that is temporarily
  absent are kept.
- Shutdown never edits the stored configuration.

### 4.2 Identity, admission and compromise containment (implemented in `com.redactado.noray.identity`)

**Design rule: no node holds anything that lets it hurt another node.** Every mechanism below follows from it.

| What a node has | Consequence if that node is taken over |
|---|---|
| Its own Ed25519 signing key, generated on the node, sealed with the keyring, never copied elsewhere | Only that node's identity is lost. There is no cluster-wide secret to steal (no shared key, no shared password). |
| A certificate signed by the *authority* | It can only do what the certificate lists, until it expires or is revoked. It cannot extend, edit or reissue it (signature), nor present it from another node (it names the node id). |
| Ciphertext blobs addressed by their hash | Nothing readable: file keys never leave the node that owns the namespace, and with erasure coding it does not even hold whole files. Blob ids are 256 bits and `LIST` is a separate right, so it cannot discover what other nodes hold. |
| The authority's **public** keys | Nothing: public keys only verify. Authority private keys are not on cluster nodes (they stay offline, ideally split between people). |

- **Authority = keys + threshold** (`TrustRoot`, `noray.identity.authority.keys`, `...threshold`). Certificates and
  revocation lists need endorsements from `threshold` *distinct* authority keys (m-of-n), so one stolen or coerced
  authority key can neither admit, promote nor revive a node. Endorsements can be gathered offline
  (`identity issue` then `identity sign`).
- **Certificate** (`Certificate`): node id, signing key, transport keys (X25519 + ML-KEM, so the same object
  enrols the peer on the transport), the *operations* it may perform, validity window and a serial. Short validity
  (default 30 days) is the main tool: a node that was compromised without anyone noticing loses its access when its
  certificate runs out, with no action needed. Renewals are installed with `identity install` and spread by the mesh.
- **Rights are per operation** (`Op`): `PUT`, `GET`, `HAS`, `DELETE`, `LIST`. Presets: `reader`; `peer` (store, read,
  check: what replication and repair need); `operator` (also delete and enumerate: garbage collection). A storage node
  or ordinary client has `peer`, so it can never wipe or enumerate another node. Keep `operator` for as few nodes as
  possible.
- **Enforcement is on the serving side, per request** (`BlobResponder` + `PeerDirectory`): expiry and revocation are
  re-checked on every request, so they take effect without any message from the compromised node. The transport
  authenticates *who* is talking; the certificate says *what* they may do; a certificate shown by another node is
  rejected because it names a different node id. A refusal is `DENIED` and is never confused with "blob absent"
  (`RemoteObjectStore.has` rethrows it). Oversized blobs are refused (`noray.fs.blob.max.mib`, default 256).
- **Revocation** (`RevocationList`): authority-signed, versioned, *cumulative* (a newer list must keep every earlier
  entry, so nobody can un-revoke) and persisted (a tampered file stops the boot). It names, per node, the lowest
  certificate serial still acceptable; a node revoked after a compromise is readmitted only with a freshly issued,
  higher-serial certificate. Lists spread by gossip: tell one node and the rest learn it; peers that were away get
  it at the next introduction. Revocation also closes the transport door (`TransportEnroller`).
- **Admission** is one signed object: `identity request` on the new node, `identity issue` on the authority machine,
  `identity install` on the node, `identity enroll CERT` on its peers (keys, expiry and rights come with the
  certificate; nothing is typed by hand). A node that asks before it is known is refused once and introduced, so
  the retry works.
- **Housekeeping** (`IdentityMaintenance`, every 60 s): forgets expired and revoked certificates, re-introduces the
  node periodically, and warns for a week before this node's own certificate expires (an expired certificate
  silently isolates the node).
- **Delayed deletion** (`DeleteQuarantine`, `noray.fs.blob.delete.delay.h`, default 24, 0 = immediate). A `DELETE` from a
  peer hides the blob (not served, not counted, not listed) and removes it for good only after the delay; the node's
  owner can bring everything back with `storage quarantine restore --all` until then. Storing the same content again
  revives a blob. The cost is that space freed by garbage collection is reclaimed after the delay, not at once. Deletes
  made by a node on its own store are not delayed.
- **Signed, hash-chained logs** (`identity.log.SignedLog`): the shape of namespace version history. One owner, writers
  admitted and removed by the owner, every entry signed and chained to the previous one by hash. A holder of the log can
  withhold entries but cannot forge, edit, reorder or cut one out of the middle. Rollback is exposed by a `HeadPin`
  (sequence + hash of the newest entry seen) and forks by comparing copies (`findFork`, `adopt`). It is not yet the
  namespace's storage; it is the primitive the replicated namespace will be built on.
- **Journal anchor.** The encrypted namespace journal keeps a sealed side file with the record count and the hash of
  the newest record. A journal with fewer records (a cut tail), or a different record at the anchored position (replaced
  by another history), is refused; a torn final write from a crash is not mistaken for an attack. It lags by at most 64
  appends unless `noray.fs.namespace.sync` is on (the default). Someone who can restore both the old journal and the old anchor still
  wins locally; that is what witnesses are for (next item).
- **Witnesses** (`identity.witness.WitnessService`, channel `WITNESS`). Every `noray.witness.interval.s` (300) the node
  appends a *checkpoint* (journal record count + newest-record hash) to its own `SignedLog` and sends new entries to up to
  `noray.witness.max` (5) certified peers that hold the right to store. Each cycle a witness answers with the newest entry
  it holds; that entry is signed by this node, so a witness cannot invent evidence. If the data directory is restored from
  an older copy (journal, anchor and checkpoint log together) the witnesses hold entries the node no longer has:
  `LOG_ROLLED_BACK` (with how many records were recorded), or `LOG_FORKED` when the node continued the old copy differently
  (the witness also records `PEER_FORKED` and keeps the first history). A journal that is shorter than, or different from,
  the newest local checkpoint is caught at start (`JOURNAL_ROLLED_BACK`, `JOURNAL_REWRITTEN`) even without witnesses.
  Compaction writes a `COMPACTING` marker first, so a crash during a rewrite is not read as loss. Witnesses accept logs only
  from peers with a valid certificate and PUT right, whose key owns the log; they store at most 256 owners and 100 000
  entries each; logs are sealed with a keyring key; a damaged or foreign own log stops the node instead of starting a new
  history. The checkpoint log rotates after 20 000 entries into a new log whose genesis names the hash of the old head
  (the old one is kept as `own.log.prev`): witnesses that hold the whole old log follow the chain, and ones that were away
  are brought up to date first, without a false alarm. A witness learns counts and hashes only. Alarms are evidence, not verdicts (restoring a backup on purpose raises
  one): `witness status|alarms|run|clear`, and `ERROR` log lines.

What this does **not** protect against, stated plainly:

- A compromised **operator** node can delete and enumerate on every node it has a certificate for. Mitigations: few
  operators, short certificates, revocation, and **delayed deletion** (below), which turns a wipe into something the
  node owners can undo.
- A compromised node that owns a namespace and has its keyring unlocked can read what that namespace can read. Use
  separate pools/keyrings per sensitivity level; storage-only nodes hold no file keys at all.
- A compromised **authority machine** with enough keys (the threshold) can admit anyone. Keep authority keys offline and
  split them across people (`threshold` > 1).
- Clock skew is tolerated up to `noray.identity.skew.s` (default 5 minutes); a node whose clock can be set by an attacker
  can stretch or shorten certificate validity as seen by itself only.
- A compromised peer can still fill another node's disk with allowed `PUT`s, only more slowly: an optional per-peer
  write budget (`noray.fs.blob.peer.rate.mib.per.min`, default off) bounds the speed and buys time to revoke; a total
  per-peer quota (`noray.fs.blob.peer.quota.mib`, default off, `storage quota`) bounds how much a peer can ever
  occupy: a blob is charged to the peer that stored it first, storing existing content is free, and the charge ends
  when the blob is really removed (peer delete, quarantine sweep, or reconciliation after local GC). The ledger is
  sealed with a keyring key and written lazily (a crash can under-count recent charges).

Configuration: `noray.identity.authority.keys` (hex, comma separated; **unset = unmanaged**, every enrolled peer may do
everything and a warning is logged), `noray.identity.authority.threshold`, `noray.identity.skew.s`,
`noray.identity.maintenance.interval.s`, `noray.fs.blob.max.mib`, `noray.fs.blob.delete.delay.h`, `noray.fs.blob.peer.rate.mib.per.min`, `noray.fs.blob.peer.quota.mib`, `noray.witness.enabled|max|interval.s`. Shell: `identity show|request|install|enroll|peers|apply`
on nodes; `identity authority new|pub`, `identity issue`, `identity sign`, `identity revoke` on the authority machine.

### 4.2b Large messages over UDP (implemented in `net.overlay.Fragments`)

Up to 1100 bytes a message is one datagram, as before (no acknowledgement, the caller's request timeout covers a loss). Larger
messages (the data engine's chunks are 4 MiB) are cut into 1100-byte fragments inside the encrypted frames, acknowledged
selectively (`base` plus a 256-fragment bitmap, sent every 8 fragments and at once on gaps, duplicates and the last
fragment), and retransmitted: sliding window that grows with every acknowledged fragment (16 to 256) and halves on a
timeout, retransmission timeout from the measured round trip (Karn's rule, 60 ms to 3 s, doubling on loss).
`sendPlain` returns only when the peer has everything, and fails with `VOLUME_UNAVAILABLE` after
`noray.net.send.timeout.s` (30). The receiver keeps at most 8 messages in assembly per peer, drops abandoned ones after
2 minutes, refuses announced sizes above `noray.net.max.message.mib` (16), re-acknowledges messages it already delivered
and never delivers one twice. Completed messages are handed to handlers on their own virtual thread, so a handler can send
a large reply and wait for acknowledgements without blocking the receive loop; the price is that handlers for different
messages from one peer may run concurrently and out of order (the blob protocol correlates by request id). Limits: no
congestion control beyond the window above (it is not TCP-fair), no per-peer bandwidth cap, a message must fit in memory.
Tested with a relay that drops a third of the packets, drops acknowledgements, duplicates everything, and with two complete
nodes (`RealNodesTest`: real sockets and handshake, a 4 MiB blob stored on and read back from the other node).

### 4.2c Peers that restart, nodes that vanish (implemented in `UdpOverlay`, `VolumeHealthMonitor`)

A session lives in memory, so a restarted peer has none. Three mechanisms keep the cluster working through that:
- **Replacement handshakes.** A handshake that arrives for a peer that already has a session creates a *pending* session;
  it replaces the old one only when a packet that decrypts with it arrives, so a replayed handshake changes nothing
  (the WireGuard rule). Handshakes from one peer closer than 200 ms are ignored.
- **Re-handshake by silence.** Before a send, if we sent something and nothing came back for 1.5 s, or the session heard
  nothing for 15 s, the sender runs a fresh handshake (1.5 s patience, at most one per 3 s); a fragmented send that gets
  not a single packet back drops the session so that the next one starts over. Glare (both ends initiating at once) is not
  resolved: one of the two attempts times out and is retried by the next send.
- **Volumes that were not there at boot.** `FsConfigService.retryPending()` runs at the start of every health round: it
  attaches the defined volumes that failed to attach (a disk plugged in later, a peer that was not enrolled yet) and keeps
  mounts that were waiting for them in the stored configuration (they used to be dropped by the next unrelated change).
- **Volume health.** `VolumeHealthMonitor` probes every volume each `noray.fs.health.interval.s` (10; 0 disables) in
  parallel: a remote volume is probed with a tiny `HAS` (`RemoteObjectStore.alive`, two tries; an error reply counts as
  alive). Silent volumes go `OFFLINE`, so the data engine skips them at once instead of waiting 30 s per request, and
  come back `ONLINE` when they answer again. Failed-write cleanup (`discard`) skips unavailable nodes: their copies are
  orphans for GC.

A node's blob store is now durable by default: `<noray.data.dir>/fs/blobs` (`noray.fs.local.root` overrides it,
`memory` selects the old in-memory store). Before, whatever peers stored on a node vanished with its process.

`RealClusterTest` runs the whole thing: a client and 9 or 10 complete kernels on real UDP; a 3 MB file in a `pool` whose
volumes are the other nodes' disks; reading with nodes gone; `repair`; restart of every storage node and of the client
(namespace, keys and peers come back from disk); health monitor offline/online.

### 4.2d Keys: authority ceremony, mesh-key rotation, revoked peers (implemented)

- **Authority keys.** `identity authority new FILE` creates an Ed25519 key and, by default, stores it encrypted
  (`AuthorityKeyFile`: Argon2id of a passphrase from `--passphrase-file` or `$NORAY_AUTHORITY_PASSPHRASE`, then AEAD);
  a plain 0600 file needs an explicit `--no-passphrase`. Every command that reads a key (`issue`, `sign`, `revoke`,
  `authority pub`) takes the same passphrase source; a wrong or missing one is refused. Plain hex files from earlier versions still
  read. `identity authority config PUB... --threshold N` prints the two settings that make a set of public keys a
  mesh's trust (and warns when losing one key would block every future admission). The ceremony is: each custodian runs
  `authority new` on an offline machine, sends only the public key; whoever assembles the mesh runs `authority config`.
  Threshold signing was already `issue` by the first custodian and `sign` by each further one.
- **Mesh-key (PSK) rotation without a flag day.** Set `noray.net.mesh.psk=<new>` and `noray.net.mesh.psk.previous=<old>` on a
  node: it speaks to peers on either key. Message 1 of the handshake carries, encrypted, one byte derived from the key the
  initiator uses, so a responder that knows two picks the right one; an initiator that gets no answer (or a rejected one)
  retries with the other key. With obfuscation each datagram is tried under both keys (it must then read as one of ours)
  and replies use the key the peer used. Procedure: put `previous` on every node (restart each), switch `psk` to the new
  value node by node, finally remove `previous`. A peer that holds neither key is still refused. The old key is only
  as secret as it was: rotate after a leak, and finish quickly, because while `previous` is set an attacker with the old
  key can still open sessions (they still need an enrolled static key, so this is a weakening of the outer layer only).
- **Revoked peers.** When a revocation list names a node, `IdentityService.onRevoked` listeners run: the witness service
  deletes that node's checkpoint log (`witness.logs.dropped`) and the namespace vault its copy; the transport door was
  closed already.

### 4.2e Namespace replication — the vault (implemented in `filesystem.vfs.journal.NamespaceVault`)

The namespace (paths, file references with their keys, policies) was the one piece of state living on a single node. With
a passphrase and the managed identity, the node now seals a snapshot of it (`NamespaceRecordCodec`, AEAD under
`keyring.subKey("namespace-vault")`) and sends it on mesh channel `NAMESPACE` (6) to up to `noray.vault.copies` (3)
certified peers whenever the journal changed (checked every `noray.vault.interval.s`, 60). A holder keeps one copy per
certified owner (`vault-<owner>.blob`, newer version wins, size cap 8 MiB) and returns it to that owner on request.
Holders cannot read it; at worst they withhold it or return an older copy of the owner's. `vault restore` (after losing the
data directory: same passphrase, node back in the mesh) asks every peer, takes the newest copy that opens and rewrites the
journal; restart the node to load it. `vault status|push` show/force replication.

Limits: a copy is as fresh as the last push (changes in the last minute can be lost); the snapshot is shipped whole (a
namespace over 8 MiB sealed is not replicated and a warning is logged); the *live* namespace is still a single node's
global tree under one lock (§9). The vault protects against losing the node, not against the node being wrong.

### 4.2f Operations (implemented)

- **Durability.** `LocalDirObjectStore` now `fsync`s the blob and its directory on put and delete; `external` (hot-plug) volumes
  use it too instead of memory.
- **Quotas.** `noray.fs.blob.peer.quota.mib` is the default per-peer allowance on a storage node and
  `noray.fs.blob.peer.quota.overrides` (`<node id>:<MiB>,...`) sets exceptions (state is persisted and sealed).
  `noray.net.peer.kib.s` caps what a node sends to any single peer (token bucket in `UdpOverlay`, `net.throttled` counts waits).
  Per-file or per-directory quotas are not implemented.
- **Failure detection.** A handshake to a silent peer gives up after 3 s (was 5) and the health probe does not retry a handshake
  that already failed; a round of `volume` probes with several dead nodes takes about 7 s (parallel), after which the data engine
  skips them. Requests already in flight to a node that just died still wait for the 30 s request timeout.
- **Metrics and logs.** `kernel.metrics.Metrics` (counters/gauges for packets, handshakes, rationing, throttling, relay, blob
  requests and rejections, repairs, probes, volumes offline, vault and witness events). `metrics` shell command;
  `noray.metrics.port` serves Prometheus text on loopback only; `journal --json` prints structured output.
- **Daemon.** `NorayConfig` merges, lowest first, built-in defaults, the properties file (`--config`, `$NORAY_CONFIG`, `./noray.properties`,
  `/etc/noray/noray.properties`), `-Dnoray.*` system properties and `--set key=value`; secrets belong in `noray.keyring.passphrase.file` or `$NORAY_PASSPHRASE`; `--daemon` runs without a shell and stops cleanly on SIGTERM; `./gradlew installDist`
  produces a distribution with `dist/noray.service` (systemd, hardened) and `dist/noray.properties.example`.
  See `docs/OPERATIONS.md`.

### 4.2g Cryptographic review (done internally; see `docs/SECURITY_REVIEW.md`)

The transport crypto was reviewed against the code and tested against independent references. This is a **self-review, not
an external audit**: it found and fixed real weaknesses (handshake replay, transcript binding, no session lifetime, keys at
rest in clear) but cannot replace someone independent. Details, the findings and the tests that pin them are in
`docs/SECURITY_REVIEW.md`.

### 4.3 Traffic shaping (implemented in `net.overlay.TrafficShape`)

An observer of encrypted packets still sees their length and timing. Inside the AEAD every message is framed as
`channel payload zero-padding padLength(2)`; `noray.net.pad.bucket` (default 256) rounds it up to a multiple of that many
bytes (0 disables), so a 40-byte request and a 90-byte answer are indistinguishable. Messages that would pass 1400 bytes are
left alone (no fragmentation risk), so large transfers still show their size. `noray.net.cover.interval.ms` (default 0, off)
sends dummy packets to every connected peer at exponentially distributed gaps with that mean, one to three buckets long;
they are authenticated, indistinguishable from real packets on the wire, and dropped by the receiver (channel `0xFF`).
Cover costs bandwidth in proportion to the rate: it is the operator's trade-off.

**The header names no node.** It is `version(1) type(1) index(4) nonce(8)` (14 bytes, it used to carry the sender's 32-byte
node id). `index` is a random session index chosen by the *receiver* (WireGuard's receiver index): message 1 carries the
initiator's, message 2 echoes it and starts its body with the responder's, and every transport packet carries the
index its receiver chose. The initiator's identity travels only inside message 1, encrypted to the responder's static key
(Noise IK); the responder learns the static key from it and looks the node up by that key. Consequences: a handshake from a
key that is not enrolled gets no answer; the work to find out (two DH and one ML-KEM decapsulation) is done on a virtual
thread and rationed by a token bucket (100 a second, burst 200); a session replaced by a rekey stays readable under its old
index until the next replacement, and indices differ per session, so packets of two sessions between the same peers cannot be
linked by header.

**Obfuscation** (`noray.net.obfuscate=true`, all peers must agree): every datagram becomes `salt(12) ChaCha20(key, salt,
datagram)` with a key derived (HKDF) from the mesh pre-shared key, so no version or type byte, no fixed offset is left for a
filter to match; the test checks that none of 50 captured datagrams starts like ours. It is a fingerprint remover, not a
security layer (the sessions underneath are): it gives nothing against someone who holds the key, and a deployment that
needs more than "does not look like this protocol" (looking like QUIC or TLS) needs a different driver.

**Relays** (`noray.net.relay.enabled` on the relay; `noray.net.relay.peers` on nodes that depend on one): when A cannot
reach B (NAT, a firewall, or simply wanting neither to learn the other's IP address) the enrolled hint of B at A reads
`relay:<relay node id>` (a comma-separated list is tried in order, for example `203.0.113.4:7000,relay:<id>`). A's datagram
for B goes inside A's session with the relay as a `RELAY` frame (`target id, datagram`); the relay passes it to B inside
its own session as `RELAYED` (`source id, datagram`); B handles it like a datagram from the network and answers the same
way. The datagram stays end-to-end protected between A and B (the relay sees node ids, sizes and timing, not content, and
cannot forge or alter anything undetected); the relay only forwards between peers it has a session with, never to or from
itself, and limits every source to `noray.net.relay.kib.s` (2048) KiB/s. A node that is only reachable through a relay keeps
a session open to it and sends a tiny frame every `noray.net.relay.keepalive.s` (15) seconds so that a NAT keeps the mapping.
Fragments are 1000 bytes so that a relayed datagram still fits in one packet. Tested with three overlays on loopback: A and
B know only the relay's address; 300 kB one way, 120 kB the other, a relay that is not enabled carries nothing, and a relay
carries nothing for a peer it does not know.

What it does not hide: the source and destination IP addresses and ports of a direct path (use a relay), the handshake sizes
(a handshake is recognisably big), real traffic bursts when cover is sparse, what a relay sees (ids, sizes, timing), and a
global observer correlating both ends. Chaining relays (onion-style) and relay discovery are not done: relays are chosen
by the operator.

### 4.4 Onion routing (implemented in `net.onion`)

Anonymous, best-effort message delivery over relays. Three plug-in points keep the core independent of the network:
`OnionDirectory` (who can be a hop or destination and their onion key), `PathSelector` (which relays to use; default
uniformly random) and `OnionTransport` (how a fixed-size cell reaches a neighbour). The mesh binding
(`MeshOnionTransport`) carries cells on `MeshChannel.ONION` and learns keys from announcements that the mesh already
authenticates; relays are the enrolled peers that are up and announced a key. `OnionService` is the facade
(`send(destination, bytes)`, `subscribe`, `sendCover`, `stats`). Enable with `noray.net.onion.enabled=true`.

Format (`OnionPacket`): every cell is 1314 bytes at every hop. A cell is `ephemeral(32) | lenEnc(2) | body(1280)`; the
body holds one ChaCha20-Poly1305 layer `cmd | expiry | next | nextEphemeral | nextLenEnc | inner` for the first hop,
followed by random padding. Each hop has its own sender-chosen ephemeral X25519 key, the layer length is masked per hop, and
a relay re-pads with fresh random bytes, so cells at consecutive hops share nothing a link observer can match (tested).
Relays check an expiry (default 2 min), keep a bounded replay filter of ephemeral keys (fails closed when full), and hold
each cell a random time up to `noray.net.onion.delay.ms` (default 50). Messages up to 16 KiB are split into cells and
reassembled (bounded: 256 partial messages, chunk-count and byte caps, 60 s). Up to 5 relays; default 2, at least 1 (no relay
would mean the destination learns the sender; the service refuses).

What it gives: a relay or the destination cannot link a message to its sender; the first relay knows the sender talks to
*someone*, the last relay knows the destination; layered keys are fresh at every start (`OnionIdentity`).
What it does not: reliability (cells can be lost; nothing is retransmitted), replies (no reply blocks: put a signature and
a return route inside the message if you need them), protection against an observer who sees both ends and correlates
timing and volume (`sendCover` and the delay help, neither is a mixnet), relays beyond direct peers (the directory assumes
a meshed set), and post-quantum secrecy of the layers themselves (X25519; the PQ hybrid link below them protects what an
observer of links can record). It is a building block for module protocols, not a general anonymous transport.

## 5. Modules and isolation (implemented in `com.redactado.noray.kernel.capability`)

Two kinds of module:

- **Trusted** (`registerModule`): the core distribution. Receives the real `KernelContext`.
- **Sandboxed** (`registerSandboxedModule`): everything else (repositories, hosting, agents, scrapers…).
  It receives a `RestrictedKernelContext` and can do **nothing** unless the operator grants it, in
  `noray.module.<id>.grants` (comma separated). A module may state what it needs
  (`requestedCapabilities()`); that is only a request, and unmet requests are logged at boot.

Grants:

| Grant | Gives |
|---|---|
| `service:Name` / `service:*` | use that registered service (by simple class name) |
| `service.register:Name` | register a *new* service; replacing or unregistering an existing one is never allowed |
| `fs.read:/p`, `fs.write:/p`, `fs.create:/p`, `fs.delete:/p` | a `FileSystemService` **limited to that subtree**; `fs.write` implies read, create and delete; `fs.create` creates entries and fills each file once (no read, overwrite or delete), `fs.delete` only deletes; other paths get `DENIED`, not "not found" |
| `config.read:key*`, `config.write:key*` | configuration keys (trailing `*` = prefix); `getAllKeys` shows only readable ones |
| `event.publish:Type`, `event.subscribe:Type` | events by class name |
| `executor` | the shared worker pool, minus `shutdown`, and with a **share**: `noray.module.<id>.max.tasks` (default 8) tasks run at once and `noray.module.<id>.max.queued` (256) wait; further submissions get `RejectedExecutionException` |
| `kernel.modules` | names of loaded modules (never the module objects) |
| `web.app` | mount an authenticated web app under `/apps/<module id>/` (docs/WEB_UI_DESIGN.md) |
| `web.site:/p` | mount a public site under `/p` (reachable without login; sandboxed into an opaque origin) |

Rules that keep this honest:

- **Wildcards never cover what is sensitive.** `service:*` excludes `Keyring`, `StaticKeys`, `IdentityService`,
  `DataEngine`, `DataCluster`, `ObjectStore`, `BlobResponder`, `VolumeManager`, `StorageAdmin`, `NamespaceAdmin`, `FsConfigService`,
  `VolumeHealthMonitor`, `NamespaceVault`, `MeshTransport`, `KernelContext`, `CapabilityRegistry`, `MeshOnionTransport`, `OnionService`, `MaintenanceService`; `config.read:*` excludes keys containing passphrase/password/secret/psk/private/token/seed/authority.
  Those need an exact grant, which makes them visible in review.
- Services are narrowed through `ServiceAttenuator`s (the filesystem registers its scoped view), so the capability
  is a real narrower object, not a check the module could skip.
- Deny by default; refusals throw `CapabilityDeniedException` (or `FsException(DENIED)`) and are recorded in the
  bounded `CapabilityRegistry` log (`capability audit`). Whether a service exists is not revealed without a grant.
- `registerResource` always files resources under the caller's own module id; invalid grants fail only that module's boot.
- `capability list|show|audit|check` inspects and validates grants.

Limits: the task quota bounds concurrency and queue length only, not CPU time, memory or a task that never returns. In-JVM
sandboxed modules are a *capability discipline inside one JVM*, not a security boundary against hostile bytecode (such code
can use reflection or the JDK directly). They are for code the operator has reviewed. For code that is **not** trusted
there is the process sandbox below.

### 5.1 Process sandbox for untrusted code (implemented in `com.redactado.noray.sandbox`, Linux)

`Sandbox.run(SandboxSpec, FileSystemService)` runs a `SandboxedProgram` in a child JVM started through bubblewrap:

- user, pid, network, ipc, uts and cgroup namespaces: no network (not even loopback), sees no other process, no capabilities
  (`CapEff`/`CapBnd` zero, `NoNewPrivs` set), and cannot create further user namespaces;
- an empty root: only `/usr`, the JDK and the program's classes, read-only; no home, no data directory, no keys, cleared
  environment; the only writable place is a private in-memory `/tmp` of limited size;
- a seccomp filter (x86-64 and aarch64; deny list: mount, ptrace, bpf, perf, io_uring, keyrings, namespaces, module loading, clocks...;
  wrong architecture kills the process);
- limits: heap, address space, CPU seconds, file size, open files, wall time; a systemd scope with a hard memory and task cap
  when `systemd-run --user` works; total bytes through the bridge;
- the only door is a narrow request/response bridge (`mkdir`, `createFile`, `unlink`, `stat`, `readdir`, `readFully`,
  `writeFully`, `hasContent`, frames ≤ 32 MiB) to a `FileSystemService` chosen by the caller, normally a `ScopedFileSystemService`:
  the grants, the namespace quotas and rules all apply to it (tested: out-of-scope paths are `DENIED`, a directory quota stops
  the program).

**It fails closed:** if the system cannot provide this (no bubblewrap, user namespaces disabled, not Linux),
`Sandbox.detect()` throws and nothing runs unconfined. Shell: `sandbox status`, `sandbox run [--read P] [--write P] ... JAR CLASS`.
Tests run real programs in the real sandbox and check, from inside, what the kernel reports about them; they are skipped
where bubblewrap cannot run.

What it is not: a defence against kernel vulnerabilities reachable through the allowed system calls; available on other
operating systems; a way to run *existing* modules out of process (a program is written against `SandboxedProgram` and only
sees the filesystem; giving it other services would need more bridge operations, each one a decision); a guarantee about CPU
side channels. The seccomp filter is a deny list, a smaller surface rather than a proof. Treat it as a strong first barrier,
not as the only one.

### 5.2 Packages (implemented in `com.redactado.noray.pkg`)

Third-party code arrives as a signed package and runs only with grants the operator approved for that exact version.
There is no registry, no dependency solving, no install script and no auto-update.

- **Format.** A `.nrpkg` file is `"NRPKG1\n" | u32 len | manifest | u64 len | payload` and nothing else (no archive format
  to confuse). The manifest is a rigid UTF-8 text (`PackageManifest`): the magic line, a fixed set of keys (`id`, `version`,
  `noray.api`, `sandbox=process|jvm`, `main`, `payload.sha256`, `payload.size`, `publisher`, optional `requests`, `depends`,
  `description`), no duplicates, no unknown keys, no blank lines, no control or bidi-formatting characters, LF only, then one
  `signature=<key>:<sig>` line per signer. Each signature (Ed25519, domain `noray.package.v1`) covers every byte before the
  first signature line, so nothing can be added or changed after signing. A seeded fuzz test checks the parser only ever
  fails with `MALFORMED`.
- **Trust.** Publishers are pinned by the operator (`pkg publisher add NAME KEY[,KEY...] --threshold K [--trusted]`): k-of-n
  keys, and `trusted` is required for `sandbox=jvm`. A package from an unpinned publisher, with too few valid signatures, built
  for an unsupported `noray.api`, or whose payload hash/size differs, is refused. A process package may only request `fs.*`
  grants (the bridge can give nothing else).
- **Lifecycle.** `install` verifies and stores under `<data>/packages/store/<id>/<version>/` and runs nothing. Versions are
  immutable (same version, different content: `CONFLICT`), lower versions are refused unless `--allow-downgrade`. `approve ID
  VERSION [--grants G]` records the grants (each must be covered by a request: narrowing only) and activates the version;
  `rollback` returns to the previously active one; `revoke`, `deactivate` and `remove` undo. Exact dependencies
  (`depends=id@sha256`) must be installed and approved. `pkg fetch PEER SHA256` gets a package from one of one's own nodes over
  the `PACKAGES` mesh channel and installs it through the same verification; it never approves anything.
- **Loading.** Every load re-verifies signature and payload against the publishers pinned *now* (unpinning a publisher stops its
  packages at the next load). JVM packages are registered as sandboxed modules before boot (`PackageLoader`), and on a
  running node they are loaded, reloaded or unloaded right after approve/activate/rollback/deactivate
  (`NorayKernel.loadSandboxedModuleLive` / `unloadModule`; core modules cannot be unloaded). They are loaded from a
  private copy of the verified bytes, must report `id()` equal to the package id, and get exactly the approved grants (a
  `noray.module.<id>.grants` line cannot widen them). Process packages run on demand (`pkg run`) through `Sandbox.run` with a
  `ScopedFileSystemService` built from the approval. A package that fails any check is skipped and recorded; boot continues.
- **Integrity.** With a keyring, approvals and pinned publishers are sealed with an HMAC (keyring subkey `packages`): a file
  edited outside Noray, or a missing seal, stops every package from loading until an operator reviews `pkg list` and runs
  `pkg reseal`. The keyring is opened once by the bootstrap, before packages load, and shared with the modules.
- **Audit.** Every action and refusal is appended to `<data>/packages/audit.log`, each line carrying the hash of the previous one
  (`pkg audit --verify`). Tamper-evident, not tamper-proof.

Limits: the store lives in the local data directory, not in the pool (packages load before the mesh is up); release signing
keys are the publisher's responsibility; in-JVM packages share the JVM, so `trusted` means "reviewed", as for any sandboxed
module (§5).

## 6. Threat model → mitigations

| Threat | Mitigation | Residual risk |
|---|---|---|
| Compromised node | Nodes store only ciphertext; keys never leave clients; erasure coding so no node has a whole file; per-node keys and per-operation, expiring, revocable certificates so one node cannot act for or against another (§4.2) | Metadata about which node stores which blob id; a compromised *operator* node can delete (see §4.2 limits) |
| Physical access / seizure | Encryption at rest: file data and the namespace journal are sealed under keys derived from a passphrase (Argon2id) that is not stored; wrong passphrase refuses to boot; panic wipe / hidden volumes still to do | Unlocked running node (keys in RAM); a restored backup is detected only when a witness is reachable (or later, once one is) |
| MITM | Noise IK + ML-KEM-768 hybrid (post-quantum), enrolled static keys, no all-zero PSK | Endpoint compromise |
| Raids / legal pressure | Operators cannot read content, no default plaintext logs, plausible deniability | Operator identity if network metadata leaks |
| Traffic analysis | Packet padding to size buckets, random cover packets, anonymous header (random session indices), optional obfuscation of whole datagrams, relays (§4.3) | Timing and volume; a global passive observer; onion messages (§4.4) are best-effort and not a mixnet; no mimicry of a real protocol; a plain relay sees who talks to whom |
| Replay / forgery | Per-session sliding replay window, AEAD, signed hash-chained logs, certificates bound to node id, threshold-endorsed revocation lists | Logs are not yet the namespace's storage; only checkpoints of it are witnessed |

## 7. Operating principle

The system is meant to be self-hosted by anyone, under their own rules and responsibility. The core
therefore makes no assumptions about content and contains no content-aware logic; operator
responsibility for hosted content is a deployment decision, not a core feature.

## 8. Roadmap (foundations first)

1. ~~Architecture document~~ (this file).
2. ~~**Durable namespace and metadata**~~ — namespace journal, persistent volume definitions and mounts
   (`<noray.data.dir>/fs/`), durable `local` volume driver, `volume` shell command. See §4.1.
3. ~~**Data model**~~ — chunking, compression tiers, client-side AEAD, erasure coding, weighted placement,
   encrypted Merkle manifests, per-data storage classes with re-tiering, parallel I/O (§3.1), and the
   integration with `FileSystemService`: keyring, encrypted journal, pool mounts (§3.2).
4. **Identities, keys, signed version log** — ~~node keys, threshold authority, certificates with per-operation rights,
   revocation with gossip, admission by certificate, signed hash-chained log, journal anchor, encrypted authority keys and
   ceremony helper, mesh-key rotation, namespace replication by sealed snapshots~~ (§4.2, §4.2d, §4.2e). Remaining: the
   signed log as the namespace's live storage (the namespace is replicated as snapshots, not as an operation log).
5. ~~**Capabilities for modules**~~ (restricted `KernelContext`, scoped filesystem view, grant audit; §5). Per-module task quotas exist, and untrusted code runs in a process sandbox with CPU/memory limits (§5.1). Remaining: running existing modules out of process.
6. **Repair and lifecycle** — ~~repair after node loss, orphan-blob GC, access-driven promotion/demotion,
   measured node speed~~ (§3.3). Per-directory quotas and rules exist (§3.4), maintenance no longer holds the global lock. Remaining: delegated repair capability (repair
   without the file key is possible for replicas and erasure shards; recreating a variant needs the key).
   Remote `delete`/`list` now exist and are authorised by certificate (§4.2).
7. ~~**Traffic-analysis resistance**~~ — padding, cover traffic, anonymous header, obfuscation and relays (§4.3). Onion messages exist (§4.4). Remaining: reliable onion streams, relay discovery beyond direct peers, protocol mimicry. Per-peer bandwidth cap exists (§4.2f).
   The prerequisite (fragmentation and retransmission in the UDP overlay) is done (§4.2b); it also exposed and fixed two
   handshake bugs (a lock held while waiting for the answer, and a wrong DH in message 2) that stopped two real nodes from
   connecting.
8. Modules: repositories, static web hosting, agents, scraping, search.

Steps 3 and 4 alter the foundations; they come early because every module built before them has to
be reworked afterwards.

## 9. Known issues to resolve on the way

- Checkpoint log rotation assumes a witness is away for less than one epoch (20 000 entries, about 70 days at constant
  writes): a witness that missed two rotations is reported by the owner as `LOG_FORKED` and needs `witness clear` plus
  removing its copy of the log.
- `noray.witness.enforce` (default off, to avoid a false alarm locking a node) makes the namespace read-only while an
  alarm about the node's own history stands (`GuardedJournal`: appends and rewrites fail with `DENIED`, reads work) until the
  operator has looked and run `witness clear`. Data already written to the pool before the refusal stays as orphans for GC.
  A node that raised an alarm still runs and still serves blobs; it is not shut down.
- `noray.net.mesh.psk` is mandatory (`noray.net.mesh.psk.insecure=true` allows the all-zero key with a warning). Rotation
  is supported (§4.2d) but distribution of the key is manual: the operator copies it to each node.
- Per-peer quotas are per storage node (default plus overrides); none is carried in the certificate, and blobs stored before
  a quota was enabled are not attributed to anyone. Directory quotas and rules (§3.4) apply to the local
  namespace; the certificate roles (`peer`, `reader`, `operator`) are still the only per-peer permissions on remote storage.
- Remote garbage collection is available (needs an `operator` certificate) but only collects volumes marked
  `engine=true`; the node must not share its store with data that is not described by the caller's live set.
- A request to a node that died waits up to `silenceBeforeProbe` (5 s) plus a probe before failing; after that, requests to it
  fail at once for 10 s (circuit breaker in `RemoteObjectStore`). The overlay does not publish `PeerDown` for vanished peers.
- Both ends initiating a handshake at once both succeed (each keeps the other's session readable under its index), but
  one of the two sessions then goes unused; harmless, wasteful. While `mesh.psk.previous` is set, a handshake against a
  peer on the other key costs one extra round trip plus 0.3 s.
- The vault ships the whole namespace (≤ 8 MiB sealed) and is at most one interval stale (§4.2e).
- The overlay has no congestion control beyond the selective-ACK window and the optional per-peer cap.
- The namespace is a single tree keyed by path held in memory (the journal is streamed at boot, but the live inode table
  is the namespace); per-volume namespaces would remove the remaining coupling between paths and mounts, and a namespace
  larger than memory needs a different structure. The snapshot shipped to the vault is still whole (≤ 8 MiB sealed).
- Two writers to one file: the last plain write wins; `FileSystemService.writeIfVersion` gives compare-and-set on the content
  version (content identity, so rewriting identical bytes keeps the version). GC still blocks data writes while it runs.

### What is deliberately not done

These cannot be done in code alone, or belong to a different project:

- **External cryptographic and security review.** An internal review was done and its findings fixed
  (`docs/SECURITY_REVIEW.md`), but it is not independent. The handshake is Noise IKpsk2 plus an ML-KEM step, assembled here from
  BouncyCastle primitives; the framing, the PSK-tag rotation and the anonymous header are custom. Treat all of it as
  unreviewed until someone independent has.
- **Scale validation.** `RealClusterTest` runs 11 kernels on real sockets including a seeded random kill/restart test
  (`-Dnoray.chaos.seed=N -Dnoray.chaos.rounds=N`); nothing here has been run on hundreds of machines, over a lossy WAN, or on
  terabytes. The namespace journal loaded in memory, the global lock and whole-snapshot replication are the first things to
  give.
- **Out-of-process version of the existing modules.** Untrusted code has a process sandbox (§5.1); the modules of this repo
  still run in the JVM under capabilities, and the sandbox only works on Linux with bubblewrap.
- **Reliable/duplex anonymous streams, relay discovery beyond direct peers, mimicry of TLS/QUIC.** Onion messages (§4.4),
  relays and obfuscation exist; these do not.
- **Modules** (repositories, static hosting, agents, scraping, search) are a separate piece of work on top of this core.
  A `repo` package with refs exists as a first step; signed packages (§5.2) are how such modules are delivered.
