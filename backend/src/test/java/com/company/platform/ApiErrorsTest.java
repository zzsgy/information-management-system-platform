package com.company.platform;

import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.io.*;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实统一异常边界和日志脱敏；审计替身只用于注入记录失败，不连接业务库。 */
class ApiErrorsTest {
    @Test void distinguishesDatabaseConflictTimeoutAndUnexpectedFailure() {
        assertEquals("DUPLICATE_RECORD", ApiErrors.classify(new DataIntegrityViolationException("secret SQL",
                new SQLException("password=hidden", "23505"))).code);
        assertEquals("RECORD_IN_USE", ApiErrors.classify(new DataIntegrityViolationException("sql",
                new SQLException("sql", "23503"))).code);
        assertEquals("DATABASE_UNAVAILABLE", ApiErrors.classify(new DataAccessResourceFailureException("internal host")).code);
        assertEquals("DATABASE_TIMEOUT", ApiErrors.classify(new QueryTimeoutException("query")).code);
        assertEquals("DATABASE_QUERY_FAILED", ApiErrors.classify(new org.springframework.jdbc.BadSqlGrammarException(
                "select", "secret SQL", new SQLException("sql", "42601"))).code);
        var unknown = ApiErrors.classify(new NullPointerException("employee secret"));
        assertEquals("INTERNAL_ERROR", unknown.code);
        assertEquals(500, unknown.status);
        assertFalse(unknown.getMessage().contains("employee secret"));
    }

    @Test void responseAndAuditUseSameRequestAndOriginalBusinessCode() {
        UUID requestId = UUID.randomUUID();
        var request = new MockHttpServletRequest("POST", "/api/v1/test");
        request.setAttribute(HttpBoundary.REQUEST, requestId);
        var recorded = new ArrayList<UUID>();
        var audit = new Audit(null) {
            @Override public void denied(UUID actor, String action, UUID request) {
                assertEquals("STORAGE_INSTANCE_MISMATCH", action);
                recorded.add(request);
            }
        };
        var response = new ApiErrors(audit).handle(SystemFailure.STORAGE_INSTANCE_MISMATCH.fault(), request);
        assertEquals(503, response.getStatusCode().value());
        assertEquals("STORAGE_INSTANCE_MISMATCH", response.getBody().get("code"));
        assertEquals(requestId, response.getBody().get("requestId"));
        assertEquals(List.of(requestId), recorded);
    }

    @Test void auditFailureHasOwnCodeAndDetailedLogsExcludeSecrets() {
        UUID requestId = UUID.randomUUID();
        var audit = new Audit(null) {
            @Override public void denied(UUID actor, String action, UUID request) {
                throw new DataAccessResourceFailureException("password=very-secret");
            }
        };
        assertEquals("STORAGE_WRITE_FAILED", ApiErrors.record(audit, null,
                SystemFailure.STORAGE_WRITE_FAILED.fault(), requestId, "POST /test").code);
        assertEquals("AUDIT_WRITE_FAILED", ApiErrors.record(audit, null,
                ApiFault.denied(), requestId, "POST /test").code);
        var original = SystemFailure.STORAGE_WRITE_FAILED.fault(new IOException("D:/private/token.txt",
                new SQLException("password=very-secret", "08006", 7)));
        var log = new StringWriter();
        FailureDiagnostics.sanitized(original).printStackTrace(new PrintWriter(log));
        assertTrue(log.toString().contains("STORAGE_WRITE_FAILED"));
        assertTrue(log.toString().contains("java.io.IOException"));
        assertTrue(log.toString().contains("SQLState=08006"));
        assertTrue(log.toString().contains("ApiErrorsTest.java"));
        assertFalse(log.toString().contains("very-secret"));
        assertFalse(log.toString().contains("private/token.txt"));
    }

    @Test void asyncFailureEnvelopeIncludesExplanationAndTrace() {
        UUID requestId = UUID.randomUUID();
        var response = ApiController.envelope("OK", "操作成功",
                Map.of("state", "FAILED", "errorCode", "FILE_SUSPICIOUS"), requestId);
        var task = (Map<?,?>) response.get("data");
        assertTrue(task.get("errorMessage").toString().contains("可疑内容"));
        assertEquals(requestId, task.get("requestId"));
        assertEquals("FILE_SUSPICIOUS", task.get("errorCode"));
    }
}
