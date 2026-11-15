package com.company.platform.archive;

import com.company.platform.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实入口规则，不复制业务判断；无需连接业务库。 */
class ArchivePreservationTest {
    @Test void paperAndMixedRequireExplicitTags() {
        for(String carrier:List.of("PAPER","MIXED")) {
            assertThrows(ApiFault.class,()->ArchivePreservation.normalize(carrier,new LinkedHashMap<>()));
            for(var tags:List.of(List.of("纸质原件"),List.of("纸质复印件"),List.of("纸质原件","纸质复印件"))) {
                var values=Sql.map("preservationType","旧值","paperTags",tags);
                ArchivePreservation.normalize(carrier,values);
                assertEquals(tags,values.get("paperTags"));
                assertEquals(carrier.equals("PAPER")?"纸质":"纸质+电子",values.get("preservationType"));
            }
        }
    }
    @Test void electronicDoesNotRequireOrAcceptPaperTags() {
        var values=Sql.map("preservationType","旧值");ArchivePreservation.normalize("ELECTRONIC",values);
        assertEquals("电子",values.get("preservationType"));assertEquals(List.of(),values.get("paperTags"));
        assertThrows(ApiFault.class,()->ArchivePreservation.normalize("ELECTRONIC",Sql.map("paperTags",List.of("纸质原件"))));
    }
    @Test void invalidAndDuplicateTagsAreRejected() {
        for(Object value:List.of("纸质原件",List.of("未知"),List.of("纸质原件","纸质原件")))assertThrows(ApiFault.class,()->ArchivePreservation.tags(value));
    }
}
