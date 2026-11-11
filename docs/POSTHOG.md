# Play usage and crash reporting

Play releases default to reporting on; an existing opt-out wins. FOSS has a
no-op reporter and no SDK, endpoint or reporting strings. Reporting uses a
separate no-backup file, never the settings archive or account sync.

The pinned `com.posthog:posthog-android:3.76.2` SDK is MIT licensed. Its runtime
closure adds the MIT core SDK and Apache-2.0 Kotlin, AndroidX, Curtains, Gson,
OkHttp and Okio dependencies. The Android SDK core client is configured directly
so no Android automatic capture integrations are installed. The license is
shipped in Play assets and listed in About. Re-audit SDK queue and transport
behavior when upgrading: SDK opt-out does not erase the queue, and close flushes.

Only the five `UsageEvent` values and sanitized JVM exceptions may enter the
queue. The before-send allowlist removes SDK context enrichment. A transport
gate rejects all paths except batch ingestion. Disable closes that gate,
cancels calls, persists the preference, drains queue deletion and discards the
installation ID. An HTTP request already delivered cannot be recalled.
The uncaught handler waits at most two seconds for crash persistence before
calling Android's prior handler. Delivery is best effort, including disk-full
and abrupt process termination. Native crashes and ANRs are out of scope.

## Configuration

Set `LISEUR_POSTHOG_PROJECT_TOKEN` to the EU project's public `phc_` token.
Never supply a personal or administrative API token. Missing configuration
leaves local releases disconnected. Debug and dev ignore the production token;
explicit `LISEUR_POSTHOG_TEST_TOKEN` enables a separate EU test project only.
Do not set the test variable to the production token.

Official CI uses `-PofficialPlayRelease=true`, requiring both the public token
and `LISEUR_REPORTING_CONSENT_REVIEWED=true`. Set these repository variables
only after completing the release prerequisites below. The boolean records a
human review; it is not consent and does not establish a legal exemption.

## Projects

Both projects are on PostHog Cloud EU, on the free plan. The free plan allows
one project per organization, so they live in separate organizations:

| Use | Organization | Project | ID |
| --- | --- | --- | --- |
| Play releases | Chmouel's Organization | Liseur | 344562 |
| Debug and dev builds | Liseur Test | Liseur Test | 344594 |

Each project has "Discard client IP data" on, and its GeoIP transformation
is disabled. Autocapture, heatmaps, web vitals, dead clicks, exception
autocapture, console log and network capture, surveys and session replay are
off, and no destinations are configured. Billing has no subscription, so usage
is capped at the free allocation and cannot incur charges. Events are kept for
one year, the free plan's retention, which `docs/PRIVACY.md` states.

## Before publication

Resolve applicable consent requirements before enabling the official release
gate. Default-on pseudonymous analytics is not automatically exempt:
[CNIL guidance](https://www.cnil.fr/en/sheet-ndeg16-use-analytics-your-websites-and-applications)
and [PostHog GDPR guidance](https://posthog.com/docs/privacy/gdpr-compliance).
If consent is required in the distribution markets, change initialization to
wait for consent before publication. EU hosting alone does not resolve this.

Play Data safety declares App activity / App interactions and App info and
performance / Crash logs as collected, not shared, not ephemeral, optional,
for Analytics (Play's Analytics purpose covers crash diagnosis). Device or
other IDs was already declared for ML Kit as optional, for App functionality
and Analytics, which covers the installation identifier. Data is encrypted in
transit. PostHog is a service provider under the PostHog DPA signed for the
production organization (listed under Settings > Legal documents), so nothing
is declared as shared.
Keep the ML Kit answers when editing these. No book content or account
identity is collected by this feature.

Each project has a "Liseur usage" dashboard: daily and weekly active
installations from `app_foregrounded`, total and unique counts of the four
feature events, `$exception` counts by `app_version`, and installations by
`app_version`. Use `app_version` for release comparisons. Turning reporting
off and on resets identity, so counts are installations, not people.

## Verification

Run `testPlayDebugUnitTest --tests 'com.chmouel.liseur.insights.*'`, both flavor
lint/debug builds, and `assemblePlayRelease`. A release build reads only
`LISEUR_POSTHOG_PROJECT_TOKEN`, so for a local minified check set it to the
test project's token; never ship that APK.
Check the Play R8 mapping and a minified test crash for readable frames. App
class/member names and line tables are retained, but R8 still optimizes app
code and may inline small methods, so preserve mapping files to restore inlined
and dependency frames. Inspect `fossDebugRuntimeClasspath` and the FOSS APK for SDK classes
and the EU endpoint.

On a disposable emulator with an explicitly configured test token: capture all
five events, generate an uncaught test exception containing a private sentinel,
restart, and inspect received event JSON. `adb shell am crash <pid>` on the
main process raises a JVM exception; `am crash <package>` hits the WebView
renderer instead, which is a native crash. Confirm the sentinel and free-form
context are absent. Disable while reports are queued, restart offline then
online, and verify no pending or new reports arrive. Re-enable and confirm a
new installation ID and no disabled-period activity. Never run a deliberate
crash or install over the app on a real phone without permission.
