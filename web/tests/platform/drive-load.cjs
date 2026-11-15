/** 公盘本机压力验收：真实HTTPS、16MiB分片、双副本、分页与下载；只允许本批专用库。 */
const fs = require("node:fs"),
  path = require("node:path"),
  crypto = require("node:crypto"),
  assert = require("node:assert/strict");
const { request, expect } = require("@playwright/test"),
  maintenance = require("../../../scripts/platform/maintenance.cjs");
const configPath = process.env.IMS_PLATFORM_TEST_CONFIG,
  config = JSON.parse(fs.readFileSync(configPath, "utf8")),
  fixture = JSON.parse(
    fs.readFileSync(process.env.IMS_OFFICE_TEST_FIXTURE, "utf8"),
  ),
  out = path.resolve(process.env.IMS_DRIVE_LOAD_OUTPUT);
assert.match(
  config.IMS_PLATFORM_DB_URL,
  /^jdbc:postgresql:\/\/127\.0\.0\.1:55467\/ims_platform_dev_[0-9]+$/,
);
assert.equal(config.IMS_PLATFORM_PORT, "18467");
fs.mkdirSync(out, { recursive: true });
const base = "https://127.0.0.1:18467",
  hash = (b) => crypto.createHash("sha256").update(b).digest("hex"),
  reports = [],
  runId = crypto.randomUUID();
let client, headers;
async function call(route, method = "GET", data, extra = {}) {
  const response = await client.fetch(base + "/api/v1" + route, {
    method,
    headers: { ...headers, ...extra },
    data,
  });
  assert.ok(
    response.ok(),
    route + " " + response.status() + " " + (await response.text()),
  );
  return (await response.json()).data;
}
async function upload(index) {
  const bytes = Buffer.alloc(16 * 1024 * 1024, 97 + index);
  Buffer.from("IMS_LOCAL_STABILITY_" + runId + "\n").copy(bytes);
  const digest = hash(bytes),
    operation = crypto.randomUUID(),
    body = {
      module: "DRIVE",
      purpose: "DRIVE_FILE",
      businessContextId: fixture.folderId,
      originalName: "并发分片-" + runId + "-" + index + ".txt",
      expectedSize: bytes.length,
      expectedSha256: digest,
      expectedMediaType: "application/octet-stream",
    };
  const duplicate = await Promise.all(
    Array.from({ length: 5 }, () =>
      call("/storage/upload-sessions", "POST", body, {
        "X-Operation-Id": operation,
      }),
    ),
  );
  assert.equal(new Set(duplicate.map((r) => r.uploadId)).size, 1);
  const upload = duplicate[0];
  const part = async (n) => {
    const content = bytes.subarray(
      (n - 1) * upload.partSize,
      Math.min(n * upload.partSize, bytes.length),
    );
    const response = await client.put(
      base + `/api/v1/storage/upload-sessions/${upload.uploadId}/parts/${n}`,
      {
        headers: {
          ...headers,
          "Content-Type": "application/octet-stream",
          "Content-Range": `bytes ${(n - 1) * upload.partSize}-${(n - 1) * upload.partSize + content.length - 1}/${bytes.length}`,
          "X-Content-Sha256": hash(content),
        },
        data: content,
      },
    );
    assert.ok(response.ok(), "分片 " + response.status());
  };
  const begin = Date.now();
  await Promise.all([part(1), part(1), part(2)]);
  const completeId = crypto.randomUUID();
  const jobs = await Promise.all(
    Array.from({ length: 3 }, () =>
      call(
        `/storage/upload-sessions/${upload.uploadId}/complete`,
        "POST",
        { partCount: 2, finalSha256: digest },
        { "X-Operation-Id": completeId },
      ),
    ),
  );
  assert.equal(new Set(jobs.map((j) => j.jobId)).size, 1);
  await expect
    .poll(async () => (await call("/jobs/" + jobs[0].jobId)).state, {
      timeout: 120000,
    })
    .toBe("SUCCEEDED");
  const document = await call(
      "/drive/documents",
      "POST",
      {
        folderId: fixture.folderId,
        uploadSessionId: upload.uploadId,
        name: body.originalName,
      },
      { "X-Operation-Id": crypto.randomUUID() },
    ),
    detail = await call("/drive/documents/" + document.id);
  const download = await client.get(
    base +
      `/api/v1/drive/documents/${document.id}/download?versionId=${detail.currentVersion.id}`,
    { headers },
  );
  assert.equal(download.status(), 200);
  assert.equal(hash(await download.body()), digest);
  const resumed = await client.get(
    base +
      `/api/v1/drive/documents/${document.id}/download?versionId=${detail.currentVersion.id}`,
    {
      headers: {
        ...headers,
        Range: "bytes=8388608-",
        "If-Range": download.headers().etag,
      },
    },
  );
  assert.equal(resumed.status(), 206);
  assert.equal(hash(await resumed.body()), hash(bytes.subarray(8388608)));
  return {
    id: document.id,
    bytes: bytes.length,
    elapsedMs: Date.now() - begin,
    sha256: digest,
  };
}
(async () => {
  try {
    client = await request.newContext({ ignoreHTTPSErrors: true });
    headers = { Origin: base };
    await call("/auth/login", "POST", {
      employeeNo: fixture.leaderNo,
      password: fixture.password,
    });
    const me = await call("/auth/me");
    headers["X-CSRF-Token"] = me.csrfToken;
    const files = await Promise.all([0, 1, 2].map(upload));
    reports.push({ scenario: "three-parallel-16MiB-uploads", files });
    // 元数据规模夹具沿用既有集成测试方式，共享已验真实原件；新增独立子目录，不改变既有文件。
    const context = maintenance.settings(configPath),
      folder = crypto.randomUUID(),
      source = files[0].id;
    const seedSql = `BEGIN; INSERT INTO drive.folder(id,parent_id,zone,department_id,folder_level,name,name_key) SELECT '${folder}',id,zone,department_id,'CHILD','压力分页-${runId}','load-${runId}' FROM drive.folder WHERE id='${fixture.folderId}'; INSERT INTO drive.document(id,folder_id,publishing_department_id,uploaded_by,name,name_key) SELECT gen_random_uuid(),'${folder}',publishing_department_id,uploaded_by,'压力分页'||n||'.txt','load-'||n FROM drive.document CROSS JOIN generate_series(1,1000) n WHERE id='${source}'; INSERT INTO drive.document_version(id,document_id,version_no,object_id,source_type,created_by) SELECT gen_random_uuid(),d.id,1,v.object_id,'LOCAL_UPLOAD',d.uploaded_by FROM drive.document d CROSS JOIN drive.document_version v WHERE d.folder_id='${folder}' AND v.id=(SELECT current_version_id FROM drive.document WHERE id='${source}'); UPDATE drive.document d SET current_version_id=v.id FROM drive.document_version v WHERE v.document_id=d.id AND d.folder_id='${folder}'; INSERT INTO storage.object_reference(id,object_id,owner_type,owner_id,reference_kind) SELECT gen_random_uuid(),v.object_id,'DRIVE_VERSION',v.id,'BUSINESS' FROM drive.document_version v JOIN drive.document d ON d.id=v.document_id WHERE d.folder_id='${folder}'; COMMIT;`;
    const result = require("node:child_process").spawnSync(
      path.join(config.IMS_BACKUP_POSTGRES_BIN, "psql.exe"),
      [
        "-h",
        "127.0.0.1",
        "-p",
        "55467",
        "-U",
        config.IMS_PLATFORM_DB_USER,
        "-d",
        context.name,
        "-v",
        "ON_ERROR_STOP=1",
        "-qAt",
        "-f",
        "-",
      ],
      { env: context.env, input: seedSql, encoding: "utf8", windowsHide: true },
    );
    assert.equal(result.status, 0, result.stderr);
    for (const concurrency of [5, 10, 20]) {
      const times = [],
        errors = [],
        start = Date.now();
      let counter = 0;
      await Promise.all(
        Array.from({ length: concurrency }, async () => {
          while (counter++ < 100) {
            const at = Date.now();
            try {
              const page = await call(
                `/drive/documents?folderId=${folder}&pageSize=20&keyword=${encodeURIComponent("压力分页")}`,
              );
              assert.equal(page.total, 1000);
              assert.equal(page.items.length, 20);
            } catch (e) {
              errors.push(e.message);
            }
            times.push(Date.now() - at);
          }
        }),
      );
      times.sort((a, b) => a - b);
      assert.equal(errors.length, 0);
      reports.push({
        scenario: "permission-filtered-1000-documents",
        concurrency,
        requests: times.length,
        totalMs: Date.now() - start,
        p50Ms: times[Math.ceil(times.length * 0.5) - 1],
        p95Ms: times[Math.ceil(times.length * 0.95) - 1],
        maxMs: times.at(-1),
        errors,
      });
    }
    const document = files[0].id;
    const object = JSON.parse(
      maintenance.sql(
        context,
        `SELECT row_to_json(o) FROM storage.object o JOIN drive.document_version v ON v.object_id=o.id JOIN drive.document d ON d.current_version_id=v.id WHERE d.id='${document}'`,
      ),
    );
    for (const root of [
      config.IMS_PLATFORM_STORAGE_PRIMARY,
      config.IMS_PLATFORM_STORAGE_SECONDARY,
    ])
      assert.equal(
        maintenance.sha(path.join(root, object.storage_key)),
        files[0].sha256,
      );
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        { scope: "LOCAL_SYNTHETIC_ONLY", passed: true, reports },
        null,
        2,
      ),
    );
    console.log(JSON.stringify(reports, null, 2));
  } finally {
    if (client) await client.dispose();
  }
})().catch((e) => {
  console.error(e);
  fs.writeFileSync(
    path.join(out, "failure.json"),
    JSON.stringify({ passed: false, reports, error: e.message }, null, 2),
  );
  process.exitCode = 1;
});
