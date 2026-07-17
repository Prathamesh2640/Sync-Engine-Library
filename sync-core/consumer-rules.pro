# =============================================================================
# SyncEngine — sync-core consumer ProGuard / R8 rules
#
# These rules are automatically applied to every host app that depends on
# this module. They prevent R8 from renaming or removing the public API
# symbols that the library reflects on at runtime.
# =============================================================================

# Public interfaces — host apps implement these; R8 must not rename members
-keep public interface io.github.prathamesh2640.sync.core.model.SyncableEntity { *; }

# SyncState enum — values() and valueOf() are used by Room and by the engine
-keep public enum io.github.prathamesh2640.sync.core.model.SyncState {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# Host-app entity classes that implement SyncableEntity must keep their fields
# so Room column names survive minification
-keepclassmembers class * implements io.github.prathamesh2640.sync.core.model.SyncableEntity {
    <fields>;
}

# LogLevel enum — configuration value used from host code
-keep public enum io.github.prathamesh2640.sync.core.engine.LogLevel {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# Public interfaces host apps implement or hold
-keep public interface io.github.prathamesh2640.sync.core.adapter.ConflictResolver { *; }
-keep public interface io.github.prathamesh2640.sync.core.adapter.SyncNetworkAdapter { *; }
-keep public interface io.github.prathamesh2640.sync.core.engine.SyncEngine { *; }

# LocalSyncStore — the durable-storage seam host storage modules implement
# (e.g. RoomSyncAdapter). The engine reflects on nothing here, but keep the
# public contract so R8 does not rename members implementors override.
-keep public interface io.github.prathamesh2640.sync.core.store.LocalSyncStore { *; }

# SyncEngine.create(...) factory lives on the interface's companion; keep the
# generated static entry point and the companion so the DSL is reachable.
-keep class io.github.prathamesh2640.sync.core.engine.SyncEngine$Companion { *; }

# Engine configuration — Builder + DSL used from host code
-keep public class io.github.prathamesh2640.sync.core.engine.SyncEngineConfig { *; }
-keep public class io.github.prathamesh2640.sync.core.engine.SyncEngineConfig$Builder { *; }
-keep public class io.github.prathamesh2640.sync.core.engine.SyncEngineConfig$Companion { *; }

# Sealed result hierarchies returned across the public API — keep every branch
-keep public class io.github.prathamesh2640.sync.core.result.SyncResult { *; }
-keep public class io.github.prathamesh2640.sync.core.result.SyncResult$* { *; }
-keep public class io.github.prathamesh2640.sync.core.result.SyncError { *; }
-keep public class io.github.prathamesh2640.sync.core.result.SyncError$* { *; }
-keep public class io.github.prathamesh2640.sync.core.adapter.NetworkResult { *; }
-keep public class io.github.prathamesh2640.sync.core.adapter.NetworkResult$* { *; }

# Internal engine implementation (io.github.prathamesh2640.sync.core.internal.*)
# is deliberately NOT kept — R8 is free to shrink and obfuscate it.
