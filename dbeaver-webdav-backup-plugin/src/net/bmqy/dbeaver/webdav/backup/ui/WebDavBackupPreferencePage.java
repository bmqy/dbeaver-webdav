package net.bmqy.dbeaver.webdav.backup.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import net.bmqy.dbeaver.webdav.backup.WebDavBackupPreferences;
import net.bmqy.dbeaver.webdav.backup.webdav.WebDavClient;

public class WebDavBackupPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private StringFieldEditor endpointEditor;
    private StringFieldEditor usernameEditor;
    private Text passwordText;

    public WebDavBackupPreferencePage() {
        super(GRID);
        setTitle("WebDAV 备份");
        PreferenceStore store = new PreferenceStore();
        store.setValue(WebDavBackupPreferences.PREF_ENDPOINT, WebDavBackupPreferences.getEndpoint());
        store.setValue(WebDavBackupPreferences.PREF_USERNAME, WebDavBackupPreferences.getUsername());
        store.setValue(WebDavBackupPreferences.PREF_REMOTE_DIRECTORY, WebDavBackupPreferences.getRemoteDirectory());
        store.setValue(WebDavBackupPreferences.PREF_RETENTION_COUNT, WebDavBackupPreferences.getRetentionCount());
        setPreferenceStore(store);
    }

    @Override
    public void init(IWorkbench workbench) {
    }

    @Override
    protected void createFieldEditors() {
        setDescription("为 DBeaver 项目、脚本和凭据配置 HTTPS WebDAV 备份。");
        endpointEditor = new StringFieldEditor(WebDavBackupPreferences.PREF_ENDPOINT, "WebDAV 地址：", getFieldEditorParent());
        addField(endpointEditor);
        usernameEditor = new StringFieldEditor(WebDavBackupPreferences.PREF_USERNAME, "用户名：", getFieldEditorParent());
        addField(usernameEditor);
        addField(new StringFieldEditor(WebDavBackupPreferences.PREF_REMOTE_DIRECTORY, "远程目录：", getFieldEditorParent()));
        IntegerFieldEditor retention = new IntegerFieldEditor(WebDavBackupPreferences.PREF_RETENTION_COUNT,
            "保留备份数量：", getFieldEditorParent());
        retention.setValidRange(1, 999);
        addField(retention);
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite outer = new Composite(parent, SWT.NONE);
        outer.setLayout(new GridLayout(1, false));
        outer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Control fields = super.createContents(outer);
        fields.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createPasswordSection(outer);
        createTestSection(outer);
        return outer;
    }

    private void createPasswordSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("身份验证");
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label label = new Label(group, SWT.NONE);
        label.setText("密码：");
        passwordText = new Text(group, SWT.BORDER | SWT.PASSWORD);
        passwordText.setText(WebDavBackupPreferences.getPassword());
        passwordText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createTestSection(Composite parent) {
        Button testButton = new Button(parent, SWT.PUSH);
        testButton.setText("测试连接");
        testButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                testConnection();
            }
        });
    }

    private void testConnection() {
        try {
            new WebDavClient(endpointEditor.getStringValue(), usernameEditor.getStringValue(), passwordText.getText()).test();
            MessageDialog.openInformation(getShell(), "WebDAV 备份", "连接成功。");
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "WebDAV 备份", "连接失败：" + e.getMessage());
        }
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        if (ok) {
            WebDavBackupPreferences.setEndpoint(getPreferenceStore().getString(WebDavBackupPreferences.PREF_ENDPOINT));
            WebDavBackupPreferences.setUsername(getPreferenceStore().getString(WebDavBackupPreferences.PREF_USERNAME));
            WebDavBackupPreferences.setRemoteDirectory(
                    getPreferenceStore().getString(WebDavBackupPreferences.PREF_REMOTE_DIRECTORY));
            WebDavBackupPreferences.setRetentionCount(
                    getPreferenceStore().getInt(WebDavBackupPreferences.PREF_RETENTION_COUNT));
            WebDavBackupPreferences.setPassword(passwordText.getText());
        }
        return ok;
    }
}
