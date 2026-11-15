package com.company.platform.workflow;

import com.company.platform.ApiFault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 二进制单区间边界：尾部区间可用，溢出和多区间明确拒绝。 */
class EvidenceRangeTest {
    @Test void singleAndSuffixRanges(){assertArrayEquals(new int[]{0,9},EvidenceController.range("bytes=0-9",20));assertArrayEquals(new int[]{15,19},EvidenceController.range("bytes=-5",20));assertArrayEquals(new int[]{10,19},EvidenceController.range("bytes=10-",20));assertNull(EvidenceController.range(null,20));}
    @Test void invalidAndMultipleRangesFailClosed(){for(String value:new String[]{"bytes=-0","bytes=20-","bytes=9-1","bytes=0-1,4-5","bytes=999999999999999999999-"})assertEquals(416,assertThrows(ApiFault.class,()->EvidenceController.range(value,20)).status);}
}
