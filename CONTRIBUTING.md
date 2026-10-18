# Contributing

This SDK is **community / experimental**: maintained in-house on a best-effort
basis until an outside maintainer volunteers. If you would like to be that
maintainer, open an issue.

1. Branch from `develop` and open the pull request against `develop`.
2. `sh scripts/check` (or `./mvnw -B verify`) must pass. Format with
   `./mvnw spotless:apply`. If you touched auth, the socket or webhooks, also
   run the smoke test against a real hub (`./mvnw -Psmoke verify`, see
   CLAUDE.md).
3. Do not edit `src/main/generated/` by hand. Run `sh scripts/generate
   <Wavvon-docs checkout>` and commit the result with the new `SPEC_COMMIT`.
4. A new signed byte sequence starts as a change in
   [Wavvon-server](https://github.com/Wavvon/Wavvon-server) with a wire
   vector, never here.
5. A feature that needs a hub capability checks it with `requireCapability`
   and lists it in the README table.

Contributions are licensed under Apache-2.0 (see LICENSE).
