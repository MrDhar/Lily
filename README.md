# Project Lily — SQL Editor

A compact dark, macOS-inspired SQLite SQL editor for Android with desktop-style database navigation, query tabs, result grids, imports/exports, and lightweight interactions.

## Current release

**v72 — SQL editor polish + custom import browser**

- Clear neutral active-database state with bold database name
- Long-press and compact mouse right-click database deletion
- macOS-inspired editor context menu and pointer treatment
- Current-statement execution button and caret statement indicator
- Ctrl/Cmd+Enter for current statement; Ctrl/Cmd+Shift+Enter for broader execution
- Responsive background query/import work
- Lightweight tab/sidebar transitions
- No zoom percentage toast
- Hardened SQL import for common MySQL, PostgreSQL, and SQL Server dump syntax
- Custom Lily import browser backed by Android Storage Access Framework permissions
- Single-step database deletion confirmation with Confirm Delete
- Protected Parks & Recreation database remains fully selectable

Build with the repository Android Gradle workflow.


## v73 performance pass
- Database schema discovery, table expansion, file-browser directory queries, table previews, and autocomplete DB lookups run off the UI thread.
- Result grids render only visible rows while keeping result data bounded.
- SQL syntax highlighting is debounced to keep typing responsive.
- Lightweight UI transitions remain short and interruptible; responsiveness takes priority.
