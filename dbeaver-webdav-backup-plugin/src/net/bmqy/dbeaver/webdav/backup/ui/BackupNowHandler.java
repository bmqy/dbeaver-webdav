package net.bmqy.dbeaver.webdav.backup.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import net.bmqy.dbeaver.webdav.backup.WebDavBackupPreferences;
import net.bmqy.dbeaver.webdav.backup.job.BackupJob;

public class BackupNowHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        if (WebDavBackupPreferences.getEndpoint().isBlank()) {
            MessageDialog.openWarning(HandlerUtil.getActiveShell(event), "WebDAV 备份",
                    "请先配置 WebDAV 地址，然后再开始备份。");
            return null;
        }
        new BackupJob().schedule();
        return null;
    }
}
