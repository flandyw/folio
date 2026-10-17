# Mistake review pacing

Folio's Mistakes heading has a **Review pacing** button. Focal exposes the same controls under Mistakes → Schedule. Practice from the Library remains available regardless of a suggested due date.

The default successful-review multiplier is 1.5: a new Good rating suggests five days and Easy suggests eleven. Again retries after 24 hours rather than ten minutes. The maximum interval is 180 days. Users can choose a 0.5–3× successful-review multiplier, a retry from ten minutes to seven days, and a maximum interval of 7–365 days. Frequent reviews restores 1× and ten-minute retries; relaxed defaults restores 1.5× and 24 hours. Late successful practice receives modest elapsed-time credit; early practice receives no overdue credit. These are scheduling heuristics, not a claim of a particular retention percentage.

**Reduce upcoming reviews** chooses 1–90 calendar days (today included) and a daily target. The preview counts dates that will change. Applying postpones active questions due before the end of the period, oldest first, filling each day's allowance. Suspended cards stay untouched. Overflow counts existing future bookings and is spread beyond the period; a zero target takes a break and spreads the return at ten questions per day. Nothing is moved earlier and no review history is fabricated. During the period, newly rated questions whose next date would fall inside it also respect the allowance. The period expires automatically; **End lighter period** stops pacing new ratings early. Dates already postponed stay at their new dates. The target limits scheduled questions, not how many times the user may voluntarily practise.

Dates are grouped by the device's local calendar, including daylight-saving changes. Existing future dates and questions reviewed in the other client continue to count when assigning a new date. A user travelling across time zones sees their shared timestamps in the new local calendar.

## Shared state and compatibility

### Practice rewards and routine

Focal's Review screen and Folio's Today screen show the same practice progress. Each distinct card reviewed on a local calendar day earns 10 XP, including Again. Daily missions award 20 XP each for five distinct cards, two revisited difficult cards, and three distinct cards with confidence predictions. Repeating a card on the same day does not repeatedly award card XP. Ratings still use the normal scheduler; confidence and rewards never change the rating or interval.

Before revealing, an optional Guessing / Unsure / Confident prediction is locked in. Reviews retain `confidence` (`low`, `medium`, `high`). Confidence calibration compares Guessing with Again, Unsure with Hard, and Confident with Good/Easy. Recall strength counts successful recall on up to three different calendar days; a later Again or Hard makes the card a rematch. This is practice feedback, not a retention estimate.

The weekly challenge selects up to five active cards practised before the current Monday, using the same sorted IDs and week offset in both clients. It opens after the first week of practice. Each challenge review retains `challengeWeek` (local Monday, YYYY-MM-DD) and `challengeSize` (1–5). Reviewing the full selection earns 75 XP once for that week, regardless of ratings. Progress and XP are derived from the deduplicated shared review history rather than separate counters. Deleting review history also removes its derived rewards.

`mistakeReviewPreferences` now also carries `practiceDays` (ISO weekdays 1–7), `practiceTime` (local HH:mm), `practiceTarget` (1–20), optional `practiceSnoozedUntil`, `practiceEffects`, `practiceSound`, and `practiceFinish`. The routine is an in-app weekly window, not a background notification. Skipping a window moves the next suggestion past it without changing card due dates. Daily mission goals stay fixed when the session target changes. Finishes unlock at 0/100/300/600 XP and tint progress and confetti. Sound is off by default, effects respect reduced motion, and earned progress has no missed-day penalty. Both clients should be updated so older preference writers do not discard the new fields.

No database migration is needed: the existing generic `user_state` feed carries the `mistakeReviewPreferences` keyed row with `{value, updated_at}`. The value contains `spacingMultiplier`, `retryHours`, `maximumIntervalDays`, optional `lighterUntil`, `lighterDailyTarget`, and `updatedAt`. Both clients clamp numerical settings before scheduling. Focal includes the row in its existing durable outbox and rebuilds its feed once on upgrading. Folio caches the preferences per account and durably queues an idempotent settings mutation, using row sequences and retrying stale writes. Offline settings survive process restart; Folio replays the feed once to discover preferences skipped by older clients. Large replays retain a separate cursor.

Reduction changes normal mistake `dueAt`/`updatedAt` fields through each client's existing queue. Folio marks date-only mutations so a concurrent remote review keeps its history, state and interval; a remote due date later than the requested postponement also wins. No notebook storage or handwriting format changes. Older clients display the shared rescheduled dates but continue using their own fixed algorithm when rating; both clients should be updated for consistent pacing.

## Verification

`./gradlew :app:testDebugUnitTest` checks defaults, clamping, history preservation, future occupancy, breaks, expiry, settings cache round-trip, stale settings CAS, and postponement rebasing over another device's review. Focal uses `bun run check`, `bun run build`, and `bun run build:desktop`.

On a device and a signed-in Focal browser: save pacing in each direction; apply a lighter period with a busy upcoming week; rate a card into a full day; practise a postponed question early; end the period; edit offline, restart, then reconnect. Check history, handwritten practice pages, and future dates stay intact.
