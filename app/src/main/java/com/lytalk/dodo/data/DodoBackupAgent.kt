package com.lytalk.dodo.data

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor

class DodoBackupAgent : BackupAgent() {
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?,
    ) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        checkpoint()
        super.onFullBackup(data)
    }

    private fun checkpoint() {
        runCatching {
            val db = AppDatabase.get(this).openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(FULL)").close()
        }
    }
}
