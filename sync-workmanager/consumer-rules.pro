# Consumer ProGuard rules for :sync-workmanager — bundled into the AAR so host
# apps keep the right symbols automatically.

# Public scheduler entry point.
-keep public class io.github.prathamesh2640.sync.workmanager.WorkManagerSyncScheduler { public *; }

# SyncWorker is instantiated reflectively by WorkManager through its
# (Context, WorkerParameters) constructor, so keep the class and that constructor
# even though the class itself is internal.
-keep class io.github.prathamesh2640.sync.workmanager.SyncWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
