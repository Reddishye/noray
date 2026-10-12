# Noray — Operations

How to run a node and a small mesh. Design background is in `ARCHITECTURE.md`; the unresolved limits are listed in its §9
and you should read them before relying on this for anything that matters.

## 1. Install

Releases are reproducible: `SOURCE_DATE_EPOCH=<commit time> ./gradlew release` writes the fat jar, the distribution
archives, a self-contained runtime image (`noray-<version>-linux-<arch>.tar.gz`: jlink JDK subset + jar + `bin/noray`),
a CycloneDX SBOM and `SHA256SUMS` into `build/release`. Anyone can rebuild from the same commit and compare the sums.
Sign the sums file, not each artifact: `ssh-keygen -Y sign -f release_key -n noray-release SHA256SUMS` (verify with
`ssh-keygen -Y verify -f allowed_signers -I <you> -n noray-release -s SHA256SUMS.sig < SHA256SUMS`, then `sha256sum -c SHA256SUMS`).

With the runtime image nothing else is needed on the host: unpack it to `/opt/noray` and continue with the unit below.
From source:

```
./gradlew installDist                  # build/install/noray/{bin,lib}
sudo cp -r build/install/noray /opt/noray
sudo cp dist/noray.service /etc/systemd/system/
sudo install -d -m 0750 -o noray -g noray /etc/noray /var/lib/noray
sudo cp dist/noray.properties.example /etc/noray/noray.properties
```

Edit `/etc/noray/noray.properties`. At least: `noray.net.mesh.psk` (the same 64 hex characters on every node,
`openssl rand -hex 32`), `noray.net.udp.port`, `noray.data.dir`, and the keyring passphrase in a root-owned file
(`noray.keyring.passphrase.file`). Then `systemctl enable --now noray`. The unit runs `--daemon` (no shell) and stops cleanly on
SIGTERM. Without `--daemon` you get the interactive shell, which has every command mentioned below.

## 2. Building a mesh

1. **Authority keys** (on offline machines, one per custodian):
   `identity authority new authority-1.key --passphrase-file pass1` → prints the public key. Never copy the private file to a node.
2. **Trust settings** (on one machine): `identity authority config PUB1 PUB2 PUB3 --threshold 2` → paste the two printed lines
   into every node's configuration.
3. **Enrol a node:** on the node `identity request`; on an authority machine `identity issue REQUEST --key authority-1.key
   --passphrase-file pass1 --role peer --days 30`; with a threshold above 1 the other custodians each run `identity sign CERT --key ...`;
   on the node `identity install CERT`. Peers are admitted with `identity enroll CERT host:port` (or learn each other through the
   certificate exchange once one hint is known).
4. **Storage:** `volume add kind=remote uri=noray://<node id> engine=true domain=<failure domain>` for each storage node, then a
   `kind=pool` volume mounted under `/mnt`. Several `domain` values are what make erasure coding survive the loss of a rack or site.
5. **Revoking** a node: `identity revoke NODEID --key ...` (and `sign` if needed), then `identity apply LIST` on any node; gossip does the rest.
   Certificates are short-lived on purpose: renew with `issue` before `--days` run out.

## 3. Routine

| Task | How |
|---|---|
| Is everything healthy? | `volume list`, `witness status`, `vault status`, `metrics` |
| Rebuild lost redundancy | `storage repair` (also runs every `noray.fs.maintenance.interval.s`) |
| Reclaim orphans | `storage gc` (dry run first: see `storage` help) |
| Scrape metrics | `noray.metrics.port=9464` then `curl http://127.0.0.1:9464/metrics` (loopback only: put a reverse proxy in front) |
| Structured journal info | `journal --json` |
| Back up | `backup export /safe/node.nrbak --passphrase-file F` (encrypted, verified on write; add `--include-blobs` for local stores), `backup verify FILE`. Keep the backup passphrase apart from the backup. A restored older directory raises witness alarms by design |
| Restore drill | `backup restore FILE /tmp/drill --dry-run` regularly; a real restore goes into the stopped node's empty data directory |
| Emergency | `panic wipe --confirm <first 8+ chars of node id>` shreds keys, identity and journal, then stops (read `help panic` for its limits) |
| Read-only dashboard access | `web token new auditor --viewer` (can browse, sort, filter; cannot change anything) |
| Fetch a package from your own node | `pkg fetch <node id> <payload sha256>`, then `pkg approve` as usual |
| Lose the data directory | Reinstall with the same passphrase and certificate, join the mesh, `vault restore`, restart. Data on other nodes is untouched |
| Rotate the mesh key | Put the old value in `noray.net.mesh.psk.previous` and the new in `noray.net.mesh.psk` on every node (restart each), then drop `previous` everywhere |
| Rotate an authority key | Create the new authority set, issue new certificates signed under it, change `noray.identity.authority.keys` on all nodes, revoke the old |
| Clear an integrity alarm | Investigate, then `witness clear` |
| Limit a directory | `ns set /mnt/pool/team quota.bytes 10737418240`, `ns set DIR quota.files 100000`, `ns show DIR` (rules inherit; the nearest wins) |
| Protect a directory | `ns set DIR readonly true` (frozen), or `deny.delete true` + `deny.overwrite true` (evidence: only new files). `ns clear DIR KEY` lifts a rule |
| Anonymous messages between nodes | `noray.net.onion.enabled=true` on every node that should relay or receive; modules use the `OnionService` (needs an exact `service:OnionService` grant or trusted status) |
| Open the dashboard | `noray.web.port=8787` (loopback by default), then `web token new` in the shell and open `http://127.0.0.1:8787/`. Remote access: SSH tunnel, or `noray.web.bind` + `noray.web.tls.keystore` (PKCS#12) + `noray.web.hosts` |
| Add third-party code | `pkg publisher add NAME KEY` (once), `pkg install app.nrpkg` (shows requested grants, runs nothing), `pkg approve ID VERSION [--grants G]`; JVM packages load at once on the running node, process packages run with `pkg run ID` |
| Undo a package update | `pkg rollback ID` (previous active version), `pkg deactivate ID`, `pkg audit --verify` |
| Publish a package | `pkg keygen key --passphrase-file F`, `pkg build --id ... --version ... --main ... --payload app.jar --publisher NAME --out app.nrpkg`, `pkg sign app.nrpkg --key key` (each co-signer) |
| Run untrusted code | `sandbox status`, then `sandbox run --write /mnt/pool/box JAR CLASS` (Linux with bubblewrap; refuses to run otherwise) |

## 4. What to watch

Upgrades: with a keyring, the first start after this release finds the package approvals unsealed and loads no package until
`pkg reseal` (after checking `pkg list`). Sandboxed in-JVM modules can get a time budget with
`noray.module.<id>.task.seconds.per.min`.

Logs: set `noray.log.format=json` for one JSON object per line on stderr (journald keeps it: `journalctl -u noray -o cat | jq`).
Values of anything named like a passphrase, PSK, token, seed or private key are replaced by `<redacted>` before they are written.

### Metric catalogue

All names are exported at `/metrics` as `noray_<name with dots as underscores>`. Counters only go up; gauges are sampled.

| Metric | Type | Meaning | Alert when |
|---|---|---|---|
| `net.bytes.in/out`, `net.packets.in/out` | counter | overlay traffic | — (capacity planning) |
| `net.handshakes.in/out` | counter | sessions established | — |
| `net.handshakes.refused` | counter | handshakes from unknown or revoked keys | rate rises: probing |
| `net.handshakes.rationed` | counter | handshakes dropped by the rate limiter | rate rises: flood |
| `net.handshakes.replayed` | counter | replayed first messages | any |
| `net.throttled` | counter | sends delayed by `noray.net.peer.kib.s` | sustained: cap is the bottleneck |
| `net.relay.bytes`, `net.relay.limited` | counter | relayed traffic, relay rate-limit hits | `limited` sustained |
| `blob.bytes.in/out` | counter | bytes stored/served for peers | — |
| `blob.served.<op>` | counter | blob requests served, per operation | — |
| `blob.denied` | counter | blob requests refused (role, quota) | rate rises |
| `remote.probes` | counter | requests that went silent and triggered a liveness probe | — |
| `remote.peers.gone` | counter | probes that found the peer gone | rate rises: flapping peer |
| `remote.fastfail` | counter | requests refused at once while a peer is known gone | — |
| `volumes.total/online/offline` | gauge | storage volumes by state | `offline` > 0 for > grace period |
| `volumes.offline.events` | counter | transitions to offline | rate rises |
| `data.repairs` | counter | chunks rebuilt | jump after a node loss is expected; steady growth is not |
| `fs.quota.reconciled` | counter | quota counters the maintenance loop had to correct | any (should stay 0) |
| `vault.pushes`, `vault.stored` | counter | namespace snapshots sent / kept for peers | `pushes` stops growing while enabled |
| `witness.alarms` | counter | rollback / rewrite / fork evidence | any |
| `witness.logs.dropped` | counter | witnessed logs discarded (limits) | rate rises |

Watch in particular:

- `volumes.offline.events` rising: a peer is flapping or unreachable.
- `net.handshakes.rationed` / `net.handshakes.refused` rising: someone is probing or flooding the UDP port.
- `net.throttled` rising: the per-peer cap (`noray.net.peer.kib.s`) is the bottleneck.
- `remote.probes`, `repairs`: how often nodes stop answering and how much rebuilding follows.
- Witness alarms (`witness status` exits non-zero): a journal rolled back or rewritten, or a forked log. Evidence, not proof.

## 5. Sizing and limits to know

- Cold data is erasure coded 4+4: it needs 8 reachable storage nodes to be *written*; reads need any 4.
- The namespace is one tree in memory (the journal is streamed at boot, so it is not held twice); writes, repair and
  re-tiering no longer block each other, but garbage collection pauses data writes while it runs. Millions of entries need
  memory for the inode table.
- Onion messages are best-effort (no retransmission, no reply path, at most 16 KiB each) and need at least one relay besides
  sender and destination.
- Quotas count logical bytes, not erasure-coded storage.
- The vault copy of the namespace is at most `noray.vault.interval.s` old and must stay under 8 MiB sealed.
- Time: certificate expiry uses the node clock with a skew (`noray.identity.skew.s`); keep NTP running.
- Not provided: an external security review, behaviour on hundreds of nodes or terabytes, an independent review (see `docs/SECURITY_REVIEW.md` for the internal one), the process sandbox on anything but Linux with bubblewrap and user namespaces.
