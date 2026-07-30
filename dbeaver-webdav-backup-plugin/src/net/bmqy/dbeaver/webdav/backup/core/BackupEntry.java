package net.bmqy.dbeaver.webdav.backup.core;

import java.nio.file.Path;

public record BackupEntry(Path source, String archivePath) {
}
