package com.heavendoor.ultralab;

import org.junit.Test;

/** Keep the Android Gradle unit-test suite and the dependency-free runner on identical checks. */
public final class UltraLyricsStateTest {
    @Test public void timelineRegressionSuite() {
        UltraLyricsStateStandaloneTest.main(new String[0]);
    }
}
