package net.bmqy.dbeaver.webdav.backup.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.eclipse.core.resources.ResourcesPlugin;

public final class BackupScope {

    public static final String WORKSPACE_ARCHIVE_PREFIX = "workspace";
    public static final String DBEAVER_DATA_ARCHIVE_PREFIX = "dbeaver-data";
    private static final List<String> WORKSPACE_METADATA_PLUGIN_NAMES = List.of(
            "org.eclipse.core.resources",
            "org.eclipse.e4.workbench");
    private static final List<String> WORKSPACE_METADATA_PLUGIN_PREFIXES = List.of("org.jkiss.dbeaver");
    private static final List<String> DBEAVER_DATA_INCLUDED_NAMES = List.of(
            "configuration",
            "global-settings",
            "metadata",
            "secure",
            "settings");

    private final Path workspaceRoot;
    private final Path dbeaverDataRoot;

    private BackupScope(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        this.dbeaverDataRoot = resolveDbeaverDataRoot(workspaceRoot);
    }

    public static BackupScope current() {
        Path root = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
        return new BackupScope(root.toAbsolutePath().normalize());
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public List<BackupEntry> collectEntries() throws java.io.IOException {
        List<BackupEntry> entries = new ArrayList<>();
        collectProjectEntries(entries);
        collectWorkspaceConfig(entries);
        collectWorkspacePreferences(entries);
        collectWorkspaceMetadata(entries);
        collectDbeaverDataEntries(entries);
        return entries;
    }

    private void collectProjectEntries(List<BackupEntry> entries) throws java.io.IOException {
        try (Stream<Path> children = Files.list(workspaceRoot)) {
            for (Path project : children.filter(Files::isDirectory).toList()) {
                if (".metadata".equals(project.getFileName().toString())) {
                    continue;
                }
                collectDirectory(entries, project, workspaceRoot, WORKSPACE_ARCHIVE_PREFIX);
            }
        }
    }

    private void collectWorkspaceConfig(List<BackupEntry> entries) throws java.io.IOException {
        Path config = workspaceRoot.resolve(".metadata").resolve(".config");
        collectIfRegularFile(entries, config.resolve("ai-configuration.json"));
        collectIfRegularFile(entries, config.resolve(".ai-configuration.json.bak"));
    }

    private void collectWorkspacePreferences(List<BackupEntry> entries) throws java.io.IOException {
        Path settings = workspaceRoot.resolve(".metadata").resolve(".plugins")
                .resolve("org.eclipse.core.runtime").resolve(".settings");
        if (Files.isDirectory(settings)) {
            try (Stream<Path> paths = Files.walk(settings)) {
                for (Path path : paths.filter(Files::isRegularFile).filter(BackupScope::isPreferenceFile).toList()) {
                    String relative = workspaceRoot.relativize(path).toString().replace('\\', '/');
                    entries.add(new BackupEntry(path, WORKSPACE_ARCHIVE_PREFIX + "/" + relative));
                }
            }
        }
    }

    private void collectIfRegularFile(List<BackupEntry> entries, Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        String relative = workspaceRoot.relativize(path).toString().replace('\\', '/');
        entries.add(new BackupEntry(path, WORKSPACE_ARCHIVE_PREFIX + "/" + relative));
    }

    private void collectWorkspaceMetadata(List<BackupEntry> entries) throws java.io.IOException {
        Path plugins = workspaceRoot.resolve(".metadata").resolve(".plugins");
        if (!Files.isDirectory(plugins)) {
            return;
        }
        try (Stream<Path> children = Files.list(plugins)) {
            for (Path plugin : children.filter(Files::isDirectory).filter(BackupScope::isIncludedMetadataPlugin)
                    .toList()) {
                collectDirectory(entries, plugin, workspaceRoot, WORKSPACE_ARCHIVE_PREFIX);
            }
        }
    }

    private void collectDbeaverDataEntries(List<BackupEntry> entries) throws java.io.IOException {
        if (dbeaverDataRoot == null) {
            return;
        }
        if (!Files.isDirectory(dbeaverDataRoot)) {
            return;
        }
        try (Stream<Path> children = Files.list(dbeaverDataRoot)) {
            for (Path child : children.filter(BackupScope::isIncludedDbeaverDataEntry).toList()) {
                if (isCurrentWorkspaceRoot(child)) {
                    continue;
                }
                if (Files.isRegularFile(child)) {
                    String relative = dbeaverDataRoot.relativize(child).toString().replace('\\', '/');
                    entries.add(new BackupEntry(child, DBEAVER_DATA_ARCHIVE_PREFIX + "/" + relative));
                } else if (Files.isDirectory(child)) {
                    collectDirectory(entries, child, dbeaverDataRoot, DBEAVER_DATA_ARCHIVE_PREFIX);
                }
            }
        }
    }

    private boolean isCurrentWorkspaceRoot(Path path) {
        return path.toAbsolutePath().normalize().equals(workspaceRoot);
    }

    private static void collectDirectory(List<BackupEntry> entries, Path directory, Path base, String prefix)
            throws java.io.IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (isExcluded(path)) {
                    continue;
                }
                String relative = base.relativize(path).toString().replace('\\', '/');
                entries.add(new BackupEntry(path, prefix + "/" + relative));
            }
        }
    }

    private static boolean isExcluded(Path path) {
        for (Path part : path) {
            String partName = part.toString();
            if (".history".equals(partName)) {
                return true;
            }
        }
        String name = path.getFileName().toString();
        return name.endsWith(".log") || name.endsWith(".lock") || name.endsWith(".tmp");
    }

    private static boolean isIncludedMetadataPlugin(Path path) {
        String name = path.getFileName().toString();
        return WORKSPACE_METADATA_PLUGIN_NAMES.contains(name)
                || WORKSPACE_METADATA_PLUGIN_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static boolean isIncludedDbeaverDataEntry(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return DBEAVER_DATA_INCLUDED_NAMES.contains(name) || name.endsWith(".json") || name.endsWith(".properties")
                || name.endsWith(".xml") || name.endsWith(".ini");
    }

    private static boolean isPreferenceFile(Path path) {
        return path.getFileName().toString().endsWith(".prefs");
    }

    private static Path resolveDbeaverDataRoot(Path workspaceRoot) {
        Path parent = workspaceRoot.getParent();
        if (parent == null) {
            return null;
        }
        Path parentName = parent.getFileName();
        if (parentName != null && parentName.toString().toLowerCase(Locale.ROOT).startsWith("workspace")) {
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                return grandParent;
            }
        }
        return parent;
    }
}
