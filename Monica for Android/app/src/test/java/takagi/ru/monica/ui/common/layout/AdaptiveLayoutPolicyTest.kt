package takagi.ru.monica.ui.common.layout

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test fun mediumWindowUsesRailButDoesNotSqueezeTwoPanes() {
        assertEquals(AdaptivePanePolicy(false, false, 320), adaptivePanePolicy(599))
        assertEquals(AdaptivePanePolicy(true, false, 320), adaptivePanePolicy(600))
        assertFalse(adaptivePanePolicy(839).twoPanes)
        assertTrue(adaptivePanePolicy(840).twoPanes)
        assertTrue(adaptivePanePolicy(960).twoPanes)
        assertTrue(adaptivePanePolicy(1000).twoPanes)
        assertEquals(420, adaptivePanePolicy(1600).listWidth)
    }

    @Test fun bookHingeKeepsAllControlsOnAnUnoccludedPanel() {
        assertEquals(FoldRegion(0, 0, 490, 800), safeFoldRegion(1000, 800, FoldRegion(490, 0, 510, 800)))
        assertEquals(FoldRegion(410, 0, 1000, 800), safeFoldRegion(1000, 800, FoldRegion(390, 0, 410, 800)))
        assertEquals(FoldRegion(0, 0, 500, 800), safeFoldRegion(1000, 800, FoldRegion(500, 0, 500, 800)))
    }

    @Test fun tabletopUsesTheLargerPanelAndIgnoresNonIntersectingFeatures() {
        assertEquals(FoldRegion(0, 410, 800, 1000), safeFoldRegion(800, 1000, FoldRegion(0, 390, 800, 410)))
        assertEquals(FoldRegion(0, 0, 800, 490), safeFoldRegion(800, 1000, FoldRegion(0, 490, 800, 510)))
        assertEquals(FoldRegion(0, 0, 800, 1000), safeFoldRegion(800, 1000, FoldRegion(1100, 0, 1100, 1000)))
        assertEquals(FoldRegion(0, 0, 800, 1000), safeFoldRegion(800, 1000, null))
    }
}
