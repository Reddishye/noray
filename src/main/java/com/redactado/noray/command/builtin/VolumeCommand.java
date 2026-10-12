package com.redactado.noray.command.builtin;

import com.redactado.noray.command.Command;
import com.redactado.noray.command.CommandCategory;
import com.redactado.noray.command.CommandInvocation;
import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.config.FsConfigService;
import com.redactado.noray.filesystem.volume.Volume;
import com.redactado.noray.filesystem.volume.VolumeId;

import java.util.UUID;

/** Defines persistent volumes: they are re-attached automatically at every boot. */
public final class VolumeCommand implements Command {

    @Override public String name() { return "volume"; }
    @Override public String description() { return "Define or remove persistent volumes"; }
    @Override public String synopsis() { return "volume add kind=KIND [key=value...] | volume rm UUID | volume list"; }
    @Override public CommandCategory category() { return CommandCategory.SYSTEM; }

    @Override
    public String longHelp() {
        return """
            Usage: volume add kind=KIND [key=value]...
                   volume rm UUID
                   volume list

            add   attach a volume and remember it, so it is attached again at every boot
            rm    detach a volume and forget it (mounts that use it are not removed)
            list  show the persistent volume definitions

            Examples:
              volume add kind=local path=/srv/noray/data
              volume add kind=local path=/mnt/usb/noray writable=false
              mount <UUID> /mnt/data

            Mounts of persistent volumes are remembered automatically.
            """;
    }

    @Override
    public int execute(CommandInvocation inv) throws Exception {
        var out = inv.out();
        var args = inv.args();
        FsConfigService cfg = inv.context().getServiceRegistry().get(FsConfigService.class).orElse(null);
        if (cfg == null) {
            out.println("volume: filesystem configuration service is not available");
            return 1;
        }
        if (args.isEmpty()) {
            out.println(longHelp());
            return 1;
        }

        try {
            switch (args.get(0)) {
                case "add" -> {
                    Metadata.Builder b = Metadata.builder();
                    for (String kv : args.subList(1, args.size())) {
                        int eq = kv.indexOf('=');
                        if (eq <= 0) {
                            out.println("volume: expected key=value, got '" + kv + "'");
                            return 1;
                        }
                        b.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                    Metadata meta = b.build();
                    if (meta.getString("kind", "").isEmpty()) {
                        out.println("volume: kind=... is required");
                        return 1;
                    }
                    Volume v = cfg.addVolume(meta);
                    out.println(v.uuid());
                    return 0;
                }
                case "rm" -> {
                    if (args.size() != 2) {
                        out.println("Usage: volume rm UUID");
                        return 1;
                    }
                    cfg.removeVolume(new VolumeId(UUID.fromString(args.get(1))));
                    return 0;
                }
                case "list" -> {
                    cfg.definitions().forEach((id, meta) ->
                            out.printf("%s kind=%s %s%n", id.value(), meta.getString("kind", "?"),
                                    describe(meta)));
                    return 0;
                }
                default -> {
                    out.println("volume: unknown action '" + args.get(0) + "'");
                    return 1;
                }
            }
        } catch (Exception e) {
            out.println("volume: " + e.getMessage());
            return 1;
        }
    }

    private static String describe(Metadata meta) {
        String path = meta.getString("path", "");
        String uri = meta.getString("uri", "");
        return !path.isEmpty() ? "path=" + path : !uri.isEmpty() ? "uri=" + uri : "";
    }
}
