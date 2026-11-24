# Mistake review pacing

Folio's Mistakes heading includes a **Review pacing** button; Focal exposes the same controls under **Mistakes → Schedule**. Voluntary practice from the Library remains available regardless of due dates.

## Scheduling controls

- **Defaults:** 1.5× successful-review multiplier (new **Good** = 5 days, **Easy** = 11 days), 24-hour **Again** retry (instead of 10 minutes), and a 180-day maximum interval.
- **Ranges:** 0.5–3× multiplier, 10-minute to 7-day retry, and 7–365-day maximum interval. **Frequent reviews** restores 1× and 10-minute retries; **Relaxed defaults** restores 1.5× and 24 hours.
- **Timing credit:** Late successful reviews earn modest elapsed-time credit; early practice receives no overdue credit. These are scheduling heuristics, not retention guarantees.

## Reduce upcoming reviews

Choose a window of 1–90 calendar days (including today) and a daily target. The preview shows how many due dates will shift.

- Applying postpones active questions due within the window, oldest first, filling each day's allowance while leaving suspended cards untouched.
- Overflow accounts for existing future bookings and spills past the window; a target of 0 takes a full break and spreads the return at 10 questions per day.
- Nothing moves earlier, and no review history is fabricated.
- During the lighter period, newly rated cards whose next due date lands inside the window also respect the daily allowance. The period expires automatically, or tap **End lighter period** to stop pacing new ratings early (already-postponed dates stay put).
- The target caps scheduled reviews, not voluntary practice.

Dates group by the device's local calendar, including daylight-saving transitions. Existing future dates and reviews from other clients count toward daily occupancy, and travelling across time zones maps shared timestamps into the new local calendar.

## Practice rewards and routine

Focal's Review screen and Folio's Today screen share identical practice progress:

- **XP and missions:** Each distinct card reviewed on a local calendar day earns 10 XP (including **Again**). Daily missions award 20 XP each for 5 distinct cards, 2 revisited difficult cards, and 3 distinct cards with confidence predictions. Repeating a card on the same day does not re-award card XP. Rewards and confidence never alter ratings or intervals.
- **Confidence and recall:** Locking in an optional **Guessing** / **Unsure** / **Confident** prediction before reveal stores `confidence` (`low`, `medium`, `high`). Calibration compares Guessing→Again, Unsure→Hard, and Confident→Good/Easy. Recall strength counts successful recall across up to 3 distinct calendar days (a later Again or Hard turns the card into a rematch). This is practice feedback, not a retention estimate.
- **Weekly challenge:** Picks up to 5 active cards practised before the current Monday using identical sorted IDs and week offsets in both clients (unlocks after the first week of practice). Challenge reviews store `challengeWeek` (local Monday `YYYY-MM-DD`) and `challengeSize` (1–5). Completing the full set earns 75 XP once per week regardless of rating. Progress and XP derive from deduplicated shared review history, so deleting history also removes derived rewards.
- **Routine and finishes:** `mistakeReviewPreferences` also stores `practiceDays` (ISO weekdays 1–7), `practiceTime` (local `HH:mm`), `practiceTarget` (1–20), optional `practiceSnoozedUntil`, `practiceEffects`, `practiceSound`, and `practiceFinish`. The routine is an in-app weekly window (not a background notification); skipping moves the next suggestion past that window without altering due dates. Daily mission goals stay fixed when session target changes. Finishes unlock at 0/100/300/600 XP and tint progress and confetti. Sound defaults to off, effects respect reduced motion, and there is no missed-day penalty. Update both clients so older writers do not strip the new fields.

## Shared state and compatibility

- **No database migration:** The generic `user_state` feed carries the `mistakeReviewPreferences` keyed row (`{value, updated_at}`) with `spacingMultiplier`, `retryHours`, `maximumIntervalDays`, optional `lighterUntil`, `lighterDailyTarget`, and `updatedAt`. Both clients clamp numeric settings before scheduling.
- **Sync and offline queue:** Focal publishes the row through its durable outbox and rebuilds its feed once on upgrade. Folio caches preferences per account, durably queues an idempotent settings mutation with row-sequence CAS retries, survives restarts offline, and replays the feed once (with a separate cursor for large replays) to pick up rows skipped by older clients.
- **Postponement safety:** Reduction updates normal `dueAt`/`updatedAt` fields via each client's existing queue. Folio tags date-only mutations so a concurrent remote review keeps its history, state, and interval, and a later remote due date wins. Older clients display shared rescheduled dates but still rate with their fixed algorithm until updated.

## Verification

- **Automated:** `./gradlew :app:testDebugUnitTest` covers defaults, clamping, history preservation, future occupancy, breaks, expiry, settings cache round-trips, stale settings CAS, and postponement rebasing. Focal runs `bun run check`, `bun run build`, and `bun run build:desktop`.
- **On device + signed-in Focal browser:** Save pacing in both directions; apply a lighter period over a busy week; rate a card into a full day; practise a postponed card early; end the period; edit offline, restart, and reconnect. Confirm history, handwritten practice pages, and future dates stay intact.
