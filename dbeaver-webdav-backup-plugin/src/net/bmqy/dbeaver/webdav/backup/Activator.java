package net.bmqy.dbeaver.webdav.backup;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

public class Activator implements BundleActivator {

    public static final String PLUGIN_ID = "net.bmqy.dbeaver.webdav.backup";

    private static Activator instance;

    public static Activator getDefault() {
        return instance;
    }

    @Override
    public void start(BundleContext context) {
        instance = this;
        refreshWorkspaceAfterRestore();
    }

    @Override
    public void stop(BundleContext context) {
        instance = null;
    }

    private static void refreshWorkspaceAfterRestore() {
        Path marker = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
                .resolve(".metadata").resolve(".plugins").resolve(PLUGIN_ID).resolve("restore-refresh.marker");
        if (!Files.exists(marker)) {
            return;
        }
        Job job = new Job("刷新恢复的 DBeaver 工作区") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    Files.deleteIfExists(marker);
                    ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, monitor);
                    return Status.OK_STATUS;
                } catch (Exception e) {
                    return new Status(IStatus.ERROR, PLUGIN_ID, "刷新恢复的 DBeaver 工作区失败", e);
                }
            }
        };
        job.setSystem(true);
        job.schedule();
    }
}
