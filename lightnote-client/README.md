# LightNote Client

Java 17 + JavaFX desktop client.

Current milestone:

- JavaFX three-column main window
- SQLite local database initialization
- Local note create, edit, soft delete
- Pinned and favorite flags
- Local search with SQLite FTS5 and LIKE fallback

## Build

```powershell
mvn package
```

## Run

```powershell
mvn javafx:run
```

Or from the repository root:

```powershell
pwsh .\scripts\start-client.ps1
```

Local data is stored in:

```text
%USERPROFILE%\.lightnote\lightnote.db
```
