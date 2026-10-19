# Mistake responses

The review's **Your response** area offers **Write** and **Type**. Write remains the default; an explicit choice is remembered on this device. An unfinished typed answer reopens in Type. Switching modes keeps both the handwriting and the typed response.

Type is a multiline answer field with normal keyboard selection, paste and editing. It saves each text change through `FolioViewModel.commitText` and the existing serialized document writer. Comparison dismisses the keyboard, reveals the correction and leaves the response available beside it. Rating waits for the writer; a failed save exposes Retry save and prevents rating. Space, S and number shortcuts do not trigger review actions while the answer field has focus.

On narrow windows the question folds down while the software keyboard is open, and the confidence chips leave room for Done typing and Compare answer. Expanded windows keep the question beside the response. Very short windows temporarily hide the response toolbar and question pane to preserve an editable area. Done typing restores the normal layout. Merely opening a card in Type does not summon the keyboard; choosing Type or tapping the field does.

Preview renders formatted text and offline maths through the same rich-text renderer as the question. Sharing a typed-only answer sends the question and answer as text. Mixed working retains the existing picture/clipboard sharing path. Previous attempts show typed answers as readable text, alongside any handwritten working. The device storage tab is labelled Responses and includes both modes.

There is no notebook or sync format change. Each review's answer is an ordinary `TextBox` on its existing practice page, identified by `mistake-response-<reviewId>`. A new response sits to the right of existing working and uses an editable layer; existing placement, style and layers are preserved. Drafts and completed working stay on this device and travel in notebook backups, just like handwriting. Only ratings use the existing Focal review sync.

## Verification

`MistakeTypedResponseTest` checks mixed ink/text snapshot round-trip and undo, independent review identities, canvas edits, layer choice, and retention of typed-only attempts. Existing scheduling and writer tests cover rating persistence and save failures.

On a device, test a narrow portrait window, landscape with a software keyboard, a wide tablet and split screen. Type several paragraphs, move the caret, paste, compose accented text, and scroll to the last line. Type spaces, S and 1–4 before and after revealing: they must stay in the answer. Switch Write → Type → Write, skip and resume, leave and return, rotate, restart, then rate immediately after an edit. Confirm both kinds of working survive in previous attempts and a backup round-trip. Check math preview, typed and mixed sharing, and Retry save after a storage failure. Test large system fonts and TalkBack, including the response switch and answer field.
