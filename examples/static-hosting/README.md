# Example: static hosting module

A third-party module written only against Noray's public API (`KernelModule`, `WebApps`, `web.kit`). It serves a folder of
the Noray filesystem as a public website and adds an admin page to the dashboard. It runs as a **sandboxed** module: it can
read its folder, mount its site prefix and its app, and read its own settings, nothing else.

Build and package it (from the repository root):

```
./gradlew staticHostingJar                       # build/libs/static-hosting.jar
pkg keygen publisher.key --passphrase-file pass  # once, on the publisher's machine
pkg build --id org.noray.examples.hosting --version 1.0.0 --sandbox jvm \
    --main org.noray.examples.hosting.StaticHostingModule --payload build/libs/static-hosting.jar \
    --publisher example --requests fs.read:/mnt/sites,web.site:/site,web.app,config.read:hosting.* \
    --description "Serves /mnt/sites at /site" --out static-hosting.nrpkg
pkg sign static-hosting.nrpkg --key publisher.key --passphrase-file pass
```

On the node:

```
pkg publisher add example <publisher public key> --trusted   # in-JVM packages need a trusted publisher
pkg install static-hosting.nrpkg
pkg approve org.noray.examples.hosting 1.0.0
```

The module loads as soon as it is approved (no restart): the site is at `/site/` (sandboxed into an opaque origin, or on its own origin with
`noray.web.site.port`), and the admin page under *Static hosting* in the dashboard. Settings: `hosting.root`
(default `/mnt/sites`) and `hosting.prefix` (default `/site`; the approved `web.site` grant must cover it).

`PackageLoaderTest.theStaticHostingExampleRunsAsASignedSandboxedPackage` builds, signs, installs, approves and loads this
module in a real kernel and fetches a page over HTTP.
