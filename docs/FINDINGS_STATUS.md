# Old review findings — status (2026-11-08)

The findings below came from the earlier Grok-era harness reviews (REVIEW_20261106_2303 and REVIEW_20261108_114432).
The user confirmed on 2026-11-08 that the harness-evidence findings are obsolete: the harness is no longer used, and
Orbis is now tested with unit/Compose tests plus direct adb checks on the prove_x64 emulator.

| ID | Topic | Status |
|---|---|---|
| R2303-01..04, 08, 11, 12, 13 | Harness evidence/driver validity, acceptance records | Obsolete (harness retired) |
| R2303-05 | Private address carried into a new normal tab | Fixed earlier; re-checked on device 2026-11-08 (no private URL in normal tabs or history) |
| R2303-06 | No FLAG_SECURE / Recents protection for private tabs | Fixed earlier; device: screenshots black while any private tab is open, normal after close |
| R2303-07 | Private address bar without no-personalized-learning | Fixed earlier; covered by PrivateFieldComposeTest and AddressBarPolicyTest |
| R2303-09 | History/cookie clear does not reset open tabs | Accepted as is (same as Chrome), user decision 2026-11-08 |
| R2303-10 | Private download names stay in the normal Downloads list | Being fixed in session 2 (hidden and removed from the list when private tabs close; files kept) |
| R1144-01, 04, 05 | Harness merge provenance, driver notes, gate re-derivation | Obsolete (harness retired) |
| R1144-02 | No positive control that a pending address is shown while loading | Closed: device check 2026-11-08 (/hang load shows the target, Stop reverts) + BrowserAddressBarComposeTest |
| R1144-03 | Bookmark clear leaves bookmarks.json.corrupt | Fixed in 6e0d99d (clear-all deletes the set-aside copy) |
