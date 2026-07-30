package net.bmqy.dbeaver.webdav.backup.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RestoreArchiveCommand {

    private static final String WORKSPACE_ARCHIVE_PREFIX = "workspace";
    private static final String LEGACY_WORKSPACE_ARCHIVE_PREFIX = "workspace6";
    private static final String DBEAVER_DATA_ARCHIVE_PREFIX = "dbeaver-data";
    private static final Pattern MANIFEST_WORKSPACE_PATTERN = Pattern
            .compile("\"workspace\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private RestoreArchiveCommand() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: RestoreArchiveCommand <archive> <workspaceRoot> <parentPid>");
        }
        Path archive = Path.of(args[0]).toAbsolutePath().normalize();
        Path workspaceRoot = Path.of(args[1]).toAbsolutePath().normalize();
        long parentPid = Long.parseLong(args[2]);
        try {
            waitForProcessExit(parentPid);
            Thread.sleep(2000);
            Set<String> workspaceArchivePrefixes = workspaceArchivePrefixes(archive);
            deleteRestoreRoots(archive, workspaceRoot, workspaceArchivePrefixes);
            restoreArchive(archive, workspaceRoot, workspaceArchivePrefixes);
            markWorkspaceRefreshRequired(workspaceRoot);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static void waitForProcessExit(long pid) {
        Optional<ProcessHandle> process = ProcessHandle.of(pid);
        process.ifPresent(handle -> handle.onExit().join());
    }

    private static void deleteRestoreRoots(Path archive, Path workspaceRoot, Set<String> workspaceArchivePrefixes)
            throws IOException {
        Path dataRoot = resolveDbeaverDataRoot(workspaceRoot);
        Set<Path> roots = new LinkedHashSet<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String archivePath = normalizeArchivePath(entry.getName());
                if (!isDirectoryEntry(entry, archivePath) && !"manifest.json".equals(archivePath)) {
                    cleanupRoot(archivePath, workspaceRoot, dataRoot, workspaceArchivePrefixes).ifPresent(roots::add);
                }
                zip.closeEntry();
            }
        }
        for (Path root : roots.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
            deleteRecursively(root);
        }
    }

    private static Optional<Path> cleanupRoot(String archivePath, Path workspaceRoot, Path dataRoot,
            Set<String> workspaceArchivePrefixes) throws IOException {
        archivePath = normalizeArchivePath(archivePath);
        Optional<String> workspaceRelativePath = workspaceRelativePath(archivePath, workspaceArchivePrefixes);
        if (workspaceRelativePath.isPresent()) {
            return cleanupWorkspaceRoot(workspaceRelativePath.get(), workspaceRoot);
        }
        if (archivePath.startsWith(DBEAVER_DATA_ARCHIVE_PREFIX + "/") && dataRoot != null) {
            String relative = archivePath.substring((DBEAVER_DATA_ARCHIVE_PREFIX + "/").length());
            return cleanupFirstSegment(relative, resolveDbeaverDataTargetBase(relative, workspaceRoot, dataRoot));
        }
        throw new IOException("Unsupported backup entry: " + archivePath);
    }

    private static Optional<Path> cleanupWorkspaceRoot(String relative, Path workspaceRoot) throws IOException {
        String[] parts = relative.split("/");
        if (parts.length >= 2 && ".metadata".equals(parts[0]) && ".config".equals(parts[1])) {
            return checkedCleanupRoot(workspaceRoot.resolve(".metadata").resolve(".config"), workspaceRoot);
        }
        if (parts.length >= 3 && ".metadata".equals(parts[0]) && ".plugins".equals(parts[1])) {
            if (parts.length >= 4 && "org.eclipse.core.runtime".equals(parts[2]) && ".settings".equals(parts[3])) {
                return checkedCleanupRoot(workspaceRoot.resolve(".metadata").resolve(".plugins")
                        .resolve("org.eclipse.core.runtime").resolve(".settings"), workspaceRoot);
            }
            return checkedCleanupRoot(workspaceRoot.resolve(".metadata").resolve(".plugins").resolve(parts[2]),
                    workspaceRoot);
        }
        return cleanupFirstSegment(relative, workspaceRoot);
    }

    private static Optional<Path> cleanupFirstSegment(String relative, Path base) throws IOException {
        String[] parts = relative.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            return Optional.empty();
        }
        return checkedCleanupRoot(base.resolve(parts[0]), base);
    }

    private static Optional<Path> checkedCleanupRoot(Path root, Path base) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedBase = base.toAbsolutePath().normalize();
        if (normalizedRoot.equals(normalizedBase) || !normalizedRoot.startsWith(normalizedBase)) {
            throw new IOException("Refusing to delete restore root outside allowed directory: " + root);
        }
        return Optional.of(normalizedRoot);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void restoreArchive(Path archive, Path workspaceRoot, Set<String> workspaceArchivePrefixes)
            throws IOException {
        Path dataRoot = resolveDbeaverDataRoot(workspaceRoot);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String archivePath = normalizeArchivePath(entry.getName());
                if (isDirectoryEntry(entry, archivePath) || "manifest.json".equals(archivePath)) {
                    continue;
                }
                Path target = resolveRestoreTarget(archivePath, workspaceRoot, dataRoot, workspaceArchivePrefixes);
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    zip.transferTo(out);
                }
                zip.closeEntry();
            }
        }
    }

    private static Path resolveRestoreTarget(String archivePath, Path workspaceRoot, Path dataRoot,
            Set<String> workspaceArchivePrefixes) throws IOException {
        archivePath = normalizeArchivePath(archivePath);
        Path target;
        Optional<String> workspaceRelativePath = workspaceRelativePath(archivePath, workspaceArchivePrefixes);
        if (workspaceRelativePath.isPresent()) {
            target = workspaceRoot.resolve(workspaceRelativePath.get());
        } else if (archivePath.startsWith(DBEAVER_DATA_ARCHIVE_PREFIX + "/") && dataRoot != null) {
            String relative = archivePath.substring((DBEAVER_DATA_ARCHIVE_PREFIX + "/").length());
            target = resolveDbeaverDataTargetBase(relative, workspaceRoot, dataRoot).resolve(relative);
        } else {
            throw new IOException("Unsupported backup entry: " + archivePath);
        }
        Path normalized = target.toAbsolutePath().normalize();
        boolean inWorkspace = normalized.startsWith(workspaceRoot);
        boolean inDataRoot = dataRoot != null && normalized.startsWith(dataRoot.toAbsolutePath().normalize());
        if (!inWorkspace && !inDataRoot) {
            throw new IOException("Refusing to restore outside DBeaver data directory: " + archivePath);
        }
        return normalized;
    }

    private static String normalizeArchivePath(String archivePath) {
        return archivePath.replace('\\', '/');
    }

    private static boolean isDirectoryEntry(ZipEntry entry, String archivePath) {
        return entry.isDirectory() || archivePath.endsWith("/");
    }

    private static Set<String> workspaceArchivePrefixes(Path archive) throws IOException {
        Set<String> prefixes = new LinkedHashSet<>();
        prefixes.add(WORKSPACE_ARCHIVE_PREFIX);
        prefixes.add(LEGACY_WORKSPACE_ARCHIVE_PREFIX);
        manifestWorkspaceName(archive).ifPresent(prefixes::add);
        return prefixes;
    }

    private static Optional<String> manifestWorkspaceName(Path archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String archivePath = normalizeArchivePath(entry.getName());
                if ("manifest.json".equals(archivePath)) {
                    return manifestWorkspacePath(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                            .flatMap(RestoreArchiveCommand::lastPathSegment)
                            .filter(name -> !DBEAVER_DATA_ARCHIVE_PREFIX.equals(name));
                }
                zip.closeEntry();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> manifestWorkspacePath(String manifest) {
        Matcher matcher = MANIFEST_WORKSPACE_PATTERN.matcher(manifest);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(unescapeJson(matcher.group(1)));
    }

    private static String unescapeJson(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\\' && i + 1 < text.length()) {
                result.append(text.charAt(++i));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static Optional<String> lastPathSegment(String path) {
        String normalized = normalizeArchivePath(path);
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '/') {
            end--;
        }
        int separator = normalized.lastIndexOf('/', end - 1);
        String name = normalized.substring(separator + 1, end);
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    private static Optional<String> workspaceRelativePath(String archivePath, Set<String> workspaceArchivePrefixes) {
        int separator = archivePath.indexOf('/');
        if (separator <= 0 || separator + 1 >= archivePath.length()) {
            return Optional.empty();
        }
        String firstSegment = archivePath.substring(0, separator);
        if (workspaceArchivePrefixes.contains(firstSegment)) {
            return Optional.of(archivePath.substring(separator + 1));
        }
        return Optional.empty();
    }

    private static void markWorkspaceRefreshRequired(Path workspaceRoot) throws IOException {
        Path marker = workspaceRoot.resolve(".metadata").resolve(".plugins")
                .resolve("net.bmqy.dbeaver.webdav.backup").resolve("restore-refresh.marker");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "refresh", StandardCharsets.UTF_8);
    }

    private static Path resolveDbeaverDataTargetBase(String relative, Path workspaceRoot, Path dataRoot) {
        String[] parts = relative.split("/");
        Path workspaceName = workspaceRoot.getFileName();
        Path legacyDataRoot = workspaceRoot.getParent();
        if (parts.length > 0 && workspaceName != null && legacyDataRoot != null
                && workspaceName.toString().equals(parts[0])) {
            return legacyDataRoot;
        }
        return dataRoot;
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
