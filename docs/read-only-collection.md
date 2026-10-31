# Explicit head-unit-only collection

This is a code/offline-test boundary, not evidence of Android 12/8295 or vehicle compatibility.
No new vehicle property signatures, areas, enums or VHAL configuration are inferred.

## Entry points

- `new UltraReadOnlySession(context)` is inert. Explicit `start()` requires an **already
  granted** Shizuku permission and an inactive normal Ultra engine. It does not prompt for
  permission or connect the factory-root helper.
- Call `snapshot()` on a background thread. Close with `close()` in the caller's lifecycle.
  Permission loss, disconnection or a failed Binder read terminates the session and returns
  an explicit disconnected snapshot. To retry, close the old session and create a new one;
  there is no automatic privilege acquisition or output-mode upgrade.
- This separate, opt-in diagnostic API is not automatically started or added to the UI.
  Do not start a normal session concurrently if performing a head-unit-only diagnostic.
  The API blocks an already-active normal engine but does not globally prevent another
  component from starting normal mode later.
- The default `UltraDataService(Context)` now has immutable `READ_ONLY` capability.
  It binds in a separate `ultra-read-only` process through the client above. Changing
  projection preferences or calling projection Binder methods cannot upgrade this instance.
- Existing normal Shizuku and factory-root flows explicitly construct/bind
  `UltraInstrumentDataService`. Normal output, OEM restore, firmware/signature/UID checks
  and trip collection remain unchanged. Normal mode OFF is still **not** a head-unit-only
  guarantee: its trip reader may query/deploy instrument helpers.

## Boundary

READ_ONLY never constructs `UltraTripReader`, `UltraProjectionRunner`, `UltraOutputRouter`
or their popup reader. Therefore construction, polling, snapshot, output-control calls and
shutdown have no instrument resources to start, query, deploy or restore. Its client never
creates `UltraEngine`, `UltraFrameServer`, map relays or output control calls. Vehicle
collection is still privileged and retains the existing Android 9/E28 guard. Unsupported
head units stay disconnected; no E28U implementation is fabricated.

Snapshots identify `collectionMode`, the verified legacy contract identifier and the
explicitly missing `vhalConfigurationEvidence` (`not-collected`). Returned rows carry their
declared getter area and observed scalar/array type. These are legacy getter-contract
markers, **not** a claimed VHAL configuration dump.

## Offline checks

Run `bash scripts/test-read-only-standalone.sh` with the existing JDK compiler module and
Python 3. It exercises the immutable allocation gate, structural production entry-point
boundaries, and the actual client lifecycle compiled against small Android/Shizuku fakes
(inert construction, permission refusal, normal-session refusal, failed bind, idempotent
start/close, disconnection, late callbacks, permission revocation and Binder read failure).
This is not an Android service lifecycle, signature-validation or actual vehicle test.
