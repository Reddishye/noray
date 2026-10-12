# Noray — Pending work

State on Oct 12, 2026: the core is complete and tested but **not production-ready for adversarial use**.
This file lists what is left to call it finished, in priority order.
Design background is in `ARCHITECTURE.md`; the unresolved limits are in its §9; the internal crypto review is in `SECURITY_REVIEW.md`.

Legend: **[blocker]** needed before protecting anyone at risk · **[important]** needed for a serious deployment ·
**[polish]** quality of life · effort is a rough guess (S ≤ 2 days, M ≤ 2 weeks, L > 2 weeks).

## 1. Blockers

| # | Item | Why | Effort |
|---|---|---|---|
| B1 | **Independent cryptographic and security review** of the handshake (Noise IKpsk2 + ML-KEM-768), PSK-tag rotation, anonymous header, sealed key files, onion cells | Everything is custom assembly of BouncyCastle primitives; the internal review found 8 real flaws, so others are likely. No formal analysis (ProVerif/Tamarin) exists | external |
| B2 | **Scale and soak validation**: hundreds of nodes, lossy WAN, terabytes, weeks of uptime, partitions, clock skew | Largest run so far is 11 kernels on one machine. Journal replay, whole-snapshot vault shipping and the in-memory inode table are the first things to give | L |
| B3 | **Constant-time review of the libraries in use** (BouncyCastle ML-KEM and X25519 on the target JVM) | Timing side channels are untested | S + external |
| B4 | **Threat-model sign-off per deployment**: who the adversary is, what a seized node reveals, what the operator is willing to lose | Without it "ready" has no meaning | S |

## 2. Important

I3, I7, I8, I11, I13, I14 and P2 change cryptographic, network or garbage-collection behaviour whose failures do not show in
unit tests (data loss, weaker anonymity, interoperability). They are deliberately left until after the external review (B1),
as §7 recommends.

### Storage and namespace
- **I1** Namespace larger than memory: the inode table is the namespace and lives in RAM. Needs a paged or per-volume structure (§9). L
- **I2** Vault ships the whole namespace (≤ 8 MiB sealed). Move to incremental operation-log replication; this is also the "signed log as live storage" item of roadmap step 4. L
- **I3** Delegated repair capability: repair without the file key works for replicas and erasure shards, but recreating a variant needs the key. M
- **I7** GC pauses data writes while it runs. Make it incremental (mark in the background, sweep with the grace period only). M
- **I8** Remote storage permissions are still the three certificate roles (`peer`, `reader`, `operator`). Carry per-peer quotas and path scopes in the certificate. M

### Network and anonymity
- **I10** Reliable, duplex anonymous streams on top of the onion layer (reply blocks or rendezvous, retransmission, flow control). L
- **I11** Relay discovery beyond direct peers: gossip of onion keys so a destination does not have to be a neighbour; relay selection by trust/jurisdiction/latency (the `PathSelector` SPI is ready). M
- **I12** Protocol mimicry (TLS/QUIC look-alike) for hostile networks that block unknown UDP. L
- **I13** Congestion control in the overlay beyond the selective-ACK window and the per-peer cap. M
- **I14** Distribution of the mesh PSK is manual. Derive it from the certificates, or switch to per-peer authentication only. M

### Isolation
- **I16** Run *existing* modules out of process: widen the sandbox bridge (config read, events, services) one operation at a time, each as an explicit decision. L
- **I19b** In-JVM modules now have a task-time budget (`noray.module.<id>.task.seconds.per.min`); per-module memory cannot be divided
  inside one JVM. Untrusted code belongs in the process sandbox (or I16). Remaining: per-thread CPU accounting once the JDK
  supports it for virtual threads.

### Operations
- **I21b** Hidden volumes (deniable storage). The panic wipe exists (`panic wipe`, crypto-shredding; limits in its help). M
- **I22b** Release signing: releases are reproducible and come with an SBOM and `SHA256SUMS` (`./gradlew release`), but signing
  needs the publisher's key and is a manual step (OPERATIONS §1). Native image is not attempted (BouncyCastle and reflection). S
- **I4b** Quotas can only charge logical bytes; `ns usage` now shows a stored-bytes estimate. Charging the estimate is a policy decision. S

## 3. Polish

- **P2** Both ends initiating a handshake at once leaves one session unused; harmless but wasteful. S
- **P7** Reconcile docs: `ARCHITECTURE.md` §9 and this file should not drift; when an item here is done, remove it from both. S

## 4. Application layer (the reason the core exists)

A `repo` package with refs exists as a first step, and static hosting exists as a reference module (`examples/static-hosting`,
a signed sandboxed package using the web UI's `web.site` grant). Suggested order for the rest, each one exercising a different
part of the core:

1. ~~**Static web hosting**~~ (done as the reference module; a production version would add per-site quotas and cache headers).
2. **Repositories** (git-like objects in the pool, refs signed by node keys). Exercises versioning and the signed log.
3. **Agents / scraping** (untrusted, needs the process sandbox and network egress policy: the sandbox has no network today, so design the egress bridge explicitly).
4. **Search** (index per user, encrypted; hardest privacy problem, do last).

## 5. Package system

M1–M4 are implemented (`com.redactado.noray.pkg`, shell `pkg`, ARCHITECTURE §5.2): strict signed manifest and `.nrpkg`
container, pinned k-of-n publishers, install without running, per-version approval that can only narrow the request, side-by-side
versions with activate/rollback, exact-hash dependencies, `noray.api` checks, re-verification at every load, JVM packages as
sandboxed modules and process packages through the sandbox, hash-chained audit log. The done criteria (tampered manifest,
swapped payload, downgrade, unpinned publisher, approval wider than the request) are each refused by a test.

Also implemented since: **M5** (`pkg fetch PEER SHA256` over the mesh, verified locally, never approved), **PK1** (approvals and
publishers sealed with a keyring HMAC; offline edits stop all loading until `pkg reseal`), **PK2** (JVM packages load, reload and
unload on a running node after approve/activate/rollback/deactivate).

## 6. Web UI module

Implemented (`docs/WEB_UI_DESIGN.md`), including the separate site listener, multipart uploads, dialog/menu/tree/timeline/
pagination, `UiTester`, slow-region logging and SSE patch cap, read-only viewer tokens, and the static hosting reference module
shipped as a signed package (`examples/static-hosting`). Default theme: noir.

Remaining ideas, none blocking: drag-and-drop upload with progress, a chart for histograms/heatmaps, per-user roles mapped to
identity certificates.

## 7. Suggested order of attack

1. Pay for **B1** now; it changes the foundations if it finds something, and everything else sits on top of it.
2. After B1: the items held back in §2 (I3, I7, I8, I11, I13, I14, P2), with whatever the review changes.
3. Module #2 (**repositories**) on the package and web UI APIs, to stress versioning and the signed log.
4. **I2** (incremental vault / operation log) and **I1** (namespace beyond RAM) before any scale test (**B2**).
5. **I10-I11** (onion streams, relay discovery) only if a module needs anonymity beyond one-way messages.
