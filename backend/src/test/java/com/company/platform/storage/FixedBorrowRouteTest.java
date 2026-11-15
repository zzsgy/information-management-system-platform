package com.company.platform.storage;

import com.company.platform.workflow.FixedBorrowRoute;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定路线正反例：只应用确认过的档案员去重，不因同人兼任而省略其他必要职责。 */
class FixedBorrowRouteTest {
    @Test void generalCrossKeepsBothDepartmentManagerDuties() {UUID a=UUID.randomUUID(),b=UUID.randomUUID();var route=FixedBorrowRoute.plan("GENERAL",a,b,false);assertEquals(List.of("ARCHIVIST","DEPARTMENT_MANAGER","DEPARTMENT_MANAGER","ARCHIVIST","ARCHIVE_ADMIN"),route.stream().map(FixedBorrowRoute.Duty::code).toList());assertEquals(a,route.get(1).department());assertEquals(b,route.get(2).department());}
    @Test void importantAddsGeneralManagerBeforeArchiveAdministrator() {UUID a=UUID.randomUUID();assertEquals(List.of("ARCHIVIST","DEPARTMENT_MANAGER","GENERAL_MANAGER","ARCHIVE_ADMIN"),FixedBorrowRoute.plan("IMPORTANT",a,a,false).stream().map(FixedBorrowRoute.Duty::code).toList());}
    @Test void coreOnlyDeduplicatesSameDepartmentArchivist() {UUID a=UUID.randomUUID();assertEquals(List.of("ARCHIVIST","DEPARTMENT_MANAGER","GENERAL_MANAGER","ARCHIVE_ADMIN"),FixedBorrowRoute.plan("CORE",a,a,false).stream().map(FixedBorrowRoute.Duty::code).toList());}
    @Test void applicantArchivistSkipsOnlyOwnArchivistNode() {UUID a=UUID.randomUUID(),b=UUID.randomUUID();assertEquals(List.of("DEPARTMENT_MANAGER","DEPARTMENT_MANAGER","ARCHIVIST","ARCHIVE_ADMIN"),FixedBorrowRoute.plan("GENERAL",a,b,true).stream().map(FixedBorrowRoute.Duty::code).toList());assertEquals(List.of("DEPARTMENT_MANAGER","GENERAL_MANAGER","ARCHIVE_ADMIN"),FixedBorrowRoute.plan("CORE",a,a,true).stream().map(FixedBorrowRoute.Duty::code).toList());}
}
