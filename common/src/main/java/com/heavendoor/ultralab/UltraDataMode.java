package com.heavendoor.ultralab;

/** Immutable capability: neither output preferences nor Binder commands can upgrade it. */
public enum UltraDataMode {
    READ_ONLY, INSTRUMENT;
    public boolean instrumentAllowed() { return this == INSTRUMENT; }
    void startInstrument(Runnable factory) {
        if (instrumentAllowed()) factory.run();
    }
}
