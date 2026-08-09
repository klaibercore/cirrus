<!--
Thanks for the pull request. Keep it to one change; a refactor bundled with a fix is two PRs.
-->

## What this changes

<!-- One or two sentences. What is different after this is merged? -->

## Why

<!-- The problem being solved. Link the issue if there is one: Fixes #123 -->

## How it was verified

<!-- Delete what does not apply. -->

- [ ] `./gradlew :app:testDebugUnitTest` passes
- [ ] `./gradlew :app:lintDebug` passes
- [ ] Tried it on a device (say which, and against which host/model)
- [ ] Added or updated tests
- [ ] Not testable on the JVM, because: <!-- explain -->

## Checklist

- [ ] Dependencies still point inward (`ui → domain → data`)
- [ ] No key, token or message content is logged
- [ ] Any new network destination is documented in SECURITY.md and the README
- [ ] Any tool that writes to a remote service is default-off and says so in its description
- [ ] Comments explain *why*, not *what*

## Screenshots

<!-- For UI changes. Light and dark if the change touches colour. -->
