package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.data.crypto.Keyring;
import com.redactado.noray.ops.Backup;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Encrypted, verified backups of the data directory, and restore drills. */
public final class BackupCommand implements Command {

    @Override public String name() { return "backup"; }
    @Override public String description() { return "Encrypted backups of the data directory: export, verify, restore"; }
    @Override public String synopsis() { return "backup export FILE | verify FILE | restore FILE DIR [--dry-run] [--force]"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }
    @Override public Set<String> switchFlags() { return Set.of("include-blobs", "dry-run", "force"); }
    @Override public List<String> subcommands() { return List.of("export", "verify", "restore"); }

    @Override
    public String longHelp() {
        return """
            A backup holds what a node needs to come back: keyring, node keys, namespace journal, volume definitions,
            identity, packages and web tokens (local blob stores only with --include-blobs). It is encrypted with a
            backup passphrase (Argon2id + ChaCha20-Poly1305 in authenticated chunks) and opens without the keyring.

              backup export FILE [--include-blobs]   write a backup (atomically; mode 0600)
              backup verify FILE                     decrypt and check every byte, write nothing
              backup restore FILE DIR [--dry-run] [--force]
                                                     restore into DIR (empty unless --force); every file is checked
                                                     after it is written. Restore into a stopped node's data directory.

            Passphrase: --passphrase-file FILE or $NORAY_BACKUP_PASSPHRASE. Store it apart from the backup.
            Drill: export, then `restore FILE /tmp/drill --dry-run` regularly, and a full restore before you need one.
            Export while the node is quiet: files changed during the export are caught only if they shrink.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        PrintStream out = inv.out();
        var a = inv.args();
        if (a.size() < 2) {
            out.println(longHelp());
            return 1;
        }
        char[] pass = passphrase(inv);
        if (pass == null) {
            inv.err().println("backup: give --passphrase-file FILE or set NORAY_BACKUP_PASSPHRASE");
            return 1;
        }
        Path file = Path.of(a.get(1));
        try {
            switch (a.get(0)) {
                case "export" -> {
                    Path dataDir = Path.of(inv.context().getConfiguration().get("noray.data.dir", "data"));
                    var entries = Backup.export(dataDir, file, pass, inv.hasFlag("include-blobs"), Keyring.Params.DEFAULT);
                    long bytes = entries.stream().mapToLong(Backup.Entry::size).sum();
                    out.println("backed up " + entries.size() + " file(s), " + bytes + " bytes, to " + file);
                    Backup.verify(file, pass);
                    out.println("verified: the backup opens with this passphrase and every file checks out");
                    return 0;
                }
                case "verify" -> {
                    var entries = Backup.verify(file, pass);
                    entries.forEach(e -> out.printf("%10d  %s%n", e.size(), e.path()));
                    out.println("OK: " + entries.size() + " file(s) intact");
                    return 0;
                }
                case "restore" -> {
                    if (a.size() < 3) {
                        out.println("backup restore FILE DIR [--dry-run] [--force]");
                        return 1;
                    }
                    boolean dry = inv.hasFlag("dry-run");
                    var entries = Backup.restore(file, pass, Path.of(a.get(2)), dry, inv.hasFlag("force"));
                    out.println((dry ? "would restore " : "restored ") + entries.size() + " file(s) into " + a.get(2));
                    return 0;
                }
                default -> {
                    out.println(longHelp());
                    return 1;
                }
            }
        } finally {
            Arrays.fill(pass, '\0');
        }
    }

    private static char[] passphrase(CommandInvocation inv) throws Exception {
        String f = inv.getFlag("passphrase-file", "");
        if (!f.isEmpty()) return Files.readString(Path.of(f)).stripTrailing().toCharArray();
        String env = System.getenv("NORAY_BACKUP_PASSPHRASE");
        return env == null || env.isEmpty() ? null : env.toCharArray();
    }
}
