# Consumer ProGuard rules for :sync-ui-dashboard — a debug-only module, but keep
# its public entry points so a minified debug build resolves them.

# Debug activity launched from the host's debug menu (declared in the manifest,
# so it is instantiated reflectively by the framework).
-keep public class io.github.prathamesh2640.sync.ui.SyncDashboardActivity { public *; }

# Host-facing wiring point and state model.
-keep public class io.github.prathamesh2640.sync.ui.SyncDashboard { public *; }
-keep public class io.github.prathamesh2640.sync.ui.SyncDashboardState { *; }
