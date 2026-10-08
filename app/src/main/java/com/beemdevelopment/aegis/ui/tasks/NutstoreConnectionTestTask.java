package com.beemdevelopment.aegis.ui.tasks;

import android.content.Context;

import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.WebDavClient;
import com.beemdevelopment.aegis.backup.WebDavException;

import java.util.Arrays;

/**
 * Verifies that the Nutstore directory is reachable and writable by uploading
 * a small probe file, reading it back and deleting it again.
 */
public class NutstoreConnectionTestTask
        extends ProgressDialogTask<NutstoreConnectionTestTask.Params, NutstoreConnectionTestTask.Result> {
    private final Callback _cb;

    public NutstoreConnectionTestTask(Context context, Callback cb) {
        super(context, context.getString(R.string.nutstore_connection_testing));
        _cb = cb;
    }

    @Override
    protected Result doInBackground(Params... params) {
        setPriority();

        Params p = params[0];
        WebDavClient client = null;
        try {
            client = new WebDavClient(p.getAccount(), p.getPassword(), p.getRootPath());
            return new Result(client.testConnection(), null);
        } catch (WebDavException e) {
            return new Result(null, e);
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

        public Params(String account, char[] password, String rootPath) {
            _account = account;
            _password = password;
            _rootPath = rootPath;
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

        private void clearPassword() {
            Arrays.fill(_password, '\0');
        }
    }

    public static class Result {
        private final String _warning;
        private final WebDavException _error;

        public Result(@Nullable String warning, @Nullable WebDavException error) {
            _warning = warning;
            _error = error;
        }

        @Nullable
        public String getWarning() {
            return _warning;
        }

        @Nullable
        public WebDavException getError() {
            return _error;
        }
    }
}
