# Backups

Folio now backs up the native notebook files rather than expanding every stroke into JSON. Single-notebook `.folio` exports and portable library `.folio-backup.zip` files are self-contained. They include notebook metadata, folders for library backups, binary page snapshots and journals, undo/redo history, placed images, and imported source PDFs. Existing JSON `.folio` archives, v1 nested library backups, and v2 deduplicated JSON library backups still import. New v3 files require a Folio version that supports native backups.

## Exclusions

Use a notebook’s **Exclude from library backups** menu item, select several notebooks for the same action, or open **Settings → Library & notebooks → Backup exclusions → Manage exclusions** to search and choose textbooks or other notebooks to leave out. Excluded notebooks are omitted entirely, including their source PDF, annotations, images, and metadata, from both automatic and portable library backups. Shared data still needed by included notebooks remains. They stay available on the device, and a deliberate single-notebook `.folio` export still includes the selected notebook in full.

Choices are local preferences keyed by notebook ID, so renaming/moving a notebook keeps its exclusion and a duplicate inherits it. New/imported/restored notebooks start included. Exclusion changes schedule an automatic backup check and apply to the next captured snapshot. Already captured backups and older restore points may still contain the notebook; exclusion does not delete historical backups. Automatic garbage collection releases objects only after no retained restore point needs them.

## Automatic backups

Choose a folder under Settings → Library & notebooks. Folio checks after saved changes and daily. Its `Folio automatic backup` subfolder contains timestamped `.folio-backup.zip` restore points and a `Folio backup data` directory. Each restore point is a small manifest; unchanged pages, PDFs, and images share the same content-addressed objects. A changed journal is uploaded as a new object; this is file-level incremental backup, not a transaction-level remote journal. A check with no changed file content creates no new restore point or object. The displayed time is the last successful check.

The most recent two readable restore points are retained. Objects are written, closed, and checked against the provider’s reported file lengths before the new restore point is published. Restore-point ordering stays monotonic if the device clock moves backwards. Folio reads that descriptor back before pruning older points. Garbage collection accounts for every remaining descriptor, including a point the provider refused to delete. An unreadable descriptor prevents object garbage collection. Interrupted, uncommitted objects are rewritten before reuse. A failed new backup leaves the previous points in place.

Use **Restore from backup folder** on another device and choose either the original selected folder or its `Folio automatic backup` subfolder. This grants access to both the newest readable restore point and its shared data. Keep the entire automatic backup folder together when copying it. An individual automatic restore-point ZIP requires its data folder; use a portable library backup when you need a single file. Selecting an older automatic ZIP with **Restore library backup** also works when Folio already has a read grant for its folder.

Restoring creates fresh notebook and folder identities alongside the existing library. Page IDs, ink, undo/redo stacks, exam metadata, peek anchors, and review attempts are preserved; practice notebook references are remapped to the new identity. All object hashes and notebook/page contents are checked before installation. A malformed backup does not partially add notebooks during an ordinary failure or cancellation.

## Format and implementation

The v3 `manifest.json` uses `format: folio-library` and `version: 3`. Each notebook maps strictly validated native relative paths to SHA-256 hashes. The object table records raw byte counts and compression choices. Portable archives put raw objects under `objects/<sha256>`; ink/index/journal entries use ZIP deflate at level 1, while PDF/image entries are stored without recompression. Automatic objects are `<sha256>.fbo`, encoded as gzip at level 1 for structured data or raw bytes for PDFs/images; an external descriptor also records each encoded object's length. The ZIP container never recompresses a gzip object.

There is no change to `NoteMetaCodec`, `PageSnapshotBinary`, `JournalBinary`, their version numbers, sequence handling, or history encoding. Snapshot and journal files are captured as a pair; the snapshot sequence still determines which journal records are replayed. Torn journal tails retain the existing recovery semantics. Undo-only image assets are kept too.

Under the app storage gate and repository lock, backup staging pins files published by atomic replacement with hard links, falling back to copying where links are unavailable. Appendable journals are copied. Image saves now publish atomically so pinning cannot observe in-place changes. Hashing/checksumming, compression, and SAF writes run outside the editor save gate. SHA-256, ZIP CRC, and native snapshot checksum validation are calculated in one bounded streaming pass, without loading PDF/image bytes or all ink pages into memory. Automatic compression stages only newly needed objects, one at a time. Native restore validates one page at a time and installs the existing binary files directly, sharing immutable inodes where possible and giving each journal its own synced file. The remapped index is written last; each notebook is published by directory rename. Private staging left after process death is cleaned on the next repository load/backup, and unpublished restore directories never appear in the shelf.

Readers bound manifests (16 MiB), notebooks (10,000), file/object references (250,000), each object (512 MiB), and total raw/restored data (32 GiB). The existing lower metadata/PDF/image limits still apply. Unknown versions, unsafe paths, duplicate entries, missing objects/assets, wrong lengths, and checksum failures are rejected. Automatic jobs are serialized; external restore reads and object pruning share a separate mutex.

## Verification

Run:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:prepareBackupSmoke
node tools/backup-smoke.cjs
node tools/katex-smoke.cjs
node tools/writing-follow-smoke.cjs
```

The backup smoke runner uses the compiled production codecs with JVM `org.json`. The verification-only Gradle configuration does not add JSON to the APK. It covers exclusion selection and the explicit single-notebook override, preservation of shared assets, raw binary round trips, journal sequence filtering and a torn tail, undo/redo folding, asset/page deduplication, external gzip/raw objects, legacy v1/v2 and JSON notebook decoding, size bounds, corrupt data, missing payloads, and invalid paths/versions. It also reports size and timing for a synthetic 160,000-point handwriting sample. This compares v2 JSON encoding/compression against v3 hashing/compression of already persisted native files on the JVM; it is not an Android/SAF performance guarantee.

Before release, check on a device:

1. Export and restore a notebook and a library containing dense ink, typed text, images, an imported PDF, empty PDF pages, infinite canvases, folders, a peek anchor, exam attempts, and mistake practice/reviews. Compare contents, revisions, cover indices, page order, metadata, and PDF/image rendering. Confirm undo/redo works after restore and again after restarting Folio, including an image present only in undo history. Check that editing either restored copy does not change another copy or the original.
2. Restore an older JSON `.folio`, a v1 library ZIP, and a v2 library ZIP. Back up a notebook with unopened legacy JSON pages and verify migration and restored ink/history. Restore the same file twice and confirm fresh identities and existing notebooks/folders remain intact.
3. Select a local SAF folder. Trigger the first automatic backup, trigger it again unchanged, edit one page, and trigger another backup. Confirm unchanged checks create no new point, only changed files get new data objects, and two readable points survive. Add/remove notebooks, images, and folders, then check object pruning and both retained restore points.
4. Repeat with a cloud documents provider and slow destination. Continue writing during automatic hashing/upload; saves must continue after capture. Stop the job/kill the process while copying an object, publishing a point, and installing a notebook; restart and verify old backups and saved notes, cleanup/retry, and absence of unpublished notebooks. Test provider failures, unknown file sizes, refused deletion, a backwards clock change, and revoked folder access.
5. On a second device/install, choose **Restore from backup folder** without enabling automatic backups. Check root and subfolder selection. Also select an older point using a granted folder. Truncate/remove/corrupt an object or descriptor and confirm restore refuses damaged content without adding notebooks. Test a failed newer descriptor and restore the previous readable point.

6. Exclude an imported textbook in its menu and in the settings list. Restart Folio and verify the setting and shelf label persist after renaming/moving it. Save automatic and portable library backups and confirm the textbook is absent, other notebooks restore, shared PDFs/assets needed elsewhere remain, and the saved notebook count is correct. Export the excluded textbook individually and restore its complete PDF/annotations/history. Test bulk include/exclude, duplication, re-including a notebook, and excluding all notebooks. Check that older retained points remain usable and may still contain the excluded textbook.

## Progress in Folio

Backup & restore settings shows when a requested automatic backup is queued by Android, then the active preparation, file checking, compression/copying, verification and cleanup stages. Counts distinguish reused files from newly copied files; copying a large file also reports its encoded bytes. Progress lives in the process rather than preferences, and clears when a running job finishes, fails or stops.

Saving a portable library backup reports pending saves, notebook preparation/capture, file checks, file counts and streamed source bytes, followed by archive finalization. Source bytes are uncompressed and may exceed the final ZIP size. This flow uses the same native v3 binary snapshot/journal writer as notebook exports.

On a device, watch both flows with a large PDF and dense ink, then repeat an unchanged automatic backup. Confirm counts and stages update, controls cannot start overlapping requests, completion returns to the last-checked timestamp, and failures/stopped jobs clear active progress.

## Explorer organization

Backup manifests preserve each folder’s optional `parent` reference; notebook indexes preserve optional user `tags`. Restore assigns new IDs to all folders and remaps parent references and notebook destinations together. Existing backups without these fields remain readable. See [Explorer verification](explorer.md#verification) for the nested-folder/tag device round-trip.
