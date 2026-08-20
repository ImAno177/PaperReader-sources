# PaperReader source-extension instructions

- Keep every provider in its own `source-*` Android application module.
- `source-common` owns only Binder, caller verification, bounded HTTP transport, cancellation, and
  rate gating. Provider request construction and parsing stay in the provider module.
- Depend only on PaperReader `:extension-api`; never depend on host `:logic` or `:app`.
- Keep Semantic Scholar as the preferred search engine, Crossref as exact-DOI metadata only, and
  arXiv/Europe PMC as content sources that may also advertise discovery for authoritative fallback
  results.
- Preserve cancellation, response-size limits, host signer verification, and separate-process
  services. Never load code into the PaperReader process.
- Add a deterministic fixture test for every parser or routing change. Live API calls are diagnostics,
  not CI assertions.
- Branches use `<type>/<kebab-case-summary>` with Conventional Commit types, for example
  `feat/add-core-source` or `fix/crossref-doi-normalization`. Do not add tool, owner, or agent prefixes.
