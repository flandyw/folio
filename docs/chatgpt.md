# Experimental ChatGPT connection and page feedback

Settings → Account & updates → Page review & account opens the **Account** tab.
In a notebook, the sparkle button beside Share opens **Page review** directly.
The workspace follows Folio's theme and shape/spacing tokens, puts a real page
preview beside the controls on wide tablets, and uses a scrolling column on narrow
windows or above the keyboard. Progress and the primary action stay in a fixed footer.

Connect once, then choose **Check working**, **Explain**, **Help me revise** or
**My own request**. Add optional question/context, choose an available model and
inspect the current page, then choose a **From page** / **To page** range before
choosing **Review pages**. The range defaults to the current page and supports
up to ten consecutive pages, including the current page. All selected pages are
sent together in notebook order with their page numbers, so ChatGPT can follow
working across pages. Feedback stays on the current page and identifies other
pages by number. The preview shows the current page. Preview and upload share the
same renderer and content rules; only the display resolution differs. No page
content is sent merely by opening the panel or looking at a preview.

Completed suggestions appear as sticky-note previews. Select only what you want
and edit any wording before choosing **Add N sticky notes**. All selected notes
are added beside the original page in one undo step. Empty selected notes and a
selection of zero notes cannot be applied. The result stays available across
panel visits and rotation, including selected/edited drafts, until you choose
**Start over** or **Review again**, or a new review completes. A failed/cancelled
request, model refresh, account change or connection check does not discard the
last completed review. This is session state; it does not survive process death.

The Account tab groups saved accounts, plan status and usage management separately
from page review. **Connection tools** contains the model picker, text test and
DNS/network diagnostics. A model catalog is loaded once on each panel visit when
needed, with explicit retries after failures. Account and diagnostic operations
remain serialized with reviews. Errors offer a contextual retry plus expandable,
selectable details without exposing credentials or raw provider bodies.

This prototype anchors the combined feedback to the current page as region R1. It does not claim to locate
individual words or equations or invent a marking rubric. **Check working** gives an `x/y` mark only when the page shows the marks available or a clear total; otherwise it gives no mark.
Finite pages include their PDF background, visible layers and page images;
outside-page stickies remain excluded, as in ordinary page exports. An infinite
canvas uses its visible content bounds plus padding, downscaled to at most 1600
pixels on the longest side. Very large canvases may become illegible; review a
smaller page until selection/crop support is added. Missing PDF/image resources
fail the request rather than silently sending incomplete material.

The connection follows the [official OSS registration flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in):
browser OAuth with PKCE, state, nonce and a temporary listener on 127.0.0.1;
dynamic registration uses Folio's name and a persistent installation host ID.
Each issued client ID is retained separately after disconnect; removing a saved sign-in deletes its local registration. ID
tokens are checked against discovered RSA signing keys, issuer, audience, nonce,
expiry and the registration's original subject. Credentials are AES-GCM encrypted
with an Android Keystore key in `noBackupFilesDir/chatgpt/`, separate from Focal,
Android backup, notebook storage, sync and `.folio` files. No API key, secret or
token is shipped in the APK. Requests go directly to the public Responses API.

The app-scoped manager survives Activity recreation. Requests are serialized;
refresh tokens are replaced atomically, and cancellation closes network calls.
Browser sign-in times out after five minutes and its local listener then closes.
After browser sign-in, return to Folio manually. Process death during sign-in
requires a fresh attempt; pending authorization codes and PKCE state are not
persisted. Tokens and provider response bodies are never logged or displayed.
Disconnect attempts remote revocation and explains if it was not confirmed.

After receiving the loopback callback, Folio waits until its Activity is visible
before exchanging the code. This avoids exchanging it while browser sign-in leaves
Folio subject to Android's background network restrictions. The wait has a five-minute
timeout and is cancellable. Activity recreation updates the app-scoped visibility
gate; closing the panel does not discard the sign-in operation.

A saved registration is labelled **sign-in unfinished** until identity verification
finishes. Retrying sign-in reuses that registration. A completed code exchange is
checkpointed in encrypted storage before retrieving signing keys; **Retry account
verification** resumes that step without consuming another authorization code.
The checkpoint cannot authorize inference until its signature, claims and original
nonce are checked. Verification uses the checkpoint's receipt time, so an outage
does not invalidate an otherwise valid received identity; expired access credentials
are refreshed before use. Disconnect removes the checkpoint too.

Errors identify the failed step and a safe network category (DNS, TLS, timeout,
connection, or protocol). Local credential-write failures have their own message.
The HTTP client permits address fallback before sending; OAuth and inference
request bodies prohibit automatic replay after sending. Connection checks are
available before sign-in; the text test requires plan permission and a model.
Identity-only consent is retained and can request plan permission separately.

**Check connection** works before sign-in and makes credential-free GET requests
to OpenAI's discovery and model endpoints. It reports each service hostname's
connectivity, active-network validation, metering, Data Saver, Private DNS and VPN
status. A 401 from the model endpoint is expected without an access token and shows
the server was reached. The check does not run inference or consume model usage;
it never changes the device's DNS settings or sends traffic to another resolver.

Responses use `store: false`, `stream: true`, explicit instructions and an input
array. Model choices come from the selected account. Page review requests use a
strict JSON schema and are validated again locally: exact request ID, version,
known region, annotation type, comment count and text length. No page edits occur
on a partial, failed or malformed response. Applying requires that the same
notebook/page is still open with identical content and metadata and an editable
layer. Comments are ordinary `TextBox` stickies: move/edit/delete/export/undo
behavior is unchanged. Outside-page notes do not appear in ordinary page PDFs.

## Reasoning, progress, usage and math

The model selector now has a **Reasoning level** control. **Model default** omits
the request field; explicit choices send `reasoning: { effort: ... }`. Available
levels come from `supported_reasoning_levels` when the catalog supplies it.
Documented GPT-6 models have a limited fallback (Sol 6.1 and Astra: low through
max; Sol 6 and Luna also allow none). Unknown models without capability metadata
keep the model default. Choices are remembered per model during the app session.
Higher levels can take longer and consume more plan usage.

Reviews show preparation, sending, processing/thinking, writing and validation
stages, plus elapsed time. Stages advance only on actual local work or SSE events;
there is no fabricated completion percentage. The first review at a setting has
no finish-time estimate. Later reviews show a rough remaining range based on the
last five successful reviews with the same model and reasoning setting in this
app session. An overdue estimate changes to **Taking longer than earlier reviews**,
never negative time or a false completed state. Inference allows a 10-minute idle
read and a 15-minute overall request; discovery, token exchange and other account
operations retain their shorter timeouts. Cancel stops the request, while closing
the panel keeps it running in the app-scoped manager.

The Account tab shows per-account completed-request counts and token usage
reported by `response.completed`, including input, output, reasoning and cached
input when provided. Reasoning is included in output, so is never added twice to
the total. Missing/invalid usage is unavailable, never shown as zero; failed or
canceled requests are excluded and may still consume usage. These are Folio
session counters, not subscription balance or a billing record. The documented
Sign in with ChatGPT flow does not provide a remaining-allowance endpoint; use
**Manage usage** for plan/app allowance and resets. No private ChatGPT backend
endpoints or API organization usage credentials are used.

Every unfinished/disconnected saved sign-in has a **Remove saved sign-in** button
in its account row. Cancel an active sign-in first if it is still running. Local
removal is saved before attempting remote revocation, and removes pending
verification credentials too. Revocation checks both existing and pending grants,
using a refresh token when available or access token otherwise. A working account
still offers **Disconnect**, which retains its reusable registration.

The review instructions tell the model that sticky notes support offline KaTeX
LaTeX. They request short inline `\(…\)` and display `\[…\]` formulas, correct
JSON backslash escaping, and ordinary prose without Markdown/code fences. The
800-character bound includes math source. Feedback cards render inline and display
formulas through the shared offline KaTeX document renderer, using the same delimiter
splitter as notebook sticky notes (also supporting `$…$` and `$$…$$`). Plain prose
stays native text. Draft editing retains the exact stored LaTeX source, and the
notebook sticky-note renderer handles the formulas after adding the feedback.

## Verification

Run `node tools/chatgpt-smoke.cjs` for standalone JVM protocol checks and
`./build.sh -p` for the canonical signed experimental build/publish.

Device checks (an eligible user account is needed; not automated):

1. Connect using the browser, return to Folio and complete a text test. Cancel
   sign-in, deny permission and leave the browser open for five minutes; each
   path must stop cleanly. Rotate Folio during sign-in and a request.
   Interrupt connectivity after the callback: distinguish code exchange from
   signing-key retrieval, retry the saved registration, and retry verification
   without browser sign-in when the encrypted checkpoint is present.
   With Data Saver enabled on a metered network, confirm code exchange waits until
   Folio is visible. Run Check connection before sign-in and verify both hostname
   results; compare Private DNS/VPN and Wi-Fi versus hotspot when DNS fails.
2. Restart Folio, refresh the model list and run another text test. Test renewal
   after token expiry, switching registrations and reconnecting a disconnected
   registration. Disconnect offline and confirm the remote-revocation notice.
3. Review a handwritten page, a PDF with ink and pictures, and an infinite canvas
   with negative coordinates. Check that hidden layers are not sent. Try a
   missing PDF/image resource and a usage-limit/network failure: no notes should
   be available to apply from that request.
4. Review a page, then change its ink, paper or dimensions, or switch pages:
   adding feedback must be disabled. Review again, add all comments, undo once
   and redo once. Check notes do not overlap existing content and cannot be
   added twice. Try applying on a locked layer.
5. Reopen the notebook and round-trip a `.folio` backup: comments should survive
   with their usual undo history, while ChatGPT connections do not transfer.
6. On a wide tablet, check the page/controls split; rotate to a narrow window and
   open the keyboard. The primary action must remain reachable and both tabs must
   scroll without clipped controls. Check dark/light themes and increased text size.
   Inspect the preview, then send a review: it must match the PDF/images/layers
   shown in the preview. Opening the workspace or preview must send no page content.
7. Edit a suggestion, deselect another, close and reopen the panel, then rotate:
   edited wording and selection should remain. Add only the selected notes and
   verify the exact wording and single undo entry. Empty selected notes must block
   applying; deselecting an empty note should unblock it. Switching accounts, a
   failed/cancelled review or running diagnostics must retain completed drafts.

8. Remove an unfinished registration before token exchange, and a pending
   verification checkpoint after exchange. Both disappear and remain removed after
   restart. Disconnect a working account with a pending re-sign-in; both grants
   must be cleared. Repeat offline and cancel during remote revocation.
9. Review the same page with low and high reasoning. Check streaming stage labels,
   elapsed time, cancel, rotation and reopening the panel while a request runs.
   The first review has no estimate; a second with the same setting has a range;
   another model/effort must not reuse that estimate. A long review must not claim
   completion when its estimate expires.
10. Check usage after a text test and review, switch accounts and check counters
    remain separate. When the server omits usage, display unavailable; failed and
    canceled streams must not create a completed count or applicable notes. Open
    Manage usage. Review a math page, add a note containing a fraction and display
    formula, and verify KaTeX rendering in the feedback cards before and after
    adding notes. Edit a draft: its LaTeX source should be visible in the field,
    and finishing the edit should render the updated formula. Check source
    editing/undo on the notebook too, narrow windows, larger text and deselected
    cards in both themes; formulas should fit and match each card's ink colour.

Sources: [reasoning](https://developers.openai.com/api/docs/guides/reasoning),
[GPT-6 effort compatibility](https://developers.openai.com/api/docs/guides/deployment-checklist),
[usage UX](https://developers.openai.com/siwc/ui-ux-guidelines),
[cookbook](https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt),
[accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions),
[inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference),
[preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

## Study assistant modes

Alongside Check working, Explain and Help me revise, the review supports:
Mark against criteria, Find misconceptions, Connect ideas, Build practice,
Make flashcards, Plan next steps, Improve writing and Create study notes.
Supply the question, rubric, level or learning goal in the context field (up to
3,000 characters), or write a custom request (up to 4,000 characters).
Rubric marking uses supplied allocations; it does not invent a numerical total.
Practice asks for self-contained questions and separate numbered answer notes.

Choose Quick (up to four notes), Thorough (eight) or Deep (twelve). Hints only
asks the model to withhold answers and provide progressive prompts instead.
These are model instructions; inspect the output before using it for practice.
All notes remain individually editable and selectable, with Select all and
Deselect all controls. Adding up to twelve notes is still one undo step.

Ask a follow-up beneath the feedback to request clarification, challenge a mark,
or change the practice difficulty. It sends the original reviewed page snapshots,
the original task, the latest edited feedback (including deselected notes) and
the new question. Each response replaces the panel's previous feedback; notes
already added remain in the notebook. Retries repeat the actual failed request,
including its follow-up, rather than the current form. Follow-up context stays
in memory only, across panel visits and rotation; this is not a durable chat log.
Device checks: review a multi-page answer against a rubric; generate 12 notes,
edit/deselect and add them, then undo; request hints-only practice; ask a follow-up,
cancel/retry it and confirm previous feedback remains until completion; change
the current page and confirm the old review cannot write into the changed page.
