package net.bmqy.dbeaver.webdav.backup.ui;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import net.bmqy.dbeaver.webdav.backup.WebDavBackupPreferences;
import net.bmqy.dbeaver.webdav.backup.job.RestoreJob;
import net.bmqy.dbeaver.webdav.backup.webdav.WebDavClient;

public class RestoreBackupHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        if (WebDavBackupPreferences.getEndpoint().isBlank()) {
            MessageDialog.openWarning(shell, "WebDAV 备份", "请先配置 WebDAV 地址，然后再恢复备份。");
            return null;
        }
        Job job = new Job("读取 WebDAV 备份列表") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask("正在读取 WebDAV 备份列表", IProgressMonitor.UNKNOWN);
                try {
                    WebDavClient client = new WebDavClient(WebDavBackupPreferences.getEndpoint(),
                            WebDavBackupPreferences.getUsername(), WebDavBackupPreferences.getPassword());
                    List<String> backups = client.listBackupFiles(WebDavBackupPreferences.getRemoteDirectory());
                    Display.getDefault().asyncExec(() -> openRestoreDialog(shell, backups));
                    return Status.OK_STATUS;
                } catch (Exception e) {
                    Display.getDefault().asyncExec(() -> {
                        if (shell == null || shell.isDisposed()) {
                            return;
                        }
                        MessageDialog.openError(shell, "WebDAV 备份", "读取备份列表失败：" + e.getMessage());
                    });
                    return Status.CANCEL_STATUS;
                } finally {
                    monitor.done();
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    private static void openRestoreDialog(Shell shell, List<String> backups) {
        if (shell == null || shell.isDisposed()) {
            return;
        }
        if (backups.isEmpty()) {
            MessageDialog.openInformation(shell, "恢复 WebDAV 备份", "远程目录中没有可恢复的备份文件。");
            return;
        }
        BackupSelectionDialog dialog = new BackupSelectionDialog(shell, backups);
        if (dialog.open() != Window.OK || dialog.getSelectedBackup() == null) {
            return;
        }
        String remoteName = dialog.getSelectedBackup();
        boolean confirmed = MessageDialog.openQuestion(shell, "覆盖 DBeaver 工作区",
            "恢复会在 DBeaver 完全退出后替换当前工作区中匹配的项目、脚本、偏好配置、工作区状态和凭据文件。是否继续？");
        if (confirmed) {
            RestoreJob job = new RestoreJob(remoteName);
            job.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    if (event.getResult().matches(IStatus.ERROR | IStatus.CANCEL)) {
                        Display.getDefault().asyncExec(() -> showRestoreError(event.getResult()));
                        return;
                    }
                    Display.getDefault().asyncExec(RestoreBackupHandler::promptRestart);
                }
            });
            job.schedule();
        }
    }

    private static void showRestoreError(IStatus status) {
        Shell shell = Display.getDefault().getActiveShell();
        String message = status.getMessage();
        if (status.getException() != null && status.getException().getMessage() != null) {
            message = message + "：" + status.getException().getMessage();
        }
        MessageDialog.openError(shell, "恢复 WebDAV 备份", message);
    }

    private static void promptRestart() {
        Shell shell = Display.getDefault().getActiveShell();
        MessageDialog dialog = new MessageDialog(shell, "恢复 WebDAV 备份", null,
                "恢复已准备完成。请退出 DBeaver，等待几秒后再手动打开，以便在软件完全退出后覆盖并加载恢复的工作区文件。", MessageDialog.INFORMATION,
                new String[] { "退出 DBeaver", "稍后退出" }, 0);
        if (dialog.open() == 0) {
            PlatformUI.getWorkbench().close();
        }
    }

    private static final class BackupSelectionDialog extends Dialog {

        private final List<String> backups;
        private org.eclipse.swt.widgets.List backupList;
        private String selectedBackup;

        private BackupSelectionDialog(Shell parentShell, List<String> backups) {
            super(parentShell);
            this.backups = backups;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("恢复 WebDAV 备份");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            org.eclipse.swt.widgets.Label label = new org.eclipse.swt.widgets.Label(area, SWT.NONE);
            label.setText("选择要恢复的备份文件：");

            backupList = new org.eclipse.swt.widgets.List(area, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
            backupList.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            backupList.setItems(backups.toArray(String[]::new));
            backupList.select(0);
            return area;
        }

        @Override
        protected void okPressed() {
            String[] selection = backupList.getSelection();
            selectedBackup = selection.length == 0 ? null : selection[0];
            super.okPressed();
        }

        private String getSelectedBackup() {
            return selectedBackup;
        }
    }
}
