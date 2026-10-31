#!/usr/bin/env python3
"""Structural boundary regressions, not Android execution or a vehicle test."""
from pathlib import Path
base = Path(__file__).resolve().parents[1] / "common/src/main/java/com/heavendoor/ultralab"
data = (base / "UltraDataService.java").read_text()
assert "this(context,UltraDataMode.READ_ONLY)" in data
block = data.split("mode.startInstrument(()->{", 1)[1].split("});", 1)[0]
for resource in ("UltraTripReader", "UltraProjectionRunner", "UltraOutputRouter", "UltraComfortReader"):
    assert data.count("new " + resource) == 1
    assert "new " + resource in block
assert "private final UltraDataMode mode;" in data
assert "Build.VERSION.SDK_INT!=28" in data and '!"E28".equals(Build.MODEL)' in data
assert "!SIGNER.equals(hash.toString())" in data
assert "Binder.getCallingUid()!=clientUid" in data
normal = (base / "UltraInstrumentDataService.java").read_text()
assert "super(context, UltraDataMode.INSTRUMENT)" in normal
assert "new UltraInstrumentDataService(context)" in (base / "OwnRootService.java").read_text()
assert "new ComponentName(c,UltraInstrumentDataService.class)" in (base / "UltraEngine.java").read_text()
session = (base / "UltraReadOnlySession.java").read_text()
for forbidden in ("new UltraFrameServer", "new UltraTripReader", "new UltraProjectionRunner",
                  "FactoryRootClient.connect", ".setProjection", ".restartProjection",
                  ".requestPermission", "UltraEngine.get(", "UltraMapRelay."):
    assert forbidden not in session, forbidden
assert 'processNameSuffix("ultra-read-only")' in session
assert "UltraDataService.class" in session
print("Read-only source boundaries: passed (structural; not Android lifecycle execution)")
