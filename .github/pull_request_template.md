## Summary

Describe the user-visible change and why it is needed.

## Verification

- [ ] Panel tests pass (`cd panel && npm ci && npm test`).
- [ ] Android unit tests pass (`./gradlew :app:testDebugUnitTest`).
- [ ] Relevant integration behavior was tested with fictional or dedicated non-production data.

## Public-repository safety

- [ ] Source, tests, fixtures, docs, screenshots, logs, commit messages, and generated files contain no real deployment or customer data.
- [ ] No live organization/deployment/location names, domains, routes, repository names, field mappings, business codes, template/warehouse IDs, SKUs, item codes, serial numbers, tokens, keys, signing files, photos, or API payloads are included.
- [ ] All examples use reserved domains and unmistakably fictional values.
- [ ] Deployment behavior is configured in Panel/private catalog/Cloudflare rather than hardcoded in App or Panel source.
- [ ] New optional behavior is fail-closed or disabled when its explicit configuration is absent.
- [ ] The full branch history was reviewed; deleting sensitive data in a later commit is not sufficient.

## Compatibility and documentation

- [ ] Schema or adapter changes are versioned and tested across Panel, Worker, and App.
- [ ] README and the relevant file under `docs/` match the implemented behavior.
- [ ] Catalog fallback, migration behavior, and release/rollback impact are documented where relevant.
