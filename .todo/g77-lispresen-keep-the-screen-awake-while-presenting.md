# g77: the lispresen player lets the screen sleep during a talk

Difficulty: Low

A presenting machine dims and then locks its screen after the idle time the OS is set
to, because a static page with no input is idle to it. A speaker who talks over one slide
for a few minutes, or presents from a tablet or phone (Touch in `lispresen/DESIGN.md`),
loses the screen mid-talk. The player (`lispresen/src/player.lisp`) does nothing to keep
it on.

## Value

- For the user: the screen going dark in front of an audience is one of the few failures
  the speaker cannot recover from without walking to the machine. Changing the OS
  settings before every talk is easy to forget.
- Design: player-only, no change to the deck format or the layout; the HTML stays one
  file without network access.
- Cost: a few lines of player script.

## Shape

- Request a screen wake lock (`navigator.wakeLock.request('screen')`) while the deck is
  in full screen, and in the presenter window while it is open; release it on leaving
  full screen. The browser drops the lock when the page is hidden, so request it again
  on `visibilitychange` back to visible.
- Where the API is missing or refused (no secure context, battery saver), do nothing and
  say nothing on the audience screen; the presenter window may show a small indicator.
- Check whether `file://` counts as a secure context for the API in Chrome and Safari;
  if not, document that the lock needs the deck served over HTTP (as Start does).

## Verification

- Chrome and Safari (DevTools / `safaridriver`): entering full screen acquires the lock
  (`WakeLockSentinel.released` false), leaving full screen and closing the presenter
  window release it, switching tabs and back reacquires it; over HTTP and from
  `file://`.
- `lispresen/check.sh`: byte-identical output on every backend (the golden HTML is
  regenerated).
- README.md (Presenting) and DESIGN.md (Full screen) updated.
