package com.beemdevelopment.aegis.ui.tasks;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.NutstoreBackupStore;
import com.beemdevelopment.aegis.backup.WebDavClient;
import com.beemdevelopment.aegis.backup.WebDavException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lists the device directories below the configured root and the backup
 * versions inside the selected directory.
 */
public class NutstoreListTask
        extends ProgressDialogTask<NutstoreListTask.Params, NutstoreListTask.Result> {
    private static final Pattern DEVICE_DIR_PATTERN = Pattern.compile("^[0-9a-f]{16}$");

    private final Callback _cb;

    public NutstoreListTask(Context context, Callback cb) {
        super(context, context.getString(R.string.nutstore_listing));
        _cb = cb;
    }

    @Override
    protected Result doInBackground(Params... params) {
        setPriority();

        Params p = params[0];
        WebDavClient client = null;
        try {
            client = new WebDavClient(p.getAccount(), p.getPassword(), p.getRootPath());
            client.ensureRootDirectory();

            WebDavClient.ListResult root = client.list(p.getRootPath());
            List<String> devices = new ArrayList<>();
            for (WebDavClient.RemoteEntry entry : root.getEntries()) {
                if (entry.isDirectory() && DEVICE_DIR_PATTERN.matcher(entry.getName()).matches()
                        && !entry.getName().equals(p.getDeviceId())) {
                    devices.add(entry.getName());
                }
            }
            devices.sort(String::compareTo);
            if (p.getDeviceId() != null) {
                devices.remove(p.getDeviceId());
                devices.add(0, p.getDeviceId());
            }

            String selected = p.getSelectedDevice();
            if (selected == null || (!devices.contains(selected) && !devices.isEmpty())) {
                // The previously selected device is not part of this root
                // (for example after the directory was changed).
                selected = devices.isEmpty() ? null : devices.get(0);
            }

            List<WebDavClient.RemoteEntry> versions = new ArrayList<>();
            boolean complete = true;
            if (selected != null) {
                try {
                    WebDavClient.ListResult dir =
                            client.list(p.getRootPath() + "/" + selected);
                    complete = dir.isComplete();
                    for (WebDavClient.RemoteEntry entry : dir.getEntries()) {
                        if (!entry.isDirectory() && NutstoreBackupStore.MANAGED_FILE_PATTERN
                                .matcher(entry.getName()).matches()) {
                            versions.add(entry);
                        }
                    }
                } catch (WebDavException e) {
                    if (e.getReason() != WebDavException.Reason.NOT_FOUND) {
                        throw e;
                    }
                    // The device directory does not exist yet: no versions.
                }
            }

            versions.sort((a, b) -> b.getName().compareTo(a.getName()));
            return new Result(devices, versions, selected, complete, null);
        } catch (WebDavException e) {
            return new Result(null, null, null, false, e);
        } finally {
            if (client != null) {
                client.close();
            }
            p.clearPassword();
        }
    }

    @Override
    protected void onPostExecute(Result result) {
        super.onPostExecute(result);
        _cb.onTaskFinished(result);
    }

    public interface Callback {
        void onTaskFinished(Result result);
    }

    public static class Params {
        private final String _account;
        private final char[] _password;
        private final String _rootPath;
        private final String _deviceId;
        private final String _selectedDevice;

        public Params(String account, char[] password, String rootPath,
                      String deviceId, @Nullable String selectedDevice) {
            _account = account;
            _password = password;
            _rootPath = rootPath;
            _deviceId = deviceId;
            _selectedDevice = selectedDevice;
        }

        public String getAccount() {
            return _account;
        }

        public char[] getPassword() {
            return _password;
        }

        public String getRootPath() {
            return _rootPath;
        }

        public String getDeviceId() {
            return _deviceId;
        }

        @Nullable
        public String getSelectedDevice() {
            return _selectedDevice;
        }

        private void clearPassword() {
            Arrays.fill(_password, '\0');
        }
    }

    public static class Result {
        private final List<String> _devices;
        private final List<WebDavClient.RemoteEntry> _versions;
        private final String _selectedDevice;
        private final boolean _complete;
        private final WebDavException _error;

        public Result(@Nullable List<String> devices,
                      @Nullable List<WebDavClient.RemoteEntry> versions,
                      @Nullable String selectedDevice, boolean complete,
                      @Nullable WebDavException error) {
            _devices = devices;
            _versions = versions;
            _selectedDevice = selectedDevice;
            _complete = complete;
            _error = error;
        }

        @NonNull
        public List<String> getDevices() {
            return _devices == null ? new ArrayList<>() : _devices;
        }

        @NonNull
        public List<WebDavClient.RemoteEntry> getVersions() {
            return _versions == null ? new ArrayList<>() : _versions;
        }

        @Nullable
        public String getSelectedDevice() {
            return _selectedDevice;
        }

        public boolean isComplete() {
            return _complete;
        }

        @Nullable
        public WebDavException getError() {
            return _error;
        }
    }
}
