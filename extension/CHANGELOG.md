# Changelog

All notable changes to the Kobol Language Support extension are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.1.0]

Initial release.

### Added
- Syntax highlighting (TextMate grammar): keywords, types, strings, numbers, `--` line comments, `NOTE: … END-NOTE` block comments, and `TODO`/`FIXME`/`HACK`/`XXX` tags.
- Live diagnostics, go-to-definition, hover types, document outline, folding, signature help, code actions.
- Inlay type hints for `LET` declarations.
- Status bar item showing per-file error and warning counts.
- Build integration: `Build`, `Run`, `Test`, `Type-Check`, and `Clean` commands with Gradle / `kobol` CLI auto-detection.
- Native-binary language server with auto-download from GitHub Releases, plus JVM fat-jar fallback.
- 40+ snippets covering programs, records, `MATCH`, `TRY/ON`, `SERVER`, pipelines, JDBC, and HTTP.
- Apache-2.0 license.
