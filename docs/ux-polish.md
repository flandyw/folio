# Library and notebook form polish

## Empty library and scoped search

An empty, unfiltered Library leads directly with **Create a notebook** and **Import a PDF**, hiding search, sort, multi-select, and advanced hints until content exists. This checks actual library content rather than a saved onboarding flag, so existing folders and scoped views keep normal navigation. Notebook pickers focus on browsing and search recovery without create/import dead ends.

An empty scoped search offers **Search all notebooks**, dropping folder, tag, place, kind, and exam filters while keeping the query. **Clear search** and **Reset filters** keep your current location; **Show all notebooks** clears the entire scope. Covered by `LibraryBrowseTest`.

### Device checks

- Fresh Library (portrait and landscape): confirm welcome actions appear and work without an account.
- Create an empty folder: confirm folder navigation stays available.
- Search inside a folder, Favourites, tag, notebook type, or exam filter with no hits, then tap **Search all notebooks**: confirm the query stays and matches elsewhere appear.
- Confirm **Clear search** stays in the folder, **Reset filters** keeps query and folder, and Back clears the query before leaving the folder.
- Open a notebook chooser on an empty library and check narrow widths and large fonts for clean button wrapping.

## Folder names

**New folder**, **Rename folder**, and **Create & move** share the catalogue's validation rules (`LibraryFolderNameTest`). Duplicate sibling names, missing parents, and names over 120 characters display an inline error and disable submission (including keyboard Done). Pasted names stay editable rather than being silently truncated, and a failed **Create & move** stays open with a retry message.

### Device checks

- Test a duplicate sibling name with different casing and extra spaces, then fix it.
- Rename a folder to its current name, and reuse a name under a different parent.
- Paste >120 characters, shorten it, submit via keyboard, and confirm the full corrected name saves.
- Verify duplicate-name errors stay visible inside the dialog with the landscape keyboard open.

## Notebook drafts

Dismissing the new-notebook form via Back, Close, or Cancel prompts before discarding edits to name, template, pages, paper, or cover. Untouched forms (or forms reverted to initial values, or with only cover options expanded) close immediately. **Keep editing** preserves all choices across rotation, and **Create** submits directly without prompting.

## Status and follow-up

- `:app:testDebugUnitTest` passes (verified with `-Pkotlin.compiler.execution.strategy=in-process`).
- Manual device checks above remain to be verified (especially landscape keyboard, large text, and TalkBack).
- **Follow-up:** Show destination name conflicts inline when moving an existing folder into a parent that already has a folder with that name (creation and renaming show inline errors; moving still uses the shared error).
