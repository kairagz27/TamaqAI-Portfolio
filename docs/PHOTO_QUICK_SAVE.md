# Photo quick save

The photo is still persisted locally as a draft before any network request. A fresh pending photo starts analysis once when the current account already has analysis access. The scanner and photo picker must explain the upload before selection. Accounts without access keep the invitation and explicit Analyze flow. Standalone demo does not upload or auto-analyze arbitrary photographs.

A validated `food` result with components is saved automatically for a new, unedited meal. Nutrition remains labelled `AiEstimate`, or `Demo` when the provider explicitly reports fake analysis. Ambiguous and no-food responses remain reviewable drafts and are not automatically added to totals. Manual entry and existing-meal edits retain explicit Save.

## Recovery and boundaries

- Failed or cancelled drafts never automatically restart on reopening. Retry uses the existing analysis id and photo, subject to the existing seven-day pending-photo retention policy.
- Successful analysis is persisted before confirmation. If local confirmation fails, the user retries Save without requesting another analysis.
- Room confirmation is the success boundary. Cancellation cannot downgrade a committed record to Failed. Opening an already-confirmed editor route closes it instead of saving again.
- The pre-existing privacy policy is retained: confirmation clears the pending photo reference, then removes the local pending photo. Cleanup is best effort and cannot turn a successful save into an apparent failure.
- No quota, reservation, ledger, backend URL, authentication, migration, or provider changes are made by this workflow.

## Verification

On 2026-11-09, the existing 17 MealEditor JVM tests passed, with zero failures, errors or skips. They cover one-shot automatic save, missing access, ambiguous/no-food drafts, demo provenance, local-save retry, cancellation during confirmation and after analysis persistence, failed-draft reopening, duplicate actions, offline failure, and portion editing. Those tests use in-memory fake providers.

Four additional `LocalHttpPhotoFlowTest` emulator instrumentation cases exercise actual loopback HTTP through `SessionClient`, `BackendFoodAnalysisRepository`, the use case, ViewModel and Room:

- A valid response automatically saves and remains confirmed when reopened.
- Explicit fake-provider provenance remains `Demo`, not a real AI estimate.
- An invalid response preserves the draft instead of confirming it.
- HTTP 503 requires an explicit retry, which reuses the same analysis key and photo.

All four passed within the 24-case demo/core instrumentation suite. The core suite also covers cancellation. The HTTP server returns controlled mocked-provider responses: this verifies transport and persistence integration, not live AI, calorie accuracy, external-server availability or physical-device/poor-network field behavior. No paid inference was used. See [complete verification status](CURRENT_DELIVERY.md).
