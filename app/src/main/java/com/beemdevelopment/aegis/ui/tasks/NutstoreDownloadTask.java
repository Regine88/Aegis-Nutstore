package com.beemdevelopment.aegis.ui.tasks;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.WebDavClient;
import com.beemdevelopment.aegis.backup.WebDavException;
import com.beemdevelopment.aegis.importers.AegisImporter;
import com.beemdevelopment.aegis.importers.DatabaseImporter;
import com.beemdevelopment.aegis.importers.DatabaseImporterException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Downloads a single backup version to a private temporary file and validates
 * that it is a standard, encrypted Aegis export before it is handed to the
 * existing import flow.
 */
public class NutstoreDownloadTask
        extends ProgressDialogTask<NutstoreDownloadTask.Params, NutstoreDownloadTask.Result> {
    public static final String DIR_NAME = "nutstore-restore";

    private final Callback _cb;

    public NutstoreDownloadTask(Context context, Callback cb) {
        super(context, context.getString(R.string.nutstore_downloading));
        _cb = cb;
    }

    @NonNull
    public static File getDownloadDir(Context context) {
        return new File(context.getCacheDir(), DIR_NAME);
    }

    @Override
    protected Result doInBackground(Params... params) {
        setPriority();

        Params p = params[0];
        Context context = getDialog().getContext();
        WebDavClient client = null;
        try {
            client = new WebDavClient(p.getAccount(), p.getPassword(), p.getRootPath());
            byte[] bytes = client.download(p.getRemotePath(), WebDavClient.MAX_DOWNLOAD_BYTES);

            DatabaseImporter.State state;
            try {
                state = new AegisImporter(context).read(new ByteArrayInputStream(bytes), false);
            } catch (DatabaseImporterException e) {
                return new Result(null, null, e);
            }
            if (!state.isEncrypted()) {
                return new Result(null, null,
                        new DatabaseImporterException("The backup is not encrypted"));
            }

            File dir = getDownloadDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                return new Result(null, null,
                        new IOException("Unable to create the restore directory"));
            }

            String fileName = p.getFileName().replaceAll("[^A-Za-z0-9._-]", "_");
            File file = new File(dir, fileName);
            try (FileOutputStream outStream = new FileOutputStream(file)) {
                outStream.write(bytes);
            }
            return new Result(file, null, null);
        } catch (WebDavException e) {
            return new Result(null, e, null);
        } catch (IOException e) {
            return new Result(null, null, e);
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
        private final String _remotePath;
        private final String _fileName;

        public Params(String account, char[] password, String rootPath,
                      String remotePath, String fileName) {
            _account = account;
            _password = password;
            _rootPath = rootPath;
            _remotePath = remotePath;
            _fileName = fileName;
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

        public String getRemotePath() {
            return _remotePath;
        }

        public String getFileName() {
            return _fileName;
        }

        private void clearPassword() {
            Arrays.fill(_password, '\0');
        }
    }

    public static class Result {
        private final File _file;
        private final WebDavException _webDavError;
        private final Exception _error;

        public Result(@Nullable File file, @Nullable WebDavException webDavError,
                      @Nullable Exception error) {
            _file = file;
            _webDavError = webDavError;
            _error = error;
        }

        @Nullable
        public File getFile() {
            return _file;
        }

        @Nullable
        public WebDavException getWebDavError() {
            return _webDavError;
        }

        @Nullable
        public Exception getError() {
            return _error;
        }
    }
}
