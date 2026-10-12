package com.redactado.noray.web;

import com.redactado.noray.filesystem.Metadata;
import com.redactado.noray.filesystem.store.InMemoryObjectStore;
import com.redactado.noray.filesystem.vfs.DefaultFileSystemService;
import com.redactado.noray.filesystem.vfs.DefaultVirtualFileSystem;
import com.redactado.noray.filesystem.vfs.Mount;
import com.redactado.noray.filesystem.vfs.VfsPath;
import com.redactado.noray.filesystem.vfs.journal.FileNamespaceJournal;
import com.redactado.noray.filesystem.volume.DefaultVolumeManager;
import com.redactado.noray.filesystem.volume.InMemoryVolumeDriver;
import com.redactado.noray.kernel.core.ConcurrentEventBus;
import com.redactado.noray.web.kit.FileBrowser;
import com.redactado.noray.web.kit.Kit;
import com.redactado.noray.web.kit.Table;
import com.redactado.noray.web.ui.Page;
import com.redactado.noray.web.ui.Toast;
import com.redactado.noray.web.ui.UiTester;
import com.redactado.noray.web.ui.Upload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.redactado.noray.web.html.Html.*;
import static org.junit.jupiter.api.Assertions.*;

/** Module authors' view: drive real kit components through UiTester, as operator and as viewer. */
class UiTesterAndComponentsTest {

    @TempDir
    Path tmp;

    record Item(String name, int qty) {
    }

    private static Table<Item> table(List<Item> items) {
        return Table.of("Items", () -> items)
                .column("Name", i -> i.name(), Comparator.comparing(Item::name))
                .numeric("Qty", i -> i.qty(), Comparator.comparingInt(Item::qty))
                .filter((i, q) -> i.name().toLowerCase().contains(q))
                .pageSize(2);
    }

    @Test
    void tablesSortFilterAndPageThroughTheTester() {
        List<Item> items = List.of(new Item("pear", 3), new Item("apple", 9), new Item("fig", 1));
        UiTester ui = UiTester.open(Page.of("t", r -> r.live(table(items))));
        assertTrue(ui.contains("Page 1 of 2"));
        ui.click("Qty");                      // sort ascending by quantity
        assertTrue(ui.text().indexOf("fig") < ui.text().indexOf("pear"), ui.text());
        ui.click("Qty");                      // descending
        assertTrue(ui.text().indexOf("apple") < ui.text().indexOf("pear"), ui.text());
        ui.click("Next");
        assertTrue(ui.contains("Page 2 of 2"));
        ui.change("Type to filter", "ap");
        assertTrue(ui.contains("apple") && !ui.contains("pear"), ui.text());
    }

    @Test
    void viewersMayLookButNotChangeAnything() {
        int[] deleted = {0};
        List<Item> items = List.of(new Item("a", 1), new Item("b", 2), new Item("c", 3));
        Page page = Page.of("t", r -> div(r.live(table(items)),
                Kit.button("Delete everything", Kit.Variant.DANGER, r.onClick(() -> deleted[0]++))));
        UiTester viewer = UiTester.open(page, false);
        viewer.click("Next");                 // view action: allowed
        assertTrue(viewer.contains("Page 2 of 2"));
        viewer.click("Delete everything");    // state-changing action: refused
        assertEquals(0, deleted[0]);
        assertEquals(Toast.Level.WARNING, viewer.toasts().getFirst().level());
        UiTester operator = UiTester.open(page, true);
        operator.click("Delete everything");
        assertEquals(1, deleted[0]);
    }

    @Test
    void timedRegionsAdvanceWithTheClock() {
        int[] n = {0};
        UiTester ui = UiTester.open(Page.of("t", r -> div(r.live(rr -> span("tick " + n[0]++), Duration.ofSeconds(5)))));
        assertTrue(ui.contains("tick 0"));
        ui.advance(Duration.ofSeconds(6));
        assertTrue(ui.contains("tick 1"), ui.text());
    }

    @Test
    void theFileBrowserUploadsAndCreatesFoldersThroughTheScopedService() {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        DefaultVolumeManager volumes = new DefaultVolumeManager(new ConcurrentEventBus(ex, (t, c) -> { }));
        try {
            volumes.registerDriver(new InMemoryVolumeDriver());
            DefaultVirtualFileSystem vfs = new DefaultVirtualFileSystem();
            var v = volumes.attach(Metadata.builder().put("kind", "memory").build());
            vfs.mount(new Mount("data", VfsPath.parse("/mnt/data"), v.id(), ""));
            try (DefaultFileSystemService fs = new DefaultFileSystemService(vfs, volumes, new InMemoryObjectStore(), null,
                    new FileNamespaceJournal(tmp.resolve("ns.journal"), true))) {
                FileBrowser browser = new FileBrowser(fs, VfsPath.parse("/mnt/data"), "Data").allowWrites();
                UiTester ui = UiTester.open(Page.of("files", r -> r.live(browser)));
                assertTrue(ui.contains("This folder is empty"));
                ui.upload("Upload", Map.of(), new Upload("files", "notes.md", "text/markdown", "# Hello".getBytes(StandardCharsets.UTF_8)),
                        new Upload("files", "../escape", "text/plain", new byte[1]));
                assertTrue(ui.contains("notes.md"), ui.text());
                assertEquals("# Hello", new String(fs.readFully(VfsPath.parse("/mnt/data/notes.md")), StandardCharsets.UTF_8));
                assertTrue(ui.toasts().stream().anyMatch(t -> t.level() == Toast.Level.ERROR && t.message().contains("../escape")));
                ui.submit("New folder", Map.of("folder", "docs"));
                assertTrue(ui.contains("docs"));
                ui.click("notes.md");
                assertTrue(ui.contains("Hello"), "the markdown preview renders");
                assertFalse(UiTester.open(Page.of("files", r -> r.live(new FileBrowser(fs, VfsPath.parse("/mnt/data"), "Data"))))
                        .html().contains("Upload"), "uploads are off unless allowed");
            }
        } finally {
            volumes.close();
            ex.shutdownNow();
        }
    }

    @Test
    void newComponentsRenderAccessibly() {
        String html = div(
                Kit.dialog("d1", "Confirm", List.of(p("Sure?")), List.of(Kit.dialogClose("Cancel"))),
                Kit.dialogButton("Open", "d1", Kit.Variant.PRIMARY),
                Kit.menu("More", List.of(new Kit.MenuItem("Rename"))),
                Kit.tree(List.of(new Kit.TreeItem("root", List.of(Kit.TreeItem.leaf("child")))), 1),
                Kit.timeline(List.of(new Kit.TimelineEntry(Instant.parse("2026-10-12T10:00:00Z"), "Installed", "v1"))),
                Kit.media("/img.png", "A photo", "caption"),
                Kit.gallery(Kit.media("/a.png", "a", null))).html();
        assertTrue(html.contains("<dialog id=\"d1\" aria-label=\"Confirm\">"));
        assertTrue(html.contains("data-nr-b=\"dialog-open\" data-nr-target=\"d1\""));
        assertTrue(html.contains("role=\"menu\"") && html.contains("role=\"menuitem\""));
        assertTrue(html.contains("role=\"tree\"") && html.contains("<details open>"));
        assertTrue(html.contains("<time datetime=\"2026-10-12T10:00:00Z\">2026-10-12 10:00 UTC</time>"));
        assertTrue(html.contains("<figure class=\"nr-media\"><img src=\"/img.png\" alt=\"A photo\""));
        assertTrue(html.contains("class=\"nr-gallery\" role=\"list\""));
    }
}
