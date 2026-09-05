# 709. The wave-end process draft: surface accounting between lanes

Difficulty: Low

Filed 2026-09-05 by orchestrator A, mid-wave, **to make durable what until now existed
only in two orchestrators' conversation.** Both sides agreed to draft it at wave end and
neither had written it down; a session ending would have lost it, which is itself an
instance of what the wave kept finding.

**Nothing here is a rule yet.** It is a draft to be co-signed by both orchestrators, or
cut down, at the next planning. Orchestrator B (`session_013dsYLqVDdAhRxX2FPt25VU`,
GB10) co-authored most of it and several clauses are its wording verbatim.

## Part 1 -- Surface accounting: three elements, three owners

The wave lost time twice to two lanes standing on one surface. Neither was visible from
inside either item; both took one command to settle once someone looked at FILES instead
of at INTENTIONS.

1. **Lane, at close-out: list the FILES you touched**, `git diff --name-only
   origin/develop...HEAD`, not only the items you closed.
2. **Lane, before answering an overlap question: grep and diff, never recollect.** The
   failure is not carelessness. A truthful account of the WORK answers a different
   question than one about the PATH -- B answered "672 is not in `examples/`", which was
   accurate about its code and wrong about the directory, because 672 cited the path eight
   times and edited a README inside it.
3. **Orchestrator, periodically and unprompted: intersect the live lanes' surfaces.** For
   each worktree, `git diff --name-only` against its merge base plus uncommitted and
   stashed files, then `comm -12`. **Only this one catches an overlap nobody suspects**,
   and it is the cheapest because it needs no one to have a hunch. B ran it across two
   lanes and found `JvmIoRuntimeBuilder.java` in both -- known to neither lane nor to B --
   in about fifteen seconds. **It scales quadratically with lane count** (3 lanes is 3
   pairs, 4 is 6), which is an argument for the single-lane arrangement both sides moved to.

### Two clauses on element 2, both earned the hard way

- **Grep loosely first, then narrow -- never the reverse -- and check relative as well as
  absolute forms.** The reason, which is what makes anyone actually check: **an anchored
  pattern encodes an assumption about the surrounding syntax, and that assumption is
  invisible in the pattern.** `^\s+path:` cannot match `  - path:` because YAML list
  syntax puts `- ` between the indent and the key; nothing in the pattern says it is
  making a claim about list-versus-mapping form, which is why it reads as more rigorous
  and matches less. **Three people wrote the same too-narrow pattern independently** on
  one day (51 / 41 / 53 files against a true 74-94), so it is the natural pattern to
  write, not anyone's lapse. The nine entries B's anchor missed were exactly the
  functional ones -- the syntax carrying the operational meaning was the syntax the anchor
  tripped on.
- **Grep finds citations, `git ls-files` finds members, and a rename needs both.**
  `examples/llama2/.gitignore` appeared in no content search -- loose, anchored, relative
  or absolute -- because its NAME is in the renamed path and its CONTENTS mention nothing.
  Only `git mv` found it. That is not a sharper grep; it is a different instrument for a
  different question.

## Part 2 -- Failure directions, for whoever writes the `.kb` card

Sorted 2026-09-05. **The first three are failures of a record about ITSELF; the last group
are failures of the ARRANGEMENT, and filing them together would suggest a remedy that does
not apply.**

**Record-about-itself (three directions, and they do not merge):**

- **Under-record** -- written silent at the only moment the information existed: the
  thread count on a `--parallel` row, a checkpoint path with no digest, dorian's steady
  co-tenants. `.todo/670` rule 10. Not discoverable later, because there is no trace to
  trip over -- the only way to find it is to re-measure and disagree with yourself.
- **Mis-record** -- a quantity asserted as measured that nobody established: a GB/token
  divisor, a one-accumulator baseline, "155 tokens" written from looking rather than
  counting. **This direction has NO rule and is currently defended by nothing but luck** --
  its three instances were each caught by a different accident. Whatever is written for it
  should carry a procedure.
- **Over-read** -- a record read as carrying more than it can support: a count read as an
  audit (a census said three decoders where two existed), a word read as a policy ("lenient"
  is a category containing drop-the-tail and byte-substitute), a cell read as its column
  (one of eight parallel cells), an umbrella read as beating its child (`.todo/670` rule 9),
  a plausible cause read as an identified one (contention explained everything until a
  quiet box reproduced the effect anyway).

**Arrangement (a different kind, three instances):**

- **The index names items, not surfaces.** An overlap between two items is a property of
  neither, so no care inside either can surface it. Both items were correct, neither stale.
  The remedy is not "write more carefully" but "index a second dimension" -- Part 1.
- **A hole created by the coordination that was supposed to close it.** Three `ci-spec.yaml`
  changes landed, every lane was told to skip the native pass because one merged run would
  cover them all, and the session owing that run ended. The centralisation was the right
  call and it manufactured a single point of failure.
- **The gap between assignments.** A suite ran before the last change landed; an acceptance
  slice covered that change's own surface; the COMBINATION went unverified. **Neither time
  did anyone skip a step they were assigned. Both times the gap was between the
  assignments.**

## The mis-record procedure, owed and now written

Part 2 flags mis-record as the direction with no rule, defended by luck. This is the
procedure it asked for, contributed by the GB10 side; it is a DRAFT like the rest.

**Every number entering a record carries how it was obtained, and a derived number names
its inputs.** Four tags are enough: **measured** (someone ran it), **derived** (computed
from other numbers -- name them), **counted** (enumerated, not eyeballed), **estimated**.

It is mechanical, it costs a word, and it is applied at the only moment the information
exists -- the same argument that carries rule 10. Checked against all three of the day's
instances, it catches each for a different reason, which is the test a procedure has to
pass:

- **The GB/token divisor.** Written as *derived (tok/s / parameter-count bytes)* the
  dependency is visible in the sentence, so a reader sees what has to hold for the number
  to mean anything. Untagged it read as a measurement of bandwidth.
- **The 1.6x that was withdrawn.** Written as *measured 2026-09-03, against the
  one-accumulator f32 kernel* it carries its own expiry: the baseline is named, so when
  `.todo/480` lands four accumulators the number is visibly stale rather than merely wrong.
- **"155 tokens".** There is no honest tag for a glance. Being required to write **counted**
  is what sends you to count -- the tag cannot be applied truthfully without doing the work,
  which is the property that makes it a procedure rather than a reminder.

The third case is the one that shows the shape: **the procedure works because one of the
tags is unavailable to a writer who has not done the work.** A rule that only asks for care
can be satisfied by feeling careful.

**What it does NOT catch, established 2026-09-05 by an instance offered as a worked
example for it.** `.todo/489`'s lane found that `-m chat` on a model with no chat template
does not fail: TinyLlama's llama row has no template, so chat mode fed the raw prompt, the
model answered EOS at the first sampled position, and the printed tok/s covered only the
nine PROMPT positions. Twelve runs were discarded.

**Tag that number and it comes out `measured`, truthfully.** Someone ran it; the harness
executed; the figure is what the run produced. The procedure above is silent, and so is
every other record-keeping rule here -- nobody under-recorded, nobody over-read, and the
label that carried the discrepancy ("chat prompt") was present, correct, and read by
nobody as a contradiction for two days.

So this is not a mis-record instance with a sharper edge; **it is outside the record
entirely.** The harness emitted a well-formed number for a question it was not asked, and
no discipline applied to WRITING the number could have known. What is needed is that the
harness REFUSE -- an incompatible model class and mode should be an error, not a number --
which is a validation defect and not a documentation practice.

Worth stating because the temptation was to accept it as evidence for the procedure. **A
procedure that appears to cover an instance it cannot detect is worse than one with a
stated boundary**, and this one's boundary is: it makes the CLASS of a claim visible, and
it assumes the measurement answered the question it was set. Nothing here defends the
second assumption; only a harness can.

Two things it deliberately does not do. It does not ask anyone to re-derive a number they
are quoting -- naming the input is enough, and the reader decides. And it says nothing
about whether a number is RIGHT; it makes the class of claim visible so that over-read has
less to work with, which is why this direction and over-read are neighbours rather than
duplicates.

## Part 3 -- Three practices already adopted, not waiting for this item

- **Close-outs list files touched.** In use on both sides since 2026-09-05.
- **A step that reports success is not evidence until someone has watched it report
  failure.** `.todo/688` removed `TokenizersLibrary` from `expand` and confirmed the guard
  went red with ten undefined `TOKENIZER:` names before trusting its green. The 682 lane
  found three defects the same way -- chasing a 39-to-38 count, reading a skip list instead
  of trusting it, hand-reviewing ambiguous hits -- **every one by looking at the OUTPUT of
  a step rather than at its exit status.**
  **Boundary, added 2026-09-05: the CHEAP form of this is a comparison, and it only exists
  where a baseline does.** An acceptance slice always has one, so there the instrument is
  free -- read the new run's skip count against the prior run's for the same slice, and the
  negative control is unnecessary. `.todo/682`'s acceptance had a baseline two tables up in
  the same file and nobody read it as a delta. **A first run of a new slice has no baseline,
  and for that the negative control remains the only instrument.** Keep both halves: a rule
  that looks like it always has a cheap path will be applied where there is no baseline and
  will report nothing, confidently.
- **A number whose meaning is in doubt is marked in place, never reconciled or deleted.** A
  number that is doubted and SAID to be doubted is still evidence; the same number silently
  re-fitted, or silently removed, is not -- and the reader who needs the warning is the one
  who was not there. In use since 2026-09-05, when `.todo/489`'s recorded f32 TinyLlama rows
  (1.86 / 8.84, labelled "chat prompt") fell under suspicion: TinyLlama's llama row has no
  chat template, so `-m chat` may have been timing nine prompt positions rather than
  generation. The rows stay, with the suspicion and the reason beside them and "not
  re-taken" stated. **This is the practice that keeps the boundary above from being an
  excuse** -- the procedure cannot detect a harness answering the wrong question, but once a
  human suspects one, this is what stops the evidence from being tidied away before anyone
  can check.

## Do

Both orchestrators read this at the next planning and either co-sign it into `.kb` (Part 1
as a practice with named owners; Part 2 as the card, or two cards citing each other) or cut
it. **Do not land it as a rule on one orchestrator's say-so** -- the standard the wave held
itself to was that a rule derived from one lane's reading is a hypothesis until the other
lane has tried to break it, and most of Part 2 has exactly one instance behind it.
