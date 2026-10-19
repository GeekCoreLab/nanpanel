# Repository Guidelines

## Project Structure & Module Organization

NaNPanel combines a Java 21 Quarkus control plane with a React/TypeScript console.

- `backend/panel-*`: Maven modules for Core, domain, plugins, gateway, networking, clustering, and persistence. Java sources and tests use `src/main/java` and `src/test/java`; Liquibase migrations live in `panel-persistence/src/main/resources/db/changelog/`.
- `web/src/`: UI components, styles, API client, generated types, and in-memory mocks; `web/scripts/` contains tests and tooling.
- `api/`: OpenAPI contract and endpoint semantics. `docs/adr/` records architecture decisions; `PROJECT_PLAN.md` defines the roadmap.
- `agent/`: Rust workspace with the mTLS inventory Agent, root Helper PoC, and runtime-driver crate; `plugins/provider-*` remain provider placeholders.
- `deploy/`: local PostgreSQL Compose configuration, container images, and the test K3s manifests/scripts in `deploy/k3s/`; `tests/` reserves cross-module verification.

## Build, Test, and Development Commands

Use Java 21+ and Node.js 22.18+ to support both builds and tests. Run Maven commands from the repository root; npm commands from `web/`.

- `./mvnw verify`: build and test all backend modules.
- `./mvnw -pl backend/panel-core -am test`: run Core tests and required modules.
- `npm ci && npm run build`: install locked dependencies, check TypeScript, and build into `web/dist/`.
- `npm run dev`: serve the console, proxying `/api` to Core on port 8080.
- `VITE_API_MODE=mock npm run dev`: start a standalone demonstration.
- `npm test`: run Node mock and topology tests.
- `python web/scripts/generate-types.py`: regenerate DTOs from OpenAPI; requires PyYAML.

Follow the root README for database setup and packaged Core startup.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF, final newline, spaces; four-space indentation by default, two for JSON/YAML/TSX/CSS. Use Java `io.nanpanel` packages, PascalCase classes/components, and camelCase methods/variables. Keep generated DTOs synchronized with the contract. No dedicated formatter or lint script is configured.

## Testing Guidelines

Backend tests use Quarkus JUnit and REST Assured; name classes `*Test.java`. Frontend tests use Node’s runner (`*.test.mjs`) and Playwright. Run `npm run test:browser` and `npm run test:topology` against a running mock server. Cover changed behavior and rejection paths; no numeric coverage threshold is configured. Mock success does not establish backend or deployment correctness.

## Commit & Pull Request Guidelines

History currently contains only `Initialize NaNPanel repository`; no established prefix convention exists. Use concise imperative subjects. PRs should explain behavior, link relevant issues/contracts, report validation, and include screenshots for UI changes.

## Architecture & Configuration

Keep Core in JVM mode and self-developed edge components in Rust. Preserve the ordinary-user Agent/root Helper boundary. Distinguish planned endpoints from implemented features. Keep credentials in ignored environment files; commit examples only. Retain Secure Cookies for production HTTPS.

## Rust Engineering Rules

- 优先保证正确性、内存安全、可维护性，其次考虑性能。
- 不允许为了绕过所有权检查而盲目添加 `.clone()`。
- 优先使用借用（`&T`、`&mut T`），确实需要独立所有权时才使用 `Clone`。
- 禁止无理由使用 `unsafe`、`unwrap()`、`expect()`、`panic!()`。
- 使用 `Result` 和 `?` 传播可恢复错误。
- 不得通过删除测试、弱化断言或隐藏警告来通过检查。
- 不得无理由修改公开 API、依赖版本或现有数据结构。
- 修改代码后必须执行：
  - `cargo fmt --all -- --check`
  - `cargo check --all-targets --all-features`
  - `cargo clippy --all-targets --all-features -- -D warnings`
  - `cargo test --all-features`
- 如果检查失败，必须说明失败原因，不得声称已经通过。
- 修改前先理解现有架构，尽可能采用最小变更。
