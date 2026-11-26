---
name: jolt-metrics
description: >-
  Use when working in the jolt-metrics library: a Jolt code-metrics library
  that computes LOC, cyclomatic and cognitive complexity, Halstead metrics,
  the maintainability index, architecture coupling and unusual commits over
  Clojure/Jolt and Scheme source, lints for idioms, likely bugs and needless
  work, and renders a report led by a ranked refactoring queue, which
  --baseline compares with an earlier run. Covers adding or changing a metric
  (write its writ spec first, then the pure implementation), adding a lint
  rule, the normalized model, the reader and scanner, and the coupling, queue,
  baseline and report pipeline.
---

# jolt-metrics

A code metrics library for Clojure/Jolt and Scheme source, written in Jolt. Every metric's
computation is checked against a writ spec, not just unit tests.

## Layering

```
source ──► normalized model ──► pure metric visitors ──► queue ──► report
  reader.clj, scanner.clj       halstead.clj             queue.clj  report.clj
                                maintainability.clj      cleanup.clj json.clj
                                cyclomatic.clj                      html.clj
                                cognitive.clj
                                coupling.clj, calls.clj
                                hotspot.clj, rules.clj
                                entropy.clj (unusual changes and files)
                                compression.clj (size, redundancy, near-dups)
                                loc.clj (over scanner's line classes)

forms ──► egraph.clj ──► abstraction.clj (abstraction opportunities)
forms ──► lint.clj ──► ignore.clj (scanner's markers drop lints/queue/cleanup) ──► sarif.clj
```

- **`metrics.reader`** is plain Clojure (not writ-checked): it reads forms with
  `clojure.core/read` and maps them to the normalized model. Every walker goes
  through `code-children`, so the metrics can't disagree about what is code.
- **`metrics.scanner`** is plain Clojure too: a lexer over the raw text, total on
  any input, for docstrings, discards and where each list ends. A syntax map says
  what the tokens are.
- **`metrics.*`** metric namespaces are pure and writ-constrained: single-arity
  fns, no interop, recursion only via `case`-destructured fields of a `data`
  type (or `rest`/`dec` under a test). They never mention writ and never do I/O.
- **`metrics.tokens`**, **`metrics.deflate`** and **`metrics.minhash`** are plain
  Clojure host code behind the writ-checked `metrics.compression`: the normalized
  token stream (locals as `$0`, strings blanked), DEFLATE sizes, and the MinHash
  pre-filter for the near-duplicate pair search.
- **`metrics.egraph`** and **`metrics.abstraction`** are plain Clojure with
  behaviour tests, not writ-checked (union-find, memo atoms, `pmap`). The e-graph
  is a persistent value after egg: hashcons, union by size, deferred `rebuild`,
  per-class occurrence counts, local taint, source-node set and units, and a
  one-pass memoized `extraction`. `metrics.abstraction` encodes forms as terms
  (strings keep their text, unlike tokens), saturates with sugar-undoing rules
  (`->`, `->>`, `when`, `when-not`, `if-not`, nil else), anti-unifies bucketed
  sorted-neighbourhood pairs (babble), scores with Stitch's `utility` and picks
  greedily, spending the code each pick covers. Locals are always variables;
  binders aren't parameters; derived nodes weigh 0 so patterns show as written.
  It is the slowest pass (about 5s on clojure-lsp): profile before adding work,
  and keep the output deterministic under `pmap`.
- **`metrics.core`** orchestrates parse → per-file → coupling → call graph → queue
  → summary, then the information findings (unusual changes and files,
  near-duplicates, and abstractions unless `:abstractions false`). Functions keep
  `:param-vectors`/`:body` until then; `public-file` drops them.
- **`metrics.callgraph`** is plain Clojure glue over jolt-lsp: an adapter's optional
  `:index` fn (only Clojure's has one) turns source into a jolt-lsp analysis, and
  `annotate` builds the index from those analyses alone (`db/build-from-analyses`,
  tests left out) and puts `:fan-in`, `:fan-out` and `:reach` on each function.
  The counting and the queue weight (`reach-factor`) live in the writ-checked
  `metrics.calls`. Methods, helpers and Scheme get nil.
- **`metrics.lint`** is plain Clojure with behaviour tests (`lint_test.clj`): clj-kondo's
  syntactic rules over read forms, matched by head name, with clj-kondo's messages and
  default levels (`default-levels`). It is the Clojure adapter's optional `:lint`, so
  Scheme is never linted. A rule is `(fn [form ctx])` returning `{:rule :message}`s,
  listed by head in `rules-by-head`; ctx has `:parent`, `:index`, `:in-def`, and a
  threading step arrives with the threaded value added (`as-called`). Quote and
  `comment` are data. New rules go in `default-levels` too (config validates against
  it), and the README's Lint rules table. Run new rules over clj-kondo's own `src`:
  it should stay clean, since clj-kondo lints itself.
- **Performance lint rules** (`str-accumulation`, `concat-accumulation`,
  `contains-on-sequential`, `linear-membership`, `copying-pop`, `last-on-vector`,
  `lazy-into`) are ours, not clj-kondo's, after the AAE knowledge base. Rules that
  judge a call by its argument go in `pair-rules`, so `unthreaded-rules` also
  checks them on expanded `->`/`->>` steps. Loop-shaped rules (`copying-pop`,
  `loop-accumulation`) only look at what `recur` rebinds, so a one-off copy isn't
  flagged.
- **`metrics.baseline`** is plain Clojure: `compare-queues` matches queue entries
  by kind, path, name and order among same-named entries (not line) and gives
  `:fixed :improved :worse :new :unchanged :not-analyzed`; an entry whose file
  the later run didn't read is `:not-analyzed`, never `:fixed`. `with-baseline`
  attaches it as `:since-baseline`, and Markdown, JSON (`since_baseline`) and
  HTML render it. The CLI reads `--baseline FILE` (an earlier `--format edn`
  report), checks its entries, and calls it, which keeps `metrics.core` under
  its efferent-coupling limit.
- **Kept on evidence**: unusual-change z-scores stay mean/SD and the file index
  stays an arithmetic mean. Median/MAD and a geometric mean were measured on
  real repositories and did worse (see the docstrings in `metrics.entropy` and
  `metrics.core/file-index`); don't swap them without new data.
- **`metrics.scanner`** returns `:ignores`: `#_:jolt-metrics/ignore` or
  `#_{:jolt-metrics/ignore [rules]}` (and `#_:clj-kondo/ignore`, flagged `:clj-kondo`)
  with the line range of the form after it. **`metrics.ignore/reviewed`** levels the
  lints and drops what markers cover from `:lints`, `:queue` (via
  `queue/without-rules`, which rescales the score) and the unused-function cleanup
  lists, then adds `:redundant-ignore` for our markers that dropped nothing.
- **`metrics.sarif`** renders the SARIF 2.1.0 log (`--format sarif`), adapted from
  clj-kondo's `sarif.clj`. Code borrowed from clj-kondo (EPL-1.0) keeps its header
  comment and the README credit.
- **`metrics.paths`** handles globs and resolving loaded files; **`metrics.config`**
  reads `.jolt-metrics.edn`.
- **`metrics.thresholds`** holds every configurable limit, including
  `:duplicates` and `:abstractions {:min-size :max-arity}`, and validates a
  project's `:thresholds`. **`metrics.queue`** ranks the findings; **`metrics.cleanup`**
  finds what looks unused; **`metrics.git`** reads churn, per-commit entropy
  changes and what git ignores (which `analyze-dir` skips via `:ignored`), and is
  the only shell-out. It reads blobs as bytes and decodes them
  itself: Jolt's `sh` ignores `:out-enc` and always decodes UTF-8.
- **`metrics.report`** renders Markdown; **`metrics.json`** the lean, versioned JSON
  for tools (snake_case keys, always present; bump `schema-version` when a key goes
  or changes meaning); **`metrics.html`** one self-contained page that inlines
  `resources/metrics/html/report.{css,js}` and embeds the JSON with `<` escaped.
  All text in `metrics.html` goes through its escaping `markup`; only `raw` skips
  it, so never wrap user data in `raw`. **`metrics.cli`** is the entry point.

**Languages stay separate.** The pipeline only sees an adapter from
`metrics.language`: `:parse` (text to forms in the walker's shapes, plus dependencies)
and `:scan`. Nothing outside `src/metrics/scheme*` may know Scheme exists, apart from
the one line listing its adapter. A Scheme fix belongs in `metrics.scheme.normalize`,
not in the walker: to count a Scheme form differently, change what it normalizes to.

- A raw head the walker would take for a Clojure form must stay in `clojure-heads`
  in `metrics.scheme.normalize`. Adding a form to the walker's tables means adding it
  there too, or Scheme calls of that name change meaning.
- `exprs` is eager on purpose: a malformed form has to fail inside the `expr` that
  can read it as a call instead.
- Pin Scheme with hand-scored numbers in `scheme_normalize_test.clj`.

Reading is safe by construction: `read-forms` binds `*read-eval*` to false and reads
in a scratch namespace. Keep it that way, since the source is untrusted data.

## Adding a metric (spec first)

1. Write the writ spec `test/metrics/<name>_spec.clj`:
   - `(spec metrics.<name> {:require :proved})`
   - a `data`/`refine` type and a `graph` (every spec needs one), `ann` per
     public fn, then laws — a model law (`(= (f x) (model x))`) plus worked
     examples and edge cases.
2. Write the pure implementation `src/metrics/<name>.clj`.
3. Run `jolt -M:test metrics.<name>-spec` until green (`-M:test:dev` uses a
   `../writ` checkout instead of the pinned writ).

writ rules that have cost time here:

- The implementation must have **no `map`-over-`(Vec T)` recursion**; the static
  checker rejects it ("does not descend"). Use a fixed-branching `data` type and
  recurse through `case`-destructured fields (see `metrics.cognitive`).
- Laws that use `log`/`sin`/`sqrt`/`distinct`/`frequencies`/division are only
  `:tested`; mark them `{:require :tested :because "..."}`.
- The gap check fails when a `forall` is missing for a fn — a handful of literal
  examples don't pin a function down.
- Every public fn in a writ-checked namespace needs an `ann`; a helper must be
  `defn-`.
- A law's model recomputes the formula, so it guards arithmetic, not what the reader
  feeds in. Pin reader behaviour with golden examples in `walker_test.clj`.

## The decision table (cyclomatic)

1 per: `if if-not if-let if-some when when-not when-let when-some when-first`;
1 per `cond` clause except a trailing keyword or `true`; 1 per `condp` clause except
a lone trailing form; 1 per non-default `case`; 1 per `cond->`/`cond->>` clause;
n-1 per `and`/`or` of n arguments; 1 per `for`/`doseq` binding, `:when` or `:while`;
1 per `dotimes while loop`; 1 per `catch` (`try` itself is not a branch). Keep this
table and `metrics.reader` in sync.

Cognitive follows the SonarSource paper, with a free plain `else` and no recursion
increment. `cond` is an else-if chain, `cond->` a series of ifs, and `case`/`condp`
are switches: see the `tree-builders` in `metrics.reader`.

## Commands

- `jolt -M:test` — run all writ specs + reader tests; `-M:test:dev` swaps in a
  sibling `../writ` checkout.
- `jolt -m metrics.cli <dir> [out] [--format md|edn|json|html|sarif] [--top N] [--days N]
  [--commits N] [--no-git] [--no-gitignore] [--no-abstractions] [--exclude GLOB] [--config FILE]
  [--baseline FILE]` — the report; without `--format`, out's extension picks it.

`self_test` holds `src` to its own thresholds (20 lines, 4 params, cyclomatic 10,
nesting 3, efferent coupling 19 for a file) and to its own lint rules at default
levels: new code that breaks one fails the suite, so split it. CLI tests that
don't need abstractions pass `--no-abstractions` to stay fast.

Note: running `jolt -M:test` relies on `:main-opts` in `deps.edn`; do not
also pass `-m metrics.runner` on the CLI, or it leaks into the runner's args.

## Conventions

- Metric namespaces return plain maps; `metrics.core` aggregates them.
- The file-level Halstead is the average of its definitions', skipping empty ones.
  The file-level index is the original formula: the average volume, complexity and
  source lines of its definitions. A definition's index uses its own lines.
- The report ranks on the comment-free index; the comment term is informational.
- `maintainability-index` takes the comment weight rather than loc and cloc, so
  callers work it out with `comment-weight`.
- A threshold change belongs in `metrics.queue` (or `mi-band` in `metrics.rules`).
  Update the README's table too, including where the number comes from.
