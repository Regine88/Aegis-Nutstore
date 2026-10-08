package com.beemdevelopment.aegis.backup;

import android.content.Context;

import com.beemdevelopment.aegis.R;

import java.text.DateFormat;
import java.util.Date;

/**
 * Maps the persisted backup state and classified errors to localized,
 * user-facing text. Kept in one place so that the settings screen and the
 * preference summary always agree.
 */
public final class NutstoreBackupStatus {
    private NutstoreBackupStatus() {

    }

    public static String describe(Context context, NutstoreBackupStore.State state,
                                  boolean configured) {
        if (!configured) {
            return context.getString(R.string.nutstore_status_unconfigured);
        }

        String message;
        switch (state.getStatus()) {
            case PENDING:
                message = context.getString(R.string.nutstore_status_pending);
                break;
            case UPLOADING:
                message = context.getString(R.string.nutstore_status_uploading);
                break;
            case SUCCESS:
                message = context.getString(R.string.nutstore_status_success,
                        formatTime(context, state.getLastSuccessAt()));
                break;
            case FAILED:
                message = context.getString(R.string.nutstore_status_failed,
                        failureText(context, state.getLastFailure()),
                        formatTime(context, state.getLastFailureAt()));
                break;
            case NONE:
            default:
                message = state.getLastSuccessAt() > 0
                        ? context.getString(R.string.nutstore_status_success,
                                formatTime(context, state.getLastSuccessAt()))
                        : context.getString(R.string.nutstore_status_idle);
                break;
        }

        if (state.getCleanupFailedAt() > 0) {
            message = message + "\n" + context.getString(R.string.nutstore_status_cleanup_failed);
        }
        return message;
    }

    public static String failureText(Context context, NutstoreBackupStore.FailureKind kind) {
        switch (kind) {
            case NETWORK:
                return context.getString(R.string.nutstore_error_network);
            case TIMEOUT:
                return context.getString(R.string.nutstore_error_timeout);
            case AUTHENTICATION:
                return context.getString(R.string.nutstore_error_authentication);
            case PERMISSION:
                return context.getString(R.string.nutstore_error_permission);
            case NOT_FOUND:
                return context.getString(R.string.nutstore_error_not_found);
            case CONFLICT:
                return context.getString(R.string.nutstore_error_conflict);
            case QUOTA:
                return context.getString(R.string.nutstore_error_quota);
            case RATE_LIMITED:
                return context.getString(R.string.nutstore_error_rate_limited);
            case SERVER:
                return context.getString(R.string.nutstore_error_server);
            case CONFIGURATION:
                return context.getString(R.string.nutstore_error_configuration);
            case CREDENTIALS:
                return context.getString(R.string.nutstore_error_credentials);
            case STORAGE:
                return context.getString(R.string.nutstore_error_storage);
            case SNAPSHOT:
                return context.getString(R.string.nutstore_error_snapshot);
            default:
                return context.getString(R.string.nutstore_error_unknown);
        }
    }

    public static String webDavErrorText(Context context, WebDavException e) {
        switch (e.getReason()) {
            case NETWORK:
                return context.getString(R.string.nutstore_error_network);
            case TIMEOUT:
                return context.getString(R.string.nutstore_error_timeout);
            case AUTHENTICATION:
                return context.getString(R.string.nutstore_error_authentication);
            case PERMISSION:
                return context.getString(R.string.nutstore_error_permission);
            case NOT_FOUND:
                return context.getString(R.string.nutstore_error_not_found);
            case CONFLICT:
                return context.getString(R.string.nutstore_error_conflict);
            case QUOTA:
                return context.getString(R.string.nutstore_error_quota);
            case RATE_LIMITED:
                return context.getString(R.string.nutstore_error_rate_limited);
            case SERVER:
                return context.getString(R.string.nutstore_error_server);
            case CONFIGURATION:
                return context.getString(R.string.nutstore_error_configuration);
            default:
                return context.getString(R.string.nutstore_error_unknown);
        }
    }

    private static String formatTime(Context context, long epochSeconds) {
        if (epochSeconds <= 0) {
            return context.getString(R.string.nutstore_status_never);
        }
        return DateFormat.getDateTimeInstance().format(new Date(epochSeconds * 1000));
    }
}
