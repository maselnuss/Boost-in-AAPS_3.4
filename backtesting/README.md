# backtesting

The analysis record that used to be here (the dated studies under `scripts/`, the reports,
research notes, protocols, the relationships register and the statistical-methods note) moved on
29 October 2026 to separate private repositories that keep the same `backtesting/...` paths:
`boost-experiments` for the record as a whole and `vwatdd` for the volume-weighted TDD studies. A
path such as `backtesting/scripts/2026-07-v7-foundation/v7_common.py`, named in a source comment
or a document here, is found at that path there.

Their history up to the move is in this repository; the last commit holding them is
3f75d6a36c84e048c5ddf8a5dfce935184842d11.

What stays here is the infrastructure the studies call: `scripts/extractor` (Nightscout to the
local database), `scripts/kotlin-harness` (the replay harness for the engine sources) and
`scripts/tooling` (the emulator launch check, markdown to PDF and other build and publishing
helpers).
