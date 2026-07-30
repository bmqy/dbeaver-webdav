package net.bmqy.dbeaver.webdav.backup.core;

import java.nio.file.Path;

public record BackupResult(Path archive, String remoteName, int fileCount, long byteCount) {
}
