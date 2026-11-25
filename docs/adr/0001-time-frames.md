# ADR 0001: Independent limits, time frames and grouped settings

- Status: accepted (agreed on 2026-11-24, implemented in 0.6.0)
- Applies to: 0.6.0

## Context

Up to 0.5.0 the rules are:

- a daily limit per app, with one value for weekdays and one for the weekend;
- one fixed "morning" (midnight until a set hour, on weekdays) with a pool of minutes that replaces the per-app limits
  while it lasts, and only counts apps that have a limit of their own;
- one schedule window that turns kids mode on;
- every setting on a single list.

What is missing: a limit on total TV time ("one hour, on any app"), limits per app inside a part of the day
("Netflix, 30 minutes in the morning"), and parts of the day other than the fixed morning. Adding each of these to the
single list would make it unreadable with a remote.

## Options considered

1. **A daily total next to the existing morning.** Smallest change. Leaves two pools with different rules (the morning
   replaces app limits, the daily total adds to them) and no way to limit one app in the morning.
2. **Fixed frames (morning, afternoon) with a total and per-app limits each.** Covers the cases, but six values per
   app (three frames, weekdays and weekend) and hours that are burned in.
3. **Time frames the parent defines, with a limit mode selector** (daily total, by time frame, by app, mixed).
   Dropped after comparing with other products (Apple Screen Time, Google Family Link and Google TV, Nintendo,
   Qustodio): none has a mode selector. All of them show each kind of limit as its own feature, on or off.
4. **Three independent kinds of limit plus time frames the parent defines.** Chosen.

## Decision

### Model

- **Three kinds of limit, each on or off by itself**: the daily total, the time frames and the limits per app. One
  that is off keeps its values. There is no mode.
- **Time frame**: a free name (a kid's name says who it is for), days of the week, a start and an end on the same
  day, an optional total and optional limits per app that only hold inside it. Frames never overlap; saving one that
  overlaps another is refused. At most four. Each frame keeps its own count.
- **Why frames**: other platforms give each kid a profile. A shared TV has none, so a frame stands in for "who is
  watching": what one kid watches in the morning does not spend what another has in the afternoon.
- **The daily total and the limits per app** count the whole day and are shared by every frame. Both keep separate
  values for weekdays and the weekend. Frames don't need that split: a Saturday morning is another frame.
- **One rule**: every limit that applies counts at the same time and the strictest wins. In kids mode an app is blocked
  when any of these has run out: its limit in the current frame, the current frame's total, its daily limit, the daily
  total. A minute watched counts against all four.
- **What counts toward a total**: every app on the TV's launcher, except the ones marked **Always allowed**, the home
  screen, Settings and Couch Curfew. A newly installed app counts without the parent doing anything.
- **Per app, four states**: always allowed, no limit of its own (the default; counts toward totals), a number of
  minutes, blocked.
- **The schedule stays as it is**: its own windows decide when kids mode turns itself on. Frames only say how much
  time there is inside kids mode. A frame outside the schedule only counts if kids mode is turned on by hand. Between
  frames only the daily limits apply.
- **Extra time** (behind the PIN) has one entry, "More time today". It goes to the limits that have run out for the
  app; with none run out, to every limit that applies to it. Extra for a total is for every app.
- Time only counts in kids mode, as before.

### Settings

First level, each row showing its state:

| Row | Inside |
|---|---|
| Kids mode | Toggles in place; no screen of its own |
| More time today | Only in kids mode: pick an app, then how much |
| Schedule | When kids mode turns itself on |
| Limits | The daily total, the time frames (each opens its own screen) and the limits per app |
| Always allowed | The apps that never count |
| Daily guideline | The age that colors the dashboard |
| Parent and setup | Change PIN, Setup |

"Setup needed" stays as the first row until setup is done. Inside a frame: name, days and hours, the total and the
apps with their limit in that frame. Depth is never more than three screens.

### Storage and migration

`schema` = 2, with the rules under `rules` and today's count under `today` (both JSON). The keys of 0.5.0 are read
once and never written again, so installing an older APK finds its own data: that is the rollback. Rules changed in
0.6.0 are not seen by the older version.

Read once, when `schema` is missing:

- The schedule is not touched.
- The morning pool, if on, becomes a frame named "Morning" from 00:00 to its end, with the pool as its total. What the
  pool had used that day is not carried over: it was one number, not per app.
- Per-app limits become the daily limits per app, unchanged, with today's usage and extras.

Two behaviors change for a TV that used the morning pool, and the migration cannot hide them:

1. In the morning, apps without a limit were free; now they count toward the morning's total.
2. Time watched in the morning did not spend an app's daily limit; now it does. Accepted: a daily limit covers the
   whole day, and a limit meant for one part of the day belongs in that frame. The release notes say so; the migration
   does not create frames to hide it.

## Consequences

- The dashboard shows the current frame's total and the daily total, when they are on.
- One rule to explain ("the strictest limit wins") instead of "the morning replaces the limits".
- Everything that affects one app is spread over several frames; there is no single screen for "all of Netflix".
- More navigation for the settings that were one press away, except kids mode.
- `Budget` is rewritten around frames and the three kinds of limit; `Schedule` stays; `RulesTest` needs cases for overlap, the strictest limit,
  totals, the migration and the day rollover.
- HDMI inputs and live TV are still out of reach: they are not apps.
