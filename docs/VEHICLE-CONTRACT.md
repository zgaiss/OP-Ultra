# Offline vehicle readiness contract

This change does **not** implement Android 12/8295 E28U property access. E28 and E28U
share the instrument side per the owner's platform information; head-unit APIs and
permissions still need independent evidence. Existing SDK/model, caller UID,
signature, firmware and OEM preservation safeguards remain in force.

`UltraVehicleReadiness` is the production dashboard's fail-closed readiness gate.
It accepts only the named `legacy-xp-vehicle-e28-v1` contract, emitted by the
existing E28/API 28 service after its existing platform guard. This identifier is
not a runtime VHAL discovery result and cannot be assigned to an unverified
adapter simply because numeric values look plausible.

Requirements:

- Typed schema 1, source `vehicle`, UID 0 or 2000, connected boolean true.
- Frame age 0–1500 ms, using a monotonic local receipt clock.
- Speed and gear have state `returned`, numeric scalar values, declared
  `valueType=number`, explicitly declared global getter `area=0` and integral
  nonnegative age. Numeric strings and missing/coerced metadata fail closed.
- Combined cached age plus receipt age is at most 750 ms for speed and 1000 ms
  for gear; future clock anchors and overflow-sized ages fail closed.
- Speed 0–300 km/h; gear integer 1/2/3/4 (D/N/R/P), **legacy contract only**.
- Existing fresh-map requirement remains necessary.

The area field records the legacy getter argument convention (zero means no
wheel argument), **not** a discovered VHAL area ID. No E28U property IDs, getter
signatures, units, enum mappings or area configurations are guessed. Snapshots
explicitly report `vhalConfigurationEvidence=not-collected`. Unknown contracts,
including any proposed E28U/VHAL contract, cannot pass readiness with this code.
Configuration dumps, observed types/areas and permission evidence are prerequisites
for a future separately reviewed adapter; missing evidence is not a default value.

## Offline verification

`scripts/test-vehicle-readiness-standalone.sh` exercises the exact pure production
boundary with synthetic valid, missing, wrong-type, wrong-area, expired, future,
overflow and reconnect/source-switch cases. `UltraVehicleReadinessDashboardTest`
also checks the actual JSON-to-dashboard boundary under Robolectric. Neither test
contacts a car or establishes head-unit/runtime compatibility. This contract does
not replace the existing output/firmware/OEM safeguards or imply projection is
safe merely because a data frame passes it.
