# =============================================================================
# SyncEngine — sync-network-retrofit consumer ProGuard / R8 rules
#
# Automatically applied to every host app that depends on this module. They keep
# the public network-adapter API. Retrofit/OkHttp ship their own consumer rules
# via their AARs/JARs, so we only keep our own public surface here.
# =============================================================================

# Public API host apps construct and hold.
-keep public class io.github.prathamesh2640.sync.retrofit.RetrofitSyncAdapter { *; }
