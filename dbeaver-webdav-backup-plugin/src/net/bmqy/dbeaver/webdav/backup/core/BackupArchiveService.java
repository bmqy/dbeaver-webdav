package net.bmqy.dbeaver.webdav.backup.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupArchiveService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String LEGACY_WORKSPACE_ARCHIVE_PREFIX = "workspace6";
    private static final Pattern MANIFEST_WORKSPACE_PATTERN = Pattern
        .compile("\"workspace\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    public BackupResult createArchive(BackupScope scope, Path tempDirectory) throws IOException {
        List<BackupEntry> entries = scope.collectEntries();
        if (entries.stream().noneMatch(entry -> isRestorableWorkspaceContent(entry.archivePath(), Set.of(BackupScope.WORKSPACE_ARCHIVE_PREFIX)))) {
            throw new IOException("当前工作区没有可备份的 DBeaver 项目内容，请确认项目和脚本已在 DBeaver 中可见后再备份。");
        }
        Files.createDirectories(tempDirectory);
        String remoteName = "dbeaver-backup-" + OffsetDateTime.now().format(FILE_TIME) + "-"
                + sanitize(System.getProperty("user.name", "user")) + ".zip";
        Path archive = tempDirectory.resolve(remoteName);
        long byteCount = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(createManifest(scope, entries).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (BackupEntry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.archivePath());
                zipEntry.setTime(Files.getLastModifiedTime(entry.source()).toMillis());
                zip.putNextEntry(zipEntry);
                byteCount += Files.copy(entry.source(), zip);
                zip.closeEntry();
            }
        }
        return new BackupResult(archive, remoteName, entries.size(), byteCount);
    }

    public void restoreArchive(Path archive, BackupScope scope) throws IOException {
        restoreArchive(archive, scope.workspaceRoot());
    }

    public static boolean hasRestorableWorkspaceContent(Path archive) throws IOException {
        Set<String> workspaceArchivePrefixes = workspaceArchivePrefixes(archive);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String archivePath = normalizeArchivePath(entry.getName());
                if (!isDirectoryEntry(entry, archivePath) && isRestorableWorkspaceContent(archivePath, workspaceArchivePrefixes)) {
                    return true;
                }
                zip.closeEntry();
            }
        }
        return false;
    }

    public void restoreArchive(Path archive, Path workspaceRoot) throws IOException {
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        Path dataRoot = resolveDbeaverDataRoot(workspaceRoot);
        Set<String> workspaceArchivePrefixes = workspaceArchivePrefixes(archive);
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
        } else if (archivePath.startsWith(BackupScope.DBEAVER_DATA_ARCHIVE_PREFIX + "/") && dataRoot != null) {
            String relative = archivePath.substring((BackupScope.DBEAVER_DATA_ARCHIVE_PREFIX + "/").length());
            target = resolveDbeaverDataTargetBase(relative, workspaceRoot, dataRoot).resolve(relative);
        } else {
            throw new IOException("Unsupported backup entry: " + archivePath);
        }
        Path normalized = target.toAbsolutePath().normalize();
        boolean inWorkspace = normalized.startsWith(workspaceRoot.toAbsolutePath().normalize());
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
        prefixes.add(BackupScope.WORKSPACE_ARCHIVE_PREFIX);
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
                            .flatMap(BackupArchiveService::lastPathSegment)
                            .filter(name -> !BackupScope.DBEAVER_DATA_ARCHIVE_PREFIX.equals(name));
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

    private static boolean isRestorableWorkspaceContent(String archivePath, Set<String> workspaceArchivePrefixes) {
        Optional<String> relative = workspaceRelativePath(normalizeArchivePath(archivePath), workspaceArchivePrefixes);
        if (relative.isEmpty()) {
            return false;
        }
        String path = relative.get();
        return !path.startsWith(".metadata/") && (path.contains("/Scripts/") || path.contains("/.dbeaver/"));
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

    private static String createManifest(BackupScope scope, List<BackupEntry> entries) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"formatVersion\": 1,\n");
        json.append("  \"createdAt\": \"").append(OffsetDateTime.now()).append("\",\n");
        json.append("  \"workspace\": \"").append(escape(scope.workspaceRoot().toString())).append("\",\n");
        json.append("  \"includesCredentials\": true,\n");
        json.append("  \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            BackupEntry entry = entries.get(i);
            json.append("    {\"path\": \"").append(escape(entry.archivePath())).append("\", ")
                    .append("\"size\": ").append(Files.size(entry.source())).append(", ")
                    .append("\"sha256\": \"").append(sha256(entry.source())).append("\"}");
            if (i + 1 < entries.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sanitize(String text) {
        return text.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
