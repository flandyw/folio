# AI page review, connections, and classification

**Settings → AI** manages all saved connections (ChatGPT sign-ins and API keys), usage counters, connection diagnostics, and the classification model. Inside a notebook, the sparkle button beside Share opens **Page review** directly (wide tablets show a live page preview beside controls; narrow screens stack vertically with a fixed footer).

## Page review

1. Choose a task: **Check working**, **Explain**, **Help me revise**, **Mark against criteria**, **Find misconceptions**, **Connect ideas**, **Build practice**, **Make flashcards**, **Plan next steps**, **Improve writing**, **Create study notes**, or **My own request** (up to 4,000 chars; optional context/rubric up to 3,000 chars).
2. Choose depth (**Quick** ≤4 notes, **Thorough** ≤8, **Deep** ≤12), optional **Hints only**, model, **Reasoning level**, and a **From page / To page** range (up to 10 consecutive pages including the current page). Nothing is sent by opening the panel or previewing.
3. Completed suggestions appear as editable, selectable sticky-note previews (rendering inline `\(…\)` / `$…$` and display `\[…\]` / `$$…$$` formulas via offline KaTeX). **Add N sticky notes** places all selected notes beside the current page in one undo step (empty selected notes block apply; target notebook/page revision and layer editability are re-checked).
4. **Follow-ups:** Ask a follow-up beneath the feedback to clarify, challenge a mark, or adjust difficulty. Sends the original page snapshots, task, edited feedback, and new question; replaces panel feedback without touching already-added notebook notes. Retries repeat the failed request verbatim.

**Check working** assigns an `x/y` mark only when the page shows available marks; rubric marking uses supplied allocations without inventing totals. Finite pages render their PDF background, visible layers, and page images (excluding outside stickies); infinite canvases render content bounds downscaled to ≤1600 px. Missing PDF/image resources fail the request rather than sending incomplete pages. Drafts and follow-ups live in memory across panel visits and rotation until replaced or cleared.

## ChatGPT sign-in & security

Follows the [official OSS OAuth registration flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in): browser PKCE + state + nonce with a temporary `127.0.0.1` listener (5-minute timeout), dynamic registration with a persistent installation ID, and RSA ID-token verification against discovered signing keys.

- Credentials encrypt with AES-GCM via Android Keystore in `noBackupFilesDir/chatgpt/` (isolated from Focal, Android Backup, notebooks, sync, and `.folio` files; zero bundled secrets).
- After browser callback, Folio waits up to 5 minutes until its Activity is visible before exchanging the code (avoiding background network restrictions). A completed exchange checkpoints encrypted credentials so **Retry account verification** resumes without re-running browser OAuth.
- **Disconnect** attempts remote revocation while keeping the reusable registration; **Remove saved sign-in** deletes the local registration and any pending checkpoint first, then attempts revocation.
- **Check connection** runs credential-free GET probes against OpenAI's discovery and model endpoints, reporting hostname reachability, network validation, metering, Data Saver, Private DNS, and VPN status.

## Reasoning, progress, and usage

- **Reasoning level:** Defaults to **Model default** (omits field); explicit levels send `reasoning: { effort: ... }` from `supported_reasoning_levels` (or documented GPT-6 fallbacks), remembered per model in memory.
- **Progress:** Streams real stages (*preparing*, *sending*, *thinking*, *writing*, *validating*) with elapsed time and a rough remaining range after the first completed review at the same model/effort (up to 10 min idle read / 15 min total; switches to *Taking longer than earlier reviews* if overdue).
- **Usage:** Settings → AI tracks per-account session request counts and reported tokens (`input`, `output`, `reasoning`, `cached input`; reasoning is never double-counted; missing usage shows as unavailable, never zero). Use **Manage usage** for actual plan allowance.

## API-key providers

Page review, import checks, and **Reclassify notebooks** also support **OpenRouter**, **NVIDIA NIM**, **AMD Token Factory**, **Vercel AI Gateway**, and **Cloudflare Workers AI** (32-char account ID + Workers AI Read/Edit token). Keys store in the same encrypted vault; removing a key only deletes it locally (revoke at the provider to invalidate elsewhere).

- `AiProvider` (pure) is the single provider table (URLs, key prefixes, checks, links, wording). `ChatGptManager.infer` routes ChatGPT through Responses API and key providers through OpenAI-compatible Chat Completions (`ChatCompletions.kt`).
- **Curated catalogs:** Drops batch-only, media-generator, expiring, and embedding/safety/caption IDs. OpenRouter opens on ~20 suggested routers/aliases (Auto default; search finds all ~470 entries, including `"free"`). Typing an unlisted model ID offers **Use model ID** (`AppPrefs.AI_CUSTOM_MODELS`).
- **Schema & errors:** Writes the JSON schema into the system prompt plus `response_format` when supported (retries once without it on HTTP 400/422), strips `<think>` blocks/fences, and runs identical local validation. Errors name the provider and fix without exposing raw provider bodies.

## Background import classification & Reclassify notebooks

- **Import checks (`BackupService.checkWithAi`):** When on-device classification (`ExamDetection.kt` + bundled `vcaa-exam-resources.json`) leaves uncertain fields and AI is connected, Folio sends text only (filename, metadata, first 2 pages up to 2,500 chars each + OCR if needed; never images) with strict `ExamAiClassification` schema. Results merge without overriding confident local fields and can land after import (`ExamAiClassification.afterImport`), notifying via channel *Document details* when backgrounded. Controlled by **Double-check with ChatGPT** (`AppPrefs.AI_IMPORT_CHECK`).
- **Reclassify notebooks (`Settings → Library → Reclassify notebooks`):** Re-runs classification across selected existing notebooks in application scope (`LibraryReclassifier`), reads cached front matter (`cacheDir/exam-evidence/<id>.json`), batches 6 notebooks per request with up to 3 concurrent requests, previews field-by-field diffs (defaulting to filling empty fields only; never clears fields or renames notebooks), and applies via `updateExamTags`.
- **Classification model:** Configurable independently (`AppPrefs.AI_CLASSIFY_MODEL` / `AI_CLASSIFY_EFFORT`, defaulting to Page review's model at its lightest effort). On AMD Token Factory, Cloudflare's **Clef / Clef-flash** decision models (`POST /v1/systemone`) answer choice questions directly in under a second (refused for Page review/Text test).

## Verification

- **Automated:** `node tools/chatgpt-smoke.cjs`, `node tools/import-smoke.cjs`, and `./build.sh -p`.
- **On device:**
  - Test browser OAuth, cancel, timeout, offline callback/checkpoint retry, Data Saver visibility wait, **Check connection**, token refresh, disconnect, and remove saved sign-in.
  - Review handwritten pages, PDFs with ink/images, and negative-coordinate canvases; verify hidden layers stay excluded, missing resources fail cleanly, stale page edits block apply, and `.folio` round-trips preserve added notes without leaking credentials.
  - Test multi-page rubric reviews, 12-note edit/deselect + single undo, hints-only mode, follow-ups, reasoning levels, usage counters, KaTeX rendering in cards/drafts, API-key providers (valid/invalid keys, vision vs text-only models, provider switching), background import checks, and **Reclassify notebooks**.

References: [reasoning](https://developers.openai.com/api/docs/guides/reasoning), [GPT-6 effort](https://developers.openai.com/api/docs/guides/deployment-checklist), [usage UX](https://developers.openai.com/siwc/ui-ux-guidelines), [Sign in with ChatGPT](https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt), [profiles & sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [models & inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview limits](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).
