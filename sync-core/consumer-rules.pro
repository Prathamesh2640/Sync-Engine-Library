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
