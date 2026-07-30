package net.bmqy.dbeaver.webdav.backup.job;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import net.bmqy.dbeaver.webdav.backup.Activator;
import net.bmqy.dbeaver.webdav.backup.WebDavBackupPreferences;
import net.bmqy.dbeaver.webdav.backup.core.BackupArchiveService;
import net.bmqy.dbeaver.webdav.backup.core.BackupScope;
import net.bmqy.dbeaver.webdav.backup.core.RestoreArchiveCommand;
import net.bmqy.dbeaver.webdav.backup.webdav.WebDavClient;

public final class RestoreJob extends Job {

    private final String remoteName;

    public RestoreJob(String remoteName) {
        super("DBeaver WebDAV 恢复");
        this.remoteName = remoteName;
        setUser(true);
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        monitor.beginTask("正在从 WebDAV 恢复 DBeaver 工作区", 3);
        try {
            WebDavClient client = new WebDavClient(WebDavBackupPreferences.getEndpoint(),
                    WebDavBackupPreferences.getUsername(), WebDavBackupPreferences.getPassword());
            monitor.worked(1);

            byte[] data = client.download(WebDavBackupPreferences.getRemoteDirectory() + "/" + remoteName);
            Path archive = Files.createTempFile("dbeaver-webdav-restore-", ".zip");
            Files.write(archive, data);
            if (!BackupArchiveService.hasRestorableWorkspaceContent(archive)) {
                Files.deleteIfExists(archive);
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                        "所选备份不包含可恢复的项目内容。请换一份包含 Scripts 或 .dbeaver 项目数据的旧备份。");
            }
            monitor.worked(1);

            Path workspaceRoot = BackupScope.current().workspaceRoot();
            startExternalRestore(archive, workspaceRoot);
            monitor.worked(1);

            return Status.info("已准备恢复 " + remoteName + "。请关闭 DBeaver，工作区文件将在进程完全退出后覆盖。");
        } catch (Exception e) {
            return new Status(IStatus.ERROR, Activator.PLUGIN_ID, "DBeaver WebDAV 恢复失败", e);
        } finally {
            monitor.done();
        }
    }

    private static void startExternalRestore(Path archive, Path workspaceRoot) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Path classPath = resolveBundleClassPath();
        Path log = archive.resolveSibling(archive.getFileName() + ".log");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(classPath.toString());
        command.add(RestoreArchiveCommand.class.getName());
        command.add(archive.toString());
        command.add(workspaceRoot.toString());
        command.add(Long.toString(ProcessHandle.current().pid()));
        new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private static Path resolveBundleClassPath() throws Exception {
        Bundle bundle = FrameworkUtil.getBundle(RestoreJob.class);
        if (bundle == null) {
            throw new IllegalStateException("Cannot resolve plugin bundle location");
        }
        File bundleFile = FileLocator.getBundleFile(bundle);
        if (bundleFile != null) {
            return bundleFile.toPath().toAbsolutePath().normalize();
        }
        String location = bundle.getLocation();
        int referenceIndex = location.indexOf("reference:");
        if (referenceIndex >= 0) {
            location = location.substring(referenceIndex + "reference:".length());
        }
        if (location.startsWith("reference:")) {
            location = location.substring("reference:".length());
        }
        if (location.startsWith("file:")) {
            return Path.of(java.net.URI.create(hierarchicalFileUri(location))).toAbsolutePath().normalize();
        }
        return Path.of(location).toAbsolutePath().normalize();
    }

    private static String hierarchicalFileUri(String location) {
        if (location.startsWith("file:/")) {
            return location;
        }
        return Path.of(location.substring("file:".length())).toUri().toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
