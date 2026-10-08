package com.beemdevelopment.aegis.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.NutstoreBackupException;
import com.beemdevelopment.aegis.backup.NutstoreBackupManager;
import com.beemdevelopment.aegis.backup.NutstoreBackupStatus;
import com.beemdevelopment.aegis.backup.NutstoreBackupStore;
import com.beemdevelopment.aegis.backup.NutstoreCredentialStore;
import com.beemdevelopment.aegis.backup.NutstoreCredentialsException;
import com.beemdevelopment.aegis.backup.WebDavClient;
import com.beemdevelopment.aegis.helpers.ViewHelper;
import com.beemdevelopment.aegis.importers.AegisImporter;
import com.beemdevelopment.aegis.importers.DatabaseImporter;
import com.beemdevelopment.aegis.ui.dialogs.Dialogs;
import com.beemdevelopment.aegis.ui.tasks.NutstoreDownloadTask;
import com.beemdevelopment.aegis.ui.tasks.NutstoreListTask;
import com.beemdevelopment.aegis.ui.tasks.NutstoreConnectionTestTask;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Configuration and status screen for Nutstore (Jianguoyun) backups.
 */
public class NutstoreBackupsActivity extends AegisActivity {
    public static final String EXTRA_RESTORE_ONLY = "restoreOnly";
    public static final String EXTRA_RESULT_FILE = "resultFile";

    private NutstoreCredentialStore _credentialStore;
    private NutstoreBackupManager _backupManager;
    private NutstoreCredentialStore.Config _config;

    private boolean _restoreOnly;
    private String _selectedDevice;

    private TextView _statusView;
    private TextView _warningView;
    private TextInputLayout _passwordLayout;
    private TextInputEditText _accountView;
    private TextInputEditText _passwordView;
    private TextInputEditText _rootPathView;
    private TextInputEditText _retentionView;
    private MaterialSwitch _autoBackupView;
    private TextView _autoBackupSummaryView;
    private View _backupNowView;
    private View _deviceRowView;
    private Spinner _deviceSpinner;
    private TextView _listWarningView;
    private TextView _versionsEmptyView;
    private ViewGroup _versionsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _restoreOnly = getIntent().getBooleanExtra(EXTRA_RESTORE_ONLY, false);
        if (!_restoreOnly && abortIfOrphan(savedInstanceState)) {
            return;
        }

        setContentView(R.layout.activity_nutstore_backups);
        setSupportActionBar(findViewById(R.id.toolbar));
        ViewHelper.setupAppBarInsets(findViewById(R.id.app_bar_layout));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        _credentialStore = new NutstoreCredentialStore(this);
        _backupManager = new NutstoreBackupManager(this);

        _statusView = findViewById(R.id.nutstore_status);
        _warningView = findViewById(R.id.nutstore_warning);
        _passwordLayout = findViewById(R.id.nutstore_app_password_layout);
        _accountView = findViewById(R.id.nutstore_account);
        _passwordView = findViewById(R.id.nutstore_app_password);
        _rootPathView = findViewById(R.id.nutstore_root_path);
        _retentionView = findViewById(R.id.nutstore_retention);
        _autoBackupView = findViewById(R.id.nutstore_auto_backup);
        _autoBackupSummaryView = findViewById(R.id.nutstore_auto_backup_summary);
        _backupNowView = findViewById(R.id.nutstore_backup_now);
        _deviceRowView = findViewById(R.id.nutstore_device_row);
        _deviceSpinner = findViewById(R.id.nutstore_device_spinner);
        _listWarningView = findViewById(R.id.nutstore_list_warning);
        _versionsEmptyView = findViewById(R.id.nutstore_versions_empty);
        _versionsView = findViewById(R.id.nutstore_versions);

        TextInputLayout retentionLayout = findViewById(R.id.nutstore_retention_layout);
        retentionLayout.setHelperText(getString(R.string.nutstore_retention_hint,
                NutstoreCredentialStore.MIN_RETENTION, NutstoreCredentialStore.MAX_RETENTION));
        TextInputLayout rootPathLayout = findViewById(R.id.nutstore_root_path_layout);
        rootPathLayout.setHelperText(getString(R.string.nutstore_root_path_hint));

        // Keep the application password away from autofill and instance state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setImportantForAutofill(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        _passwordView.setSaveEnabled(false);

        findViewById(R.id.nutstore_save).setOnClickListener(v -> saveConfiguration());
        findViewById(R.id.nutstore_test_connection).setOnClickListener(v -> testConnection());
        _backupNowView.setOnClickListener(v -> backupNow());
        findViewById(R.id.nutstore_revoke).setOnClickListener(v -> confirmRevoke());
        findViewById(R.id.nutstore_browse).setOnClickListener(v -> browseBackups());

        // Remove leftovers from an interrupted restore.
        File downloadDir = NutstoreDownloadTask.getDownloadDir(this);
        File[] leftovers = downloadDir.listFiles();
        if (leftovers != null) {
            for (File leftover : leftovers) {
                leftover.delete();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConfiguration();
        updateStatus();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadConfiguration() {
        boolean encrypted = isVaultEncrypted();
        if (_restoreOnly) {
            // A fresh install has no vault yet: uploads stay disabled.
            _warningView.setText(R.string.nutstore_restore_mode_hint);
            _warningView.setVisibility(View.VISIBLE);
            _autoBackupView.setVisibility(View.GONE);
            _autoBackupSummaryView.setVisibility(View.GONE);
            _backupNowView.setVisibility(View.GONE);
            findViewById(R.id.nutstore_revoke).setVisibility(View.GONE);
        } else {
            _warningView.setText(R.string.nutstore_encryption_required);
            _warningView.setVisibility(encrypted ? View.GONE : View.VISIBLE);
        }
        _autoBackupView.setEnabled(encrypted);

        try {
            _config = _credentialStore.loadConfig();
            _accountView.setText(_config.getAccount() == null ? "" : _config.getAccount());
            _rootPathView.setText(_config.getRootPath());
            _retentionView.setText(String.valueOf(_config.getRetention()));
            _passwordLayout.setHelperText(getString(_config.isPasswordStored()
                    ? R.string.nutstore_app_password_hint_saved
                    : R.string.nutstore_app_password_hint_empty));
            _autoBackupView.setChecked(_config.isAutoBackupEnabled() && encrypted && !_restoreOnly);
        } catch (NutstoreCredentialsException e) {
            _config = null;
            _accountView.setText("");
            _rootPathView.setText(NutstoreCredentialStore.DEFAULT_ROOT_PATH);
            _retentionView.setText(String.valueOf(NutstoreCredentialStore.DEFAULT_RETENTION));
            _passwordLayout.setHelperText(getString(R.string.nutstore_app_password_hint_empty));
            _autoBackupView.setChecked(false);
            _statusView.setText(R.string.nutstore_status_corrupted);
        }

        // The password is never written back into the form.
        _passwordView.setText("");

        boolean configured = _config != null && _config.getAccount() != null;
        _backupNowView.setEnabled(!_restoreOnly && encrypted && configured);
    }

    private void updateStatus() {
        if (_config == null) {
            return;
        }
        if (_config.getAccount() == null || !_config.isPasswordStored()) {
            _statusView.setText(R.string.nutstore_status_unconfigured);
            _backupNowView.setEnabled(false);
            return;
        }

        NutstoreBackupStore.State state;
        try {
            state = _backupManager.loadState();
        } catch (NutstoreBackupException e) {
            _statusView.setText(R.string.nutstore_error_storage);
            return;
        }

        _statusView.setText(NutstoreBackupStatus.describe(this, state, true));

        boolean configured = _config.getAccount() != null;
        _backupNowView.setEnabled(isVaultEncrypted() && configured);
    }

    private void saveConfiguration() {
        String account = textOf(_accountView);
        String rootPath = textOf(_rootPathView);
        String passwordText = textOf(_passwordView);
        int retention = parseRetention();
        boolean autoBackup = _autoBackupView.isChecked() && !_restoreOnly;
        boolean encrypted = isVaultEncrypted();

        if (autoBackup && !encrypted) {
            Toast.makeText(this, R.string.nutstore_encryption_required, Toast.LENGTH_LONG).show();
            return;
        }

        boolean passwordStored = _config != null && _config.isPasswordStored();
        if (autoBackup && (account.isEmpty() || (!passwordStored && passwordText.isEmpty()))) {
            Toast.makeText(this, R.string.nutstore_error_configuration, Toast.LENGTH_LONG).show();
            return;
        }

        // Changing the account, directory or password invalidates everything
        // that is still queued or in flight for the old destination.
        boolean destinationChanged = _config == null
                || !account.equals(_config.getAccount() == null ? "" : _config.getAccount())
                || !rootPath.equals(_config.getRootPath())
                || !passwordText.isEmpty();
        if (destinationChanged) {
            _backupManager.cancelWork();
        }

        char[] password = passwordText.isEmpty() ? null : passwordText.toCharArray();
        try {
            _config = _credentialStore.save(account.isEmpty() ? null : account, password,
                    rootPath, autoBackup, retention);
        } catch (NutstoreCredentialsException e) {
            Dialogs.showErrorDialog(this, R.string.nutstore_save_error, e);
            return;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }

        Toast.makeText(this, R.string.nutstore_saved, Toast.LENGTH_SHORT).show();

        try {
            if (autoBackup) {
                // Enabling the feature creates the first backup right away.
                _backupManager.requestBackup();
            } else {
                _backupManager.stopAndClearPending();
            }
        } catch (NutstoreCredentialsException | NutstoreBackupException e) {
            // The settings are saved; the status line shows the remaining problem.
        }

        loadConfiguration();
        updateStatus();
    }

    private void testConnection() {
        String account = textOf(_accountView);
        String rootPath = textOf(_rootPathView);
        char[] password = readPasswordForInput();
        if (password == null) {
            return;
        }

        if (account.isEmpty() || password.length == 0) {
            Arrays.fill(password, '\0');
            Toast.makeText(this, R.string.nutstore_error_configuration, Toast.LENGTH_LONG).show();
            return;
        }

        NutstoreConnectionTestTask task = new NutstoreConnectionTestTask(this, result -> {
            if (isFinishing()) {
                return;
            }
            if (result.getError() != null) {
                Toast.makeText(this, NutstoreBackupStatus.webDavErrorText(this, result.getError()),
                        Toast.LENGTH_LONG).show();
            } else if (result.getWarning() != null) {
                Toast.makeText(this, getString(R.string.nutstore_connection_warning,
                        result.getWarning()), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.nutstore_connection_success, Toast.LENGTH_LONG).show();
            }
        });
        task.execute(getLifecycle(), new NutstoreConnectionTestTask.Params(account, password, rootPath));
    }

    /**
     * Returns the application password entered in the form, or the stored one
     * when the field is left empty. Returns null after showing an error.
     */
    private char[] readPasswordForInput() {
        String passwordText = textOf(_passwordView);
        if (!passwordText.isEmpty()) {
            return passwordText.toCharArray();
        }
        try {
            return _credentialStore.readPassword();
        } catch (NutstoreCredentialsException e) {
            Toast.makeText(this, R.string.nutstore_error_credentials, Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void browseBackups() {
        char[] password = readPasswordForInput();
        if (password == null) {
            return;
        }

        String account = textOf(_accountView);
        if (account.isEmpty() || password.length == 0) {
            Arrays.fill(password, '\0');
            Toast.makeText(this, R.string.nutstore_error_configuration, Toast.LENGTH_LONG).show();
            return;
        }

        String rootPath = textOf(_rootPathView);
        String deviceId = _config == null ? null : _config.getDeviceId();
        NutstoreListTask task = new NutstoreListTask(this, result -> {
            if (isFinishing()) {
                return;
            }
            if (result.getError() != null) {
                Toast.makeText(this, NutstoreBackupStatus.webDavErrorText(this, result.getError()),
                        Toast.LENGTH_LONG).show();
                return;
            }
            _selectedDevice = result.getSelectedDevice();
            updateDeviceSpinner(result.getDevices());
            renderVersions(result);
        });
        task.execute(getLifecycle(), new NutstoreListTask.Params(account, password, rootPath,
                deviceId, _selectedDevice));
    }

    private void updateDeviceSpinner(List<String> devices) {
        if (devices.size() <= 1) {
            _deviceRowView.setVisibility(View.GONE);
            return;
        }

        _deviceRowView.setVisibility(View.VISIBLE);
        List<String> labels = new ArrayList<>(devices);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        _deviceSpinner.setAdapter(adapter);

        int index = _selectedDevice == null ? -1 : labels.indexOf(_selectedDevice);
        if (index >= 0) {
            _deviceSpinner.setSelection(index);
        }

        _deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String device = labels.get(position);
                if (!device.equals(_selectedDevice)) {
                    _selectedDevice = device;
                    browseBackups();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    private void renderVersions(NutstoreListTask.Result result) {
        _versionsView.removeAllViews();
        _listWarningView.setVisibility(result.isComplete() ? View.GONE : View.VISIBLE);

        List<WebDavClient.RemoteEntry> versions = result.getVersions();
        _versionsEmptyView.setVisibility(versions.isEmpty() ? View.VISIBLE : View.GONE);

        String device = result.getSelectedDevice() == null ? "" : result.getSelectedDevice();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (WebDavClient.RemoteEntry entry : versions) {
            View row = inflater.inflate(R.layout.nutstore_backup_item, _versionsView, false);
            TextView name = row.findViewById(R.id.nutstore_item_name);
            TextView details = row.findViewById(R.id.nutstore_item_details);
            name.setText(entry.getName());
            details.setText(getString(R.string.nutstore_version_details,
                    formatDate(entry.getLastModified()),
                    Formatter.formatShortFileSize(this, entry.getSize()),
                    device));
            row.setOnClickListener(v -> confirmRestore(entry));
            _versionsView.addView(row);
        }
    }

    private String formatDate(long epochSeconds) {
        if (epochSeconds <= 0) {
            return getString(R.string.nutstore_status_never);
        }
        return DateFormat.getDateTimeInstance().format(new Date(epochSeconds * 1000));
    }

    private void confirmRestore(WebDavClient.RemoteEntry entry) {
        Dialogs.showSecureDialog(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.nutstore_restore_confirm_title)
                .setMessage(getString(R.string.nutstore_restore_confirm_message, entry.getName()))
                .setPositiveButton(R.string.nutstore_restore,
                        (dialog, which) -> downloadAndRestore(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void downloadAndRestore(WebDavClient.RemoteEntry entry) {
        char[] password = readPasswordForInput();
        if (password == null) {
            return;
        }

        String account = textOf(_accountView);
        if (account.isEmpty() || password.length == 0) {
            Arrays.fill(password, '\0');
            Toast.makeText(this, R.string.nutstore_error_configuration, Toast.LENGTH_LONG).show();
            return;
        }

        NutstoreDownloadTask task = new NutstoreDownloadTask(this, result -> {
            if (isFinishing()) {
                return;
            }
            File file = result.getFile();
            if (file != null) {
                onBackupDownloaded(file);
            } else if (result.getWebDavError() != null) {
                Toast.makeText(this, NutstoreBackupStatus.webDavErrorText(this, result.getWebDavError()),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.nutstore_error_invalid_backup, Toast.LENGTH_LONG).show();
            }
        });
        task.execute(getLifecycle(), new NutstoreDownloadTask.Params(account, password,
                textOf(_rootPathView), entry.getPath(), entry.getName()));
    }

    private void onBackupDownloaded(File file) {
        if (_restoreOnly) {
            // The welcome screen takes ownership of the downloaded file.
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_FILE, file.getAbsolutePath()));
            finish();
            return;
        }

        DatabaseImporter.Definition definition = null;
        for (DatabaseImporter.Definition candidate : DatabaseImporter.getImporters(false)) {
            if (candidate.getType() == AegisImporter.class) {
                definition = candidate;
                break;
            }
        }
        if (definition == null) {
            Toast.makeText(this, R.string.nutstore_error_invalid_backup, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, ImportEntriesActivity.class);
        intent.putExtra("importerDef", definition);
        intent.putExtra("file", file);
        startActivity(intent);
    }

    private void backupNow() {
        if (!isVaultEncrypted()) {
            Toast.makeText(this, R.string.nutstore_encryption_required, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            _backupManager.requestBackup();
            Toast.makeText(this, R.string.nutstore_backup_queued, Toast.LENGTH_SHORT).show();
            updateStatus();
        } catch (NutstoreCredentialsException e) {
            Toast.makeText(this, NutstoreBackupStatus.failureText(this,
                    NutstoreBackupStore.FailureKind.CREDENTIALS), Toast.LENGTH_LONG).show();
        } catch (NutstoreBackupException e) {
            Toast.makeText(this, NutstoreBackupStatus.failureText(this,
                    NutstoreBackupStore.FailureKind.STORAGE), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmRevoke() {
        Dialogs.showSecureDialog(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.nutstore_revoke_confirm_title)
                .setMessage(R.string.nutstore_revoke_confirm_message)
                .setPositiveButton(R.string.nutstore_revoke,
                        (dialog, which) -> revokeAuthorization())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private void revokeAuthorization() {
        try {
            _backupManager.stopAndClearPending();
            _credentialStore.clearCredentials();
            Toast.makeText(this, R.string.nutstore_revoked, Toast.LENGTH_SHORT).show();
        } catch (NutstoreBackupException | NutstoreCredentialsException e) {
            Dialogs.showErrorDialog(this, R.string.nutstore_save_error, e);
        }

        loadConfiguration();
        updateStatus();
    }

    private boolean isVaultEncrypted() {
        return _vaultManager.isVaultLoaded() && _vaultManager.getVault().isEncryptionEnabled();
    }

    private int parseRetention() {
        try {
            int value = Integer.parseInt(textOf(_retentionView));
            return Math.max(NutstoreCredentialStore.MIN_RETENTION,
                    Math.min(NutstoreCredentialStore.MAX_RETENTION, value));
        } catch (NumberFormatException e) {
            return NutstoreCredentialStore.DEFAULT_RETENTION;
        }
    }

    private static String textOf(TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }
}
