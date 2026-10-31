package com.heavendoor.ultralab;

public final class UltraDataModeStandaloneTest {
    public static void main(String[] args) {
        int[] allocations = {0};
        UltraDataMode.READ_ONLY.startInstrument(() -> { throw new AssertionError("instrument allocated"); });
        for (int i = 0; i < 100; i++) UltraDataMode.READ_ONLY.startInstrument(() -> allocations[0]++);
        if (allocations[0] != 0 || UltraDataMode.READ_ONLY.instrumentAllowed())
            throw new AssertionError("read-only capability");
        UltraDataMode.INSTRUMENT.startInstrument(() -> allocations[0]++);
        if (allocations[0] != 1 || !UltraDataMode.INSTRUMENT.instrumentAllowed())
            throw new AssertionError("normal capability");
        System.out.println("UltraDataMode: 103 capability assertions passed");
    }
}
