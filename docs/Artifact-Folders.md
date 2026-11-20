# Artifact folders

A project's artifacts can be saved to a folder the keeper picks. Off until a folder is chosen.

## Where

"What it made" on the hive shows the folder row: **Choose a folder** / **Change folder**, **Save all**, **Stop saving**, and a **Save automatically** switch. Each artifact also has its own **Save** pill.

## What is written

One subfolder per run, `run-<n> <hive name>` (`run-<last 8 of the id>` if the run's number is not known). Files are named `<creature> - <label>.<ext>`; the extension follows the media type (markdown by default). An artifact with no text but a link is written as a short note naming the link. Nothing is ever overwritten: a taken name gets ` (2)`, ` (3)`.

## Rules

- The folder is per project and per device, kept in this device's settings, never in the project, an export or a `.ive` bundle.
- A repository is not needed.
- Saving as they are made covers the active run only; an artifact already written is not written twice. The keeper's own Save always writes, into a numbered file if need be.
- Desktop: a folder chooser; paths are checked to stay inside the chosen folder. Android: the system folder picker (persistent permission). Web: no folder service, so the controls do not appear.

## Code

`ArtifactFolders.kt` (settings, planner, saver, `ArtifactFolderHost`), `ArtifactFolderUi.kt`, `DesktopArtifactFolderService`, `AndroidArtifactFolderService`; the hook is `ApplicationRuntime.saveArtifactsAsMade`.
