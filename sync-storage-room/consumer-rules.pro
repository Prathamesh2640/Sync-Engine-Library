# =============================================================================
# SyncEngine — sync-storage-room consumer ProGuard / R8 rules
#
# Automatically applied to every host app that depends on this module. They keep
# the public storage API and let Room's generated code work under R8. Host
# @Entity classes implementing SyncableEntity keep their fields via the
# :sync-core consumer rules (bundled from that module's AAR).
# =============================================================================

# Public API host apps hold or extend
-keep public class io.github.prathamesh2640.sync.room.RoomSyncAdapter { *; }

# Room generates <Db>_Impl / <Dao>_Impl classes and accesses them reflectively.
# androidx.room ships its own consumer rules, but keep our generated Room code
# defensively so a host with aggressive shrinking cannot strip it.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
