# Security & publishing rules

- Never commit: `secrets.properties`, `app/google-services.json`,
  `local.properties`, `*.keystore` / `*.jks`, or any API key, OAuth client
  secret, password, or token — in code, comments, or docs.
- Keys live only in `secrets.properties` (gitignored); code reads them through
  the generated `OrbitSecrets` object. Update `secrets.properties.example`
  when a new key is introduced.
- Internal planning docs (FIREBASE_SETUP.md, DESKTOP_PLAN.md, RELEASE_NOTES.md,
  docs/*.html design plans) stay LOCAL — they are gitignored on purpose.
  Public docs are README.md and COMMANDS.md only (plus docs/screenshots/).
- Release notes text goes into the GitHub release description, not into a
  committed file.
