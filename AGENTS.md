# PaperReader source-extension instructions

Shared PaperReader rules (branch and commit naming, process isolation for extension code, and the
host verification gate) are in the host [AGENTS.md](https://github.com/ImAno177/PaperReader/blob/main/AGENTS.md).
This file lists only rules specific to the source repository.

- Keep every provider in its own `source-*` Android application module.
- `source-common` owns only Binder, caller verification, bounded HTTP transport, cancellation, and
  rate gating. Provider request construction and parsing stay in the provider module.
- Depend only on PaperReader `:extension-api`; never depend on host `:logic` or `:app`.
- Keep Semantic Scholar as the preferred search engine, Crossref as exact-DOI metadata only, and
  arXiv/Europe PMC as content sources that may also advertise discovery for authoritative fallback
  results.
- Preserve cancellation, response-size limits, host signer verification, and separate-process
  services.
- Add a deterministic fixture test for every parser or routing change. Live API calls are diagnostics,
  not CI assertions.
- When a provider's descriptor changes, update `registry/` to match. `scripts/test_registry_contract.py`
  fails on any mismatch.
