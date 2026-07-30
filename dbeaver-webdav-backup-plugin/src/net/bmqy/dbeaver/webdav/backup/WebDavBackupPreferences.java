package net.bmqy.dbeaver.webdav.backup;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;

public final class WebDavBackupPreferences {

    public static final String NODE = Activator.PLUGIN_ID;
    public static final String PREF_ENDPOINT = "endpoint";
    public static final String PREF_USERNAME = "username";
    public static final String PREF_REMOTE_DIRECTORY = "remoteDirectory";
    public static final String PREF_RETENTION_COUNT = "retentionCount";

    private static final String SECURE_PASSWORD = "password";

    private WebDavBackupPreferences() {
    }

    public static String getEndpoint() {
        return node().get(PREF_ENDPOINT, "");
    }

    public static void setEndpoint(String endpoint) {
        node().put(PREF_ENDPOINT, endpoint == null ? "" : endpoint.trim());
        flush();
    }

    public static String getUsername() {
        return node().get(PREF_USERNAME, "");
    }

    public static void setUsername(String username) {
        node().put(PREF_USERNAME, username == null ? "" : username.trim());
        flush();
    }

    public static String getRemoteDirectory() {
        return node().get(PREF_REMOTE_DIRECTORY, "dbeaver-backups");
    }

    public static void setRemoteDirectory(String remoteDirectory) {
        String value = remoteDirectory == null || remoteDirectory.isBlank() ? "dbeaver-backups" : remoteDirectory.trim();
        node().put(PREF_REMOTE_DIRECTORY, value);
        flush();
    }

    public static int getRetentionCount() {
        return Math.max(1, node().getInt(PREF_RETENTION_COUNT, 10));
    }

    public static void setRetentionCount(int retentionCount) {
        node().putInt(PREF_RETENTION_COUNT, Math.max(1, retentionCount));
        flush();
    }

    public static String getPassword() {
        try {
            return secureNode().get(SECURE_PASSWORD, "");
        } catch (StorageException e) {
            return "";
        }
    }

    public static void setPassword(String password) {
        try {
            secureNode().put(SECURE_PASSWORD, password == null ? "" : password, true);
            secureNode().flush();
        } catch (StorageException | java.io.IOException e) {
            throw new IllegalStateException("Failed to store WebDAV password", e);
        }
    }

    private static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    private static ISecurePreferences secureNode() {
        return SecurePreferencesFactory.getDefault().node(NODE);
    }

    private static void flush() {
        try {
            node().flush();
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Failed to store WebDAV backup preferences", e);
        }
    }
}
