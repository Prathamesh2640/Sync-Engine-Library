# =============================================================================
# SyncEngine — sync-core consumer ProGuard / R8 rules
#
# These rules are automatically applied to every host app that depends on
# this module. They prevent R8 from renaming or removing the public API
# symbols that the library reflects on at runtime.
# =============================================================================

# Public interfaces — host apps implement these; R8 must not rename members
-keep public interface com.yourlibrary.sync.core.model.SyncableEntity { *; }

# SyncState enum — values() and valueOf() are used by Room and by the engine
-keep public enum com.yourlibrary.sync.core.model.SyncState {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# Host-app entity classes that implement SyncableEntity must keep their fields
# so Room column names survive minification
-keepclassmembers class * implements com.yourlibrary.sync.core.model.SyncableEntity {
    <fields>;
}

# -----------------------------------------------------------------------------
# Commit 4 — public API contracts (adapter / result / engine)
# -----------------------------------------------------------------------------

# Adapter contracts — host apps implement these; keep members so SAM/method
# signatures survive minification.
-keep public interface com.yourlibrary.sync.core.adapter.ConflictResolver { *; }
-keep public interface com.yourlibrary.sync.core.adapter.SyncNetworkAdapter { *; }

# NetworkResult sealed hierarchy — host apps branch over it in `when` and read
# its data-class payloads. Keep the base and every subclass (nested `$` types).
-keep public class com.yourlibrary.sync.core.adapter.NetworkResult { *; }
-keep public class com.yourlibrary.sync.core.adapter.NetworkResult$* { *; }

# Engine result/error sealed hierarchies.
-keep public class com.yourlibrary.sync.core.result.SyncResult { *; }
-keep public class com.yourlibrary.sync.core.result.SyncResult$* { *; }
-keep public class com.yourlibrary.sync.core.result.SyncError { *; }
-keep public class com.yourlibrary.sync.core.result.SyncError$* { *; }

# Engine entry point + configuration DSL.
-keep public interface com.yourlibrary.sync.core.engine.SyncEngine { *; }
-keep public class com.yourlibrary.sync.core.engine.SyncEngineConfig { *; }
-keep public class com.yourlibrary.sync.core.engine.SyncEngineConfig$Builder { *; }

# LogLevel enum — values()/valueOf() may be used for config parsing.
-keep public enum com.yourlibrary.sync.core.engine.LogLevel {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}
