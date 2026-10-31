package com.heavendoor.ultralab;

import android.content.Context;

/** Explicit legacy normal-mode entry point. All existing signature/UID/model checks remain. */
public final class UltraInstrumentDataService extends UltraDataService {
    public UltraInstrumentDataService(Context context) throws Exception {
        super(context, UltraDataMode.INSTRUMENT);
    }
}
