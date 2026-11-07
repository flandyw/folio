# Library and notebook form polish

The empty, unfiltered Library offers Create a notebook and Import a PDF without
search, sort, selection or advanced hints ahead of the first action. This uses
library content rather than a saved onboarding flag: existing folders and scoped
views keep their normal navigation. Notebook pickers offer browsing and search
recovery, without create/import actions.

An empty scoped search offers Search all notebooks. It drops folder, tag, place,
kind and exam filters but retains the query. Clear search and Reset filters keep
the current location. Show all notebooks still clears the whole scope.

## Device checks

- Fresh library, portrait and landscape: the welcome actions are visible; create
  opens the notebook form, import opens the PDF picker. No account is required.
- Create a folder without notebooks: folder navigation remains available.
- Search in a folder with no match, then Search all notebooks: the term remains
  and matching notebooks elsewhere appear. Repeat in Favorites and with a tag,
  notebook type and exam filter active.
- Clear search inside a folder: stay in the folder. Reset filters: retain the
  query and folder. Back from search: clear the query before leaving the folder.
- Open the notebook chooser with an empty library: no dead-end create/import
  actions. Check narrow widths and large fonts for wrapping of recovery buttons.

`LibraryBrowseTest` covers the scope rules used by these recovery actions.

## Folder names

New folder, Rename folder and Create & move use the same name rules as the
catalog. Duplicate sibling names, missing parents and overlong names show an
inline explanation and disable submission, including keyboard Done. Pasted
names remain editable instead of being silently dropped or truncated. A failed
Create & move keeps the form open with a retry message.

- Try a duplicate with changed case and surrounding spaces; then correct it.
- Rename a folder without changing its name, and reuse a name in another parent.
- Paste more than 120 characters in a name dialog or Create & move; shorten it
  and submit with the keyboard. Confirm the complete corrected name is kept.
- Check duplicate-name feedback with the keyboard open in landscape: the reason
  should be visible inside the dialog, not only in a snackbar behind it.

`LibraryFolderNameTest` checks shared validation, rename exclusions and limits.
