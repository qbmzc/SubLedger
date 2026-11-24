package com.subledger.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/**
 * Result of an update session committed by {@link Updater#install}: opens the system's confirm
 * screen when it asks for the user, and reports failures. On success Android replaces the app.
 */
public final class UpdateStatus extends BroadcastReceiver {
    static final String ACTION = "com.subledger.mobile.UPDATE_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = confirmIntent(intent);
            if (confirm != null) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return;
        }
        String message = message(status);
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmIntent(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private static String message(int status) {
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                return null;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "已取消更新";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "更新被系统阻止";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "更新失败：与已安装的版本冲突（签名不一致）";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "更新失败：这个版本与设备不兼容";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "更新失败：安装包无效";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "更新失败：存储空间不足";
            default:
                return "更新失败";
        }
    }
}
