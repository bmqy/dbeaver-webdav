package net.bmqy.dbeaver.webdav.backup.job;

import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import net.bmqy.dbeaver.webdav.backup.Activator;
import net.bmqy.dbeaver.webdav.backup.WebDavBackupPreferences;
import net.bmqy.dbeaver.webdav.backup.core.BackupArchiveService;
import net.bmqy.dbeaver.webdav.backup.core.BackupResult;
import net.bmqy.dbeaver.webdav.backup.core.BackupScope;
import net.bmqy.dbeaver.webdav.backup.webdav.WebDavClient;

public final class BackupJob extends Job {

    public BackupJob() {
        super("DBeaver WebDAV 备份");
        setUser(true);
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        monitor.beginTask("正在备份 DBeaver 工作区到 WebDAV", 4);
        try {
            WebDavClient client = new WebDavClient(WebDavBackupPreferences.getEndpoint(),
                    WebDavBackupPreferences.getUsername(), WebDavBackupPreferences.getPassword());
            monitor.worked(1);

            BackupScope scope = BackupScope.current();
            BackupResult result = new BackupArchiveService().createArchive(scope,
                    Files.createTempDirectory("dbeaver-webdav-backup-"));
            monitor.worked(1);

            String remoteDirectory = WebDavBackupPreferences.getRemoteDirectory();
            client.ensureDirectory(remoteDirectory);
            monitor.worked(1);

            client.upload(result.archive(), remoteDirectory + "/" + result.remoteName());
            pruneOldBackups(client, remoteDirectory, WebDavBackupPreferences.getRetentionCount());
            Files.deleteIfExists(result.archive());
            monitor.worked(1);

            return Status.info("已上传 " + result.remoteName() + "，共 " + result.fileCount() + " 个文件。");
        } catch (Exception e) {
            return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "DBeaver WebDAV 备份失败", e);
        } finally {
            monitor.done();
        }
    }

    private static void pruneOldBackups(WebDavClient client, String remoteDirectory, int retentionCount) throws Exception {
        List<String> backups = client.listBackupFiles(remoteDirectory);
        int deleteCount = backups.size() - retentionCount;
        for (int i = 0; i < deleteCount; i++) {
            client.delete(remoteDirectory + "/" + backups.get(i));
        }
    }
}
