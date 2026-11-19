#!/usr/bin/env node
/** 本机隔离演练维护工具：只读检查、持锁备份、全新库恢复核对；不自动切换业务入口。 */
"use strict";
const fs = require("node:fs"),
  path = require("node:path"),
  crypto = require("node:crypto"),
  child = require("node:child_process"),
  net = require("node:net"),
  assert = require("node:assert/strict");
/** 参数只接受固定选项，口令读取私有配置，不接受命令行口令或任意命令模板。 */
function options(argv) {
  const mode = argv[0];
  assert.ok(
    ["check", "backup", "restore-verify"].includes(mode),
    "命令必须为check、backup或restore-verify",
  );
  const args = { mode };
  for (let index = 1; index < argv.length; index += 2) {
    const key = argv[index];
    assert.ok(
      [
        "--config",
        "--output",
        "--bundle",
        "--database",
        "--port",
        "--current-ledger",
      ].includes(key) &&
        argv[index + 1] &&
        !args[key],
      "未知、重复或缺少参数",
    );
    args[key] = argv[index + 1];
  }
  assert.ok(args["--config"], "必须提供私有配置文件");
  return args;
}
/** 固定JSON键顺序，用于比较清单与保护事实签名，不改变任何业务文档。 */
function canonical(value) {
  return Array.isArray(value)
    ? value.map(canonical)
    : value && typeof value === "object"
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((key) => [key, canonical(value[key])]),
        )
      : value;
}
/** 摘要按64KiB块流式计算，大附件不进入单个内存缓冲。 */
function sha(file) {
  const fd = fs.openSync(file, "r"),
    buffer = Buffer.alloc(65536),
    digest = crypto.createHash("sha256");
  try {
    let size;
    while ((size = fs.readSync(fd, buffer, 0, buffer.length, null)))
      digest.update(buffer.subarray(0, size));
    return digest.digest("hex");
  } finally {
    fs.closeSync(fd);
  }
}
/** 所有路径必须绝对、无链接；恢复和备份不跟随junction或符号链接。 */
function safe(value) {
  assert.ok(
    typeof value === "string" && path.isAbsolute(value),
    "必须配置绝对路径",
  );
  const resolved = path.resolve(value);
  for (let current = resolved; ; current = path.dirname(current)) {
    if (fs.existsSync(current))
      assert.ok(!fs.lstatSync(current).isSymbolicLink(), "路径不允许链接");
    if (path.dirname(current) === current) break;
  }
  return resolved;
}
function inside(parent, value) {
  const relative = path.relative(parent, value);
  return (
    relative === "" ||
    (!relative.startsWith(".." + path.sep) &&
      relative !== ".." &&
      !path.isAbsolute(relative))
  );
}
/** 新目录必须不存在，且限制当前账号访问；任何失败均保留不完整证据，不递归删除。 */
function privateDirectory(value) {
  const directory = safe(value);
  assert.ok(!fs.existsSync(directory), "目标目录已存在，拒绝覆盖");
  fs.mkdirSync(directory, { mode: 0o700 });
  if (process.platform === "win32") {
    const result = child.spawnSync(
      "C:/Windows/System32/icacls.exe",
      [
        directory,
        "/inheritance:r",
        "/grant:r",
        `${process.env.USERDOMAIN}\\${process.env.USERNAME}:(OI)(CI)F`,
      ],
      { windowsHide: true, stdio: "ignore" },
    );
    assert.equal(result.status, 0, "无法限制演练目录权限");
  }
  return directory;
}
/** 仅接受独立本机平台库，业务数据库、远程地址与空口令均拒绝。 */
function settings(file) {return settingsFromConfig(JSON.parse(fs.readFileSync(safe(file), "utf8")));}
function settingsFromConfig(config) {
  const url = new URL(config.IMS_PLATFORM_DB_URL.replace(/^jdbc:/, ""));
  assert.equal(url.protocol, "postgresql:");
  assert.ok(["127.0.0.1", "localhost"].includes(url.hostname));
  if(config.IMS_BACKUP_SOURCE_DATABASE){
    assert.match(config.IMS_BACKUP_SOURCE_DATABASE,/^[A-Za-z0-9_]+$/);
    assert.equal(url.pathname,"/"+config.IMS_BACKUP_SOURCE_DATABASE,"源库与明确配置的库名不一致");
  }else assert.match(url.pathname, /^\/ims_platform_(dev|test|trial)[a-z0-9_]*$/);
  assert.ok(
    config.IMS_PLATFORM_DB_USER && config.IMS_PLATFORM_DB_PASSWORD,
    "数据库身份未配置",
  );
  assert.ok(
    Number.isInteger(Number(config.IMS_PLATFORM_PORT)) &&
      Number(config.IMS_PLATFORM_PORT) > 1024 &&
      Number(config.IMS_PLATFORM_PORT) < 65536,
    "必须明确配置本批应用监听端口",
  );
  const bin = safe(config.IMS_BACKUP_POSTGRES_BIN),
    roots = {};
  for (const [label, key] of Object.entries({
    primary: "IMS_PLATFORM_STORAGE_PRIMARY",
    secondary: "IMS_PLATFORM_STORAGE_SECONDARY",
    ledger: "IMS_PLATFORM_LEDGER_ROOT",
    audit: "IMS_PLATFORM_READING_AUDIT",
    exportsPrimary: "IMS_PLATFORM_EXPORT_PRIMARY",
    exportsSecondary: "IMS_PLATFORM_EXPORT_SECONDARY",
    businessMail: "IMS_PLATFORM_NOTIFICATIONS_LOCAL_DIRECTORY",
    credentialMail: "IMS_PLATFORM_MAILBOX_ROOT",
    auditArchivePrimary: "IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY",
    auditArchiveSecondary: "IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY",
  })) {
    if (config[key]) roots[label] = safe(config[key]);
  }
  for (const required of ["primary", "secondary", "ledger"])
    assert.ok(roots[required], "缺少必要持久目录：" + required);
  assert.equal(
    Boolean(roots.auditArchivePrimary),
    Boolean(roots.auditArchiveSecondary),
    "日志归档必须同时备份两个目录",
  );
  const directories = Object.values(roots);
  for (let first = 0; first < directories.length; first++)
    for (let second = first + 1; second < directories.length; second++)
      assert.ok(
        !inside(directories[first], directories[second]) &&
          !inside(directories[second], directories[first]),
        "持久目录不能相同或互相嵌套",
      );
  return {
    config,
    url,
    bin,
    roots,
    name: url.pathname.slice(1),
    env: {
      ...process.env,
      PGPASSWORD: config.IMS_PLATFORM_DB_PASSWORD,
      PGCLIENTENCODING: "UTF8",
      PGOPTIONS: "-c timezone=UTC -c DateStyle=ISO,YMD",
    },
  };
}
function executable(context, name) {
  return path.join(
    context.bin,
    name + (process.platform === "win32" ? ".exe" : ""),
  );
}
function connection(context, database = context.name) {
  return [
    "-h",
    context.url.hostname,
    "-p",
    context.url.port || "5432",
    "-U",
    context.config.IMS_PLATFORM_DB_USER,
    "-d",
    database,
  ];
}
/** 工具错误不把SQL、外部标准错误或私有配置直接输出给用户。 */
function run(context, name, args) {
  const result = child.spawnSync(executable(context, name), args, {
    env: context.env,
    windowsHide: true,
    encoding: "utf8",
    maxBuffer: 8 * 1024 * 1024,
  });
  if(result.status!==0&&context.config.IMS_OPS_RUN_ROOT){
    let detail=String(result.stderr||result.error?.message||"");
    for(const key of ["IMS_PLATFORM_DB_PASSWORD","IMS_RESTORE_DB_PASSWORD","IMS_RESTORE_APP_PASSWORD","IMS_PLATFORM_TLS_PASSWORD"]){const secret=context.config[key];if(secret)detail=detail.replaceAll(String(secret),"[REDACTED]");}
    const logRoot=safe(context.config.IMS_OPS_RUN_ROOT);
    if(fs.existsSync(logRoot))fs.appendFileSync(path.join(logRoot,"database-tools.private.log"),JSON.stringify({at:new Date().toISOString(),tool:name,exit:result.status,detail})+"\n",{mode:0o600});
  }
  assert.equal(result.status, 0, "数据库工具执行失败：" + name);
  return result.stdout.trim();
}
function sql(context, query, database = context.name) {
  return run(context, "psql", [
    "-X",
    "-qAt",
    ...connection(context, database),
    "-v",
    "ON_ERROR_STOP=1",
    "-c",
    query,
  ]);
}
/** 已完成归档必须与数据库登记的摘要、清单一致，不能把受损归档重新备份成“正常副本”。 */
function verifyAuditArchives(context, database = context.name) {
  if (
    !sql(context, "SELECT to_regclass('ops.audit_archive_run')::text", database)
  )
    return 0;
  const rows = JSON.parse(
    sql(
      context,
      "SELECT COALESCE(json_agg(t),'[]'::json)::text FROM (SELECT id,month_start,sha256,byte_size,row_counts FROM ops.audit_archive_run WHERE state='ARCHIVED' ORDER BY id)t",
      database,
    ),
  );
  for (const row of rows) {
    assert.match(row.id, /^[0-9a-f-]{36}$/);
    for (const label of ["auditArchivePrimary", "auditArchiveSecondary"]) {
      assert.ok(context.roots[label], "已完成日志归档缺少副本目录");
      const directory = path.join(context.roots[label], row.id),
        file = safe(path.join(directory, "records.jsonl.gz")),
        manifest = JSON.parse(
          fs.readFileSync(safe(path.join(directory, "manifest.json")), "utf8"),
        );
      assert.equal(sha(file), row.sha256, "日志归档副本摘要不符");
      assert.equal(
        fs.statSync(file).size,
        Number(row.byte_size),
        "日志归档副本长度不符",
      );
      assert.equal(manifest.format, 1);
      assert.equal(manifest.id, row.id);
      assert.equal(manifest.month, row.month_start);
      assert.equal(manifest.sha256, row.sha256);
      assert.deepEqual(
        manifest.rowCounts,
        row.row_counts,
        "日志归档清单数量不符",
      );
    }
  }
  return rows.length;
}
function identifier(value) {
  assert.match(value, /^[a-z_][a-z0-9_]*$/);
  return '"' + value + '"';
}
/** 已注册模式中的基本表及主键决定顺序，恢复前后按同一顺序逐行SHA-256比较。 */
function tables(context, database = context.name) {
  return JSON.parse(
    sql(
      context,
      "SELECT COALESCE(json_agg(x ORDER BY x.schema,x.name),'[]'::json)::text FROM (SELECT n.nspname AS schema,c.relname AS name,COALESCE((SELECT json_agg(a.attname ORDER BY keys.ord) FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY keys(num,ord) JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=keys.num WHERE i.indrelid=c.oid AND i.indisprimary),'[]'::json) AS keys FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='r' AND n.nspname IN ('platform_meta','iam','archive','workflow','storage','ops','portal','drive'))x",
      database,
    ),
  );
}
/** COPY以流处理，不把审计历史或全部行聚合进数据库/Node单个JSON对象。 */
async function facts(context, table, database = context.name) {
  const qualified = identifier(table.schema) + "." + identifier(table.name),
    order = table.keys.length
      ? table.keys.map(identifier).join(",")
      : "to_jsonb(t)::text",
    query = `COPY(SELECT to_jsonb(t)::text FROM ${qualified} t ORDER BY ${order}) TO STDOUT`;
  return new Promise((resolve, reject) => {
    const processHandle = child.spawn(
        executable(context, "psql"),
        [
          "-X",
          "-qAt",
          ...connection(context, database),
          "-v",
          "ON_ERROR_STOP=1",
          "-c",
          query,
        ],
        {
          env: context.env,
          windowsHide: true,
          stdio: ["ignore", "pipe", "ignore"],
        },
      ),
      hash = crypto.createHash("sha256");
    let rows = 0;
    processHandle.stdout.on("data", (bytes) => {
      hash.update(bytes);
      for (const value of bytes) if (value === 10) rows++;
    });
    processHandle.on("error", () => reject(Error("无法读取恢复核对事实")));
    processHandle.on("exit", (code) =>
      code === 0
        ? resolve({ ...table, rows, sha256: hash.digest("hex") })
        : reject(Error("恢复核对查询失败")),
    );
  });
}
/** 逐个核验READY对象的数据库摘要、长度和两份字节；COPY流读取避免百万对象聚合进内存。 */
async function verifyObjects(
  context,
  primary,
  secondary,
  database = context.name,
) {
  const instance = sql(
    context,
    "SELECT id FROM platform_meta.instance",
    database,
  );
  for (const root of [primary, secondary])
    assert.equal(
      fs.readFileSync(path.join(root, "platform-instance"), "utf8"),
      instance,
      "原件根目录与平台实例不配对",
    );
  const processHandle = child.spawn(
      executable(context, "psql"),
      [
        "-X",
        "-qAt",
        ...connection(context, database),
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        "COPY(SELECT jsonb_build_object('key',storage_key,'bytes',byte_size,'sha256',sha256)::text FROM storage.object WHERE state='READY' ORDER BY id) TO STDOUT",
      ],
      {
        env: context.env,
        windowsHide: true,
        stdio: ["ignore", "pipe", "ignore"],
      },
    ),
    closed = new Promise((resolve, reject) => {
      processHandle.on("error", () => reject(Error("无法核验原件目录")));
      processHandle.on("exit", (code) =>
        code === 0 ? resolve() : reject(Error("原件核对查询失败")),
      );
    });
  let count = 0;
  try {
    for await (const line of require("node:readline").createInterface({
      input: processHandle.stdout,
      crlfDelay: Infinity,
    })) {
      const object = JSON.parse(line);
      assert.match(
        object.key,
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
      );
      assert.match(object.sha256, /^[0-9a-f]{64}$/);
      for (const root of [primary, secondary]) {
        const file = safe(path.join(root, object.key));
        assert.ok(fs.statSync(file).isFile());
        assert.equal(
          fs.statSync(file).size,
          Number(object.bytes),
          "原件长度与目录不符",
        );
        assert.equal(sha(file), object.sha256, "原件字节与数据库摘要不符");
      }
      count++;
    }
    await closed;
    return count;
  } catch (error) {
    processHandle.kill();
    await closed.catch(() => {});
    throw error;
  }
}
/** 文件清单只收录普通文件，摘要覆盖全部根目录内容和配对标识。 */
function fileList(directory, prefix = "") {
  return fs
    .readdirSync(directory, { withFileTypes: true })
    .flatMap((entry) => {
      const relative = prefix + entry.name,
        full = path.join(directory, entry.name);
      assert.ok(!entry.isSymbolicLink(), "备份目录中存在链接");
      return entry.isDirectory()
        ? fileList(full, relative + "/")
        : [
            {
              path: relative,
              bytes: fs.statSync(full).size,
              sha256: sha(full),
            },
          ];
    })
    .sort((a, b) => a.path.localeCompare(b.path, "en"));
}
/** 先验证清单路径，不能用篡改后的恢复清单越出备份根目录。 */
function verifyFiles(directory, entries) {
  const actual = fileList(directory);
  assert.deepEqual(actual, entries, "备份对象缺失、被篡改或多出未登记对象");
  for (const entry of entries) {
    const target = path.resolve(directory, entry.path);
    assert.ok(inside(directory, target), "备份对象越出根目录");
  }
}
function listening(port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host: "127.0.0.1", port: Number(port) });
    socket.on("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.on("error", () => resolve(false));
    socket.setTimeout(1000, () => {
      socket.destroy();
      resolve(true);
    });
  });
}
/** 持有全部平台表SHARE锁至数据库与不可变对象复制完成；导出快照供pg_dump使用。 */
async function freeze(context, catalog) {
  return new Promise((resolve, reject) => {
    const processHandle = child.spawn(
      executable(context, "psql"),
      ["-X", "-qAt", ...connection(context), "-v", "ON_ERROR_STOP=1"],
      {
        env: context.env,
        windowsHide: true,
        stdio: ["pipe", "pipe", "ignore"],
      },
    );
    let text = "",
      settled = false;
    const timer = setTimeout(() => {
      processHandle.kill();
      if (!settled) reject(Error("备份一致性锁未在15秒内取得"));
    }, 15000);
    processHandle.stdout.on("data", (bytes) => {
      text += bytes.toString("utf8");
      const match = text.match(/IMS_SNAPSHOT:([0-9A-Fa-f-]+)\r?\n/);
      if (match && !settled) {
        settled = true;
        clearTimeout(timer);
        resolve({
          snapshot: match[1],
          release: () => {
            processHandle.stdin.end("ROLLBACK;\n");
          },
        });
      }
    });
    processHandle.on("exit", () => {
      clearTimeout(timer);
      if (!settled) reject(Error("无法锁定平台备份边界"));
    });
    processHandle.on("error", () => {
      clearTimeout(timer);
      reject(Error("无法启动一致性锁连接"));
    });
    processHandle.stdin.write(
      "BEGIN ISOLATION LEVEL REPEATABLE READ; SET LOCAL lock_timeout='10s'; LOCK TABLE " +
        catalog
          .map(
            (table) => identifier(table.schema) + "." + identifier(table.name),
          )
          .join(",") +
        " IN SHARE MODE; SELECT 'IMS_SNAPSHOT:'||pg_export_snapshot();\n",
    );
  });
}
/** 除本机候选库外不承诺生产可用，检查不会修改病毒库或伪造保护事实。 */
async function check(context) {
  const auditArchivesVerified = verifyAuditArchives(context);
  const missing = [];
  for (const name of ["pg_dump", "pg_restore", "psql", "createdb"])
    if (!fs.existsSync(executable(context, name))) missing.push(name);
  const instance = sql(context, "SELECT id FROM platform_meta.instance"),
    rootStates = Object.fromEntries(
      Object.entries(context.roots).map(([key, value]) => [
        key,
        fs.existsSync(value),
      ]),
    );
  let protection = "UNAVAILABLE";
  try {
    const value = JSON.parse(
        fs.readFileSync(
          safe(context.config.IMS_PLATFORM_PROTECTION_EVIDENCE),
          "utf8",
        ),
      ),
      signature = value.signature;
    delete value.signature;
    const expected = crypto
      .createHmac(
        "sha256",
        fs.readFileSync(
          safe(context.config.IMS_PLATFORM_PROTECTION_KEY),
          "utf8",
        ),
      )
      .update(JSON.stringify(canonical(value)))
      .digest("hex");
    protection =
      signature === expected &&
      value.instanceId === instance &&
      value.nodeId === context.config.IMS_PLATFORM_NODE_ID &&
      Date.parse(value.expiresAt) > Date.now()
        ? "SIGNED_CURRENT_LOCAL_EVIDENCE"
        : "INVALID_OR_EXPIRED";
  } catch {}
  const scanner = context.config.IMS_PLATFORM_SCANNER_DATABASE,
    main =
      scanner &&
      ["main.cvd", "main.cld"].some((name) =>
        fs.existsSync(path.join(safe(scanner), name)),
      ),
    daily =
      scanner &&
      ["daily.cvd", "daily.cld"].find((name) =>
        fs.existsSync(path.join(safe(scanner), name)),
      ),
    fresh =
      daily &&
      Date.now() - fs.statSync(path.join(scanner, daily)).mtimeMs <
        7 * 86400000;
  return {
    scope: "LOCAL_ISOLATED_REHEARSAL",
    schemaVersion: sql(
      context,
      "SELECT max(version::integer) FROM platform_meta.flyway_schema_history WHERE success AND version IS NOT NULL",
    ),
    databaseReachable: true,
    auditArchivesVerified,
    requiredToolsPresent: missing.length === 0,
    missingTools: missing,
    persistentDirectories: rootStates,
    storageDirectoriesDistinct: true,
    independentPhysicalMediaVerified: false,
    scanner:
      main && fresh
        ? "OFFICIAL_CANDIDATES_PRESENT_REAL_SCAN_REQUIRED"
        : "OFFICIAL_DATABASE_UNAVAILABLE",
    protection,
    notificationChannel: "LOCAL_DEVELOPMENT_ONLY",
    productionHaVerified: false,
  };
}
async function backup(context, args) {
  assert.ok(args["--output"], "必须提供全新备份目录");
  assert.ok(
    !(await listening(context.config.IMS_PLATFORM_PORT)),
    "备份演练要求先停止本批应用，拒绝把在线状态当作静止快照",
  );
  const output = safe(args["--output"]);
  for (const value of Object.values(context.roots))
    assert.ok(
      !inside(value, output) && !inside(output, value),
      "备份目标与持久目录不能嵌套",
    );
  privateDirectory(output);
  let lock;
  try {
    const catalog = tables(context);
    assert.ok(catalog.length > 20, "目标库缺少完整平台结构");
    lock = await freeze(context, catalog);
    verifyAuditArchives(context);
    run(context, "pg_dump", [
      ...connection(context),
      "--format=custom",
      "--no-owner",
      "--no-acl",
      "--snapshot=" + lock.snapshot,
      "--lock-wait-timeout=10s",
      "--file=" + path.join(output, "database.dump"),
    ]);
    run(context, "pg_restore", ["--list", path.join(output, "database.dump")]);
    const originalObjects = await verifyObjects(
      context,
      context.roots.primary,
      context.roots.secondary,
    );
    const tableFacts = [];
    for (const table of catalog) tableFacts.push(await facts(context, table));
    const directories = {};
    for (const [label, value] of Object.entries(context.roots)) {
      assert.ok(fs.existsSync(value), "持久目录不存在：" + label);
      const destination = path.join(output, label);
      fs.cpSync(value, destination, {
        recursive: true,
        errorOnExist: true,
        preserveTimestamps: true,
      });
      directories[label] = fileList(value);
      verifyFiles(destination, directories[label]);
    }
    assert.ok(
      !(await listening(context.config.IMS_PLATFORM_PORT)),
      "备份期间应用重新启动，拒绝发布一致性清单",
    );
    const privateFiles=[];
    const privateRoot=path.join(output,"privateConfiguration");
    for(const key of ["IMS_MAINTENANCE_BOOTSTRAP_FILE","IMS_MAINTENANCE_RESTORE_FILE","IMS_PLATFORM_TLS_KEYSTORE","IMS_PLATFORM_OFFICE_INBOX_KEY","IMS_PLATFORM_OFFICE_OUTBOX_KEY","IMS_PLATFORM_PROTECTION_KEY"]){
      if(!context.config[key])continue;
      const source=safe(context.config[key]);assert.ok(fs.statSync(source).isFile(),"配置依赖文件不存在："+key);
      if(!fs.existsSync(privateRoot))privateDirectory(privateRoot);
      const name=key+".bin", destination=path.join(privateRoot,name);fs.copyFileSync(source,destination,fs.constants.COPYFILE_EXCL);
      assert.equal(sha(source),sha(destination));privateFiles.push({key,name,sha256:sha(destination)});
    }
    const manifest = {
      format: 1,
      privateFiles,
      scope: "LOCAL_ISOLATED_REHEARSAL",
      sourceDatabase: context.name,
      instance: sql(context, "SELECT id FROM platform_meta.instance"),
      schemaVersion: sql(
        context,
        "SELECT max(version::integer) FROM platform_meta.flyway_schema_history WHERE success AND version IS NOT NULL",
      ),
      dumpSha256: sha(path.join(output, "database.dump")),
      tables: tableFacts,
      directories,
      originalObjects,
      createdAt: new Date().toISOString(),
      state: "VERIFIED_BACKUP",
      productionHaVerified: false,
    };
    fs.writeFileSync(
      path.join(output, "manifest.json"),
      JSON.stringify(manifest, null, 2),
      { flag: "wx", mode: 0o600 },
    );
    return {
      scope: manifest.scope,
      state: manifest.state,
      tables: catalog.length,
      originalObjects,
      files: Object.values(directories).reduce(
        (sum, entries) => sum + entries.length,
        0,
      ),
      dumpSha256: manifest.dumpSha256,
    };
  } finally {
    lock?.release();
  }
}
/** 目标必须是另一端口的本机隔离实例，维护身份与应用账号分别配置。 */
function restoreTarget(context) {
  const config = context.config;
  for (const key of [
    "IMS_RESTORE_DB_URL",
    "IMS_RESTORE_DB_USER",
    "IMS_RESTORE_DB_PASSWORD",
    "IMS_RESTORE_APP_USER",
    "IMS_RESTORE_APP_PASSWORD",
  ])
    assert.ok(config[key], "缺少独立恢复配置：" + key);
  const url = new URL(config.IMS_RESTORE_DB_URL.replace(/^jdbc:/, ""));
  assert.equal(url.protocol, "postgresql:");
  assert.ok(
    ["127.0.0.1", "localhost"].includes(url.hostname) &&
      url.pathname === "/postgres" &&
      (url.port || "5432") !== (context.url.port || "5432"),
    "恢复必须使用另一实例的维护库",
  );
  assert.match(config.IMS_RESTORE_APP_USER, /^[a-z_][a-z0-9_]*$/);
  return {
    ...context,
    url,
    name: "postgres",
    config: {
      ...config,
      IMS_PLATFORM_DB_USER: config.IMS_RESTORE_DB_USER,
      IMS_PLATFORM_DB_PASSWORD: config.IMS_RESTORE_DB_PASSWORD,
    },
    env: { ...context.env, PGPASSWORD: config.IMS_RESTORE_DB_PASSWORD },
  };
}
/** 恢复前逐对象检查；新库、新目录、当前收紧账本缺一不可，不覆盖来源或备份。 */
async function restore(context, args) {
  const target = restoreTarget(context);
  // 恢复实例保持未开放，源演练入口也必须先隔离，避免两个实例同时提供业务。
  assert.ok(
    !(await listening(context.config.IMS_PLATFORM_PORT)),
    "恢复演练必须先停止源应用入口",
  );
  for (const key of [
    "--bundle",
    "--output",
    "--database",
    "--port",
    "--current-ledger",
  ])
    assert.ok(args[key], "恢复缺少参数：" + key);
  const bundle = safe(args["--bundle"]),
    output = safe(args["--output"]),
    database = args["--database"],
    currentLedger = safe(args["--current-ledger"]);
  assert.match(database, /^ims_platform_test_restore_[a-z0-9_]+$/);
  assert.notEqual(database, context.name);
  assert.ok(!inside(bundle, output) && !inside(output, bundle));
  for (const value of Object.values(context.roots))
    assert.ok(
      !inside(value, output) && !inside(output, value),
      "恢复目录与来源持久目录重叠",
    );
  assert.ok(
    !inside(currentLedger, output) && !inside(output, currentLedger),
    "不能覆盖当前收紧账本",
  );
  assert.ok(
    !inside(bundle, currentLedger) &&
      path.relative(context.roots.ledger, currentLedger) === "",
    "必须使用配置中的独立当前收紧账本，不能把备份内账本当作当前水位",
  );
  const port = Number(args["--port"]);
  assert.ok(
    Number.isInteger(port) &&
      port > 1024 &&
      port < 65536 &&
      String(port) !== String(context.config.IMS_PLATFORM_PORT) &&
      !(await listening(port)),
    "恢复应用端口无效或已占用",
  );
  assert.equal(
    sql(
      target,
      "SELECT count(*) FROM pg_database WHERE datname='" + database + "'",
    ),
    "0",
    "恢复库已存在，拒绝覆盖",
  );
  const manifest = JSON.parse(
    fs.readFileSync(path.join(bundle, "manifest.json"), "utf8"),
  );
  assert.equal(manifest.format, 1);
  assert.equal(manifest.state, "VERIFIED_BACKUP");
  assert.deepEqual(
    Object.keys(manifest.directories).sort(),
    Object.keys(context.roots).sort(),
    "备份持久目录集合不完整",
  );
  assert.equal(
    sha(path.join(bundle, "database.dump")),
    manifest.dumpSha256,
    "备份数据库摘要不符",
  );
  for (const [label, entries] of Object.entries(manifest.directories)) {
    assert.match(label, /^[A-Za-z]+$/);
    verifyFiles(path.join(bundle, label), entries);
  }
  for(const file of manifest.privateFiles||[]){
    assert.match(file.name,/^[A-Z_]+\.bin$/);assert.equal(sha(safe(path.join(bundle,"privateConfiguration",file.name))),file.sha256,"私有配置文件摘要不符");
  }
  const sourceMarker = fs.readFileSync(
    path.join(currentLedger, "instance"),
    "utf8",
  );
  assert.equal(sourceMarker, manifest.instance, "当前收紧账本不属于该平台");
  assert.equal(
    sha(path.join(currentLedger, "key")),
    sha(path.join(bundle, "ledger", "key")),
    "账本密钥代次不同，需专门恢复协调",
  );
  const checkpoint = fs.readFileSync(
      path.join(currentLedger, "checkpoint"),
      "utf8",
    ),
    backupCheckpoint = fs.readFileSync(
      path.join(bundle, "ledger", "checkpoint"),
      "utf8",
    );
  assert.ok(
    Number(checkpoint.split("\n")[0]) >=
      Number(backupCheckpoint.split("\n")[0]),
    "当前收紧账本水位低于备份，拒绝恢复",
  );
  privateDirectory(output);
  if(manifest.privateFiles?.length){
    const privateRoot=path.join(output,"privateConfiguration");privateDirectory(privateRoot);
    for(const file of manifest.privateFiles)fs.copyFileSync(path.join(bundle,"privateConfiguration",file.name),path.join(privateRoot,file.name),fs.constants.COPYFILE_EXCL);
  }
  run(target, "createdb", [
    "-h",
    target.url.hostname,
    "-p",
    target.url.port || "5432",
    "-U",
    target.config.IMS_PLATFORM_DB_USER,
    "--template=template0",
    "--owner=" + context.config.IMS_RESTORE_APP_USER,
    database,
  ]);
  run(target, "pg_restore", [
    ...connection(target, database),
    "--role=" + context.config.IMS_RESTORE_APP_USER,
    "--exit-on-error",
    "--no-owner",
    "--no-acl",
    path.join(bundle, "database.dump"),
  ]);
  const recovered = tables(target, database);
  assert.deepEqual(
    recovered,
    manifest.tables.map(({ schema, name, keys }) => ({ schema, name, keys })),
    "恢复表或主键结构不符",
  );
  for (const table of manifest.tables)
    assert.deepEqual(
      await facts(target, table, database),
      table,
      "恢复业务记录摘要不符：" + table.schema + "." + table.name,
    );
  for (const label of Object.keys(manifest.directories)) {
    const source =
      label === "ledger" ? currentLedger : path.join(bundle, label);
    fs.cpSync(source, path.join(output, label), {
      recursive: true,
      errorOnExist: true,
      preserveTimestamps: true,
    });
    if (label !== "ledger")
      verifyFiles(path.join(output, label), manifest.directories[label]);
  }
  assert.equal(
    fs.readFileSync(path.join(currentLedger, "checkpoint"), "utf8"),
    checkpoint,
    "复制期间收紧水位变化，请保留证据后重新演练",
  );
  assert.equal(
    sha(path.join(output, "ledger", "events.jsonl")),
    sha(path.join(currentLedger, "events.jsonl")),
    "当前收紧账本复制不完整",
  );
  const originalObjects = await verifyObjects(
    target,
    path.join(output, "primary"),
    path.join(output, "secondary"),
    database,
  );
  assert.equal(originalObjects, manifest.originalObjects, "恢复原件数量不符");
  const auditArchivesVerified = verifyAuditArchives(
    {
      ...target,
      roots: {
        auditArchivePrimary: path.join(output, "auditArchivePrimary"),
        auditArchiveSecondary: path.join(output, "auditArchiveSecondary"),
      },
    },
    database,
  );
  const restored = {
    ...context.config,
    IMS_PLATFORM_DB_URL:
      "jdbc:postgresql://" +
      target.url.hostname +
      ":" +
      (target.url.port || "5432") +
      "/" +
      database,
    IMS_PLATFORM_DB_USER: context.config.IMS_RESTORE_APP_USER,
    IMS_PLATFORM_DB_PASSWORD: context.config.IMS_RESTORE_APP_PASSWORD,
    IMS_PLATFORM_PORT: String(port),
    IMS_PLATFORM_LEDGER_ROOT: path.join(output, "ledger"),
    IMS_PLATFORM_ISOLATED_RESTORE: "true",
    IMS_PLATFORM_WORKER_ENABLED: "false",
    IMS_PLATFORM_SCAN_WORKER_ENABLED: "false",
    IMS_PLATFORM_OPERATIONS_ENABLED: "false",
    IMS_PLATFORM_ACCOUNT_MESSAGES_SCHEDULE_ENABLED: "false",
    IMS_PLATFORM_BORROW_LIFECYCLE_ENABLED: "false",
    IMS_PLATFORM_NOTIFICATIONS_ENABLED: "false",
    IMS_PLATFORM_AUDIT_STORAGE_ENABLED: "false",
    IMS_PLATFORM_AUDIT_DATABASE_VOLUME: "",
    IMS_PLATFORM_PROTECTION_ENABLED: "true",
    IMS_PLATFORM_PROTECTION_EVIDENCE: path.join(
      output,
      "unconfigured-protection.json",
    ),
    IMS_PLATFORM_PROTECTION_KEY: path.join(
      output,
      "unconfigured-protection.key",
    ),
    IMS_PLATFORM_NODE_ID: "isolated-recovery-unopened",
  };
  for (const [label, key] of Object.entries({
    primary: "IMS_PLATFORM_STORAGE_PRIMARY",
    secondary: "IMS_PLATFORM_STORAGE_SECONDARY",
    audit: "IMS_PLATFORM_READING_AUDIT",
    exportsPrimary: "IMS_PLATFORM_EXPORT_PRIMARY",
    exportsSecondary: "IMS_PLATFORM_EXPORT_SECONDARY",
    businessMail: "IMS_PLATFORM_NOTIFICATIONS_LOCAL_DIRECTORY",
    credentialMail: "IMS_PLATFORM_MAILBOX_ROOT",
    auditArchivePrimary: "IMS_PLATFORM_AUDIT_ARCHIVE_PRIMARY",
    auditArchiveSecondary: "IMS_PLATFORM_AUDIT_ARCHIVE_SECONDARY",
  }))
    if (manifest.directories[label]) restored[key] = path.join(output, label);
  for (const key of Object.keys(restored))
    if (key.startsWith("IMS_RESTORE_")) delete restored[key];
  fs.writeFileSync(
    path.join(output, "restored.private.json"),
    JSON.stringify(restored, null, 2),
    { flag: "wx", mode: 0o600 },
  );
  const report = {
    scope: "LOCAL_ISOLATED_REHEARSAL",
    state: "RESTORED_VERIFIED_NOT_OPENED",
    tables: recovered.length,
    originalObjects,
    originalDatabaseHashesMatched: true,
    recordDigestsMatched: true,
    fileDigestsMatched: true,
    auditArchivesVerified,
    latestSecurityLedgerCopied: true,
    backupLedgerSequence: Number(backupCheckpoint.split("\n")[0]),
    currentLedgerSequence: Number(checkpoint.split("\n")[0]),
    automaticWorkersDisabled: true,
    contentProtectionUnconfigured: true,
    sourcePreserved: true,
    productionHaVerified: false,
  };
  fs.writeFileSync(
    path.join(output, "restore-report.json"),
    JSON.stringify(report, null, 2),
    { flag: "wx" },
  );
  return report;
}
async function main(argv) {
  const args = options(argv),
    context = settings(args["--config"]);
  return args.mode === "check"
    ? check(context)
    : args.mode === "backup"
      ? backup(context, args)
      : restore(context, args);
}
if (require.main === module)
  main(process.argv.slice(2))
    .then((report) =>
      process.stdout.write(JSON.stringify(report, null, 2) + "\n"),
    )
    .catch(() => {
      process.stderr.write(
        "维护演练未通过。请检查隔离库、进程停止状态、路径、摘要、当前收紧账本及数据库工具权限；未覆盖原件或既有目标。\n",
      );
      process.exitCode = 1;
    });
module.exports = {
  main,
  options,
  settings,
  settingsFromConfig,
  canonical,
  sha,
  safe,
  inside,
  privateDirectory,
  sql,
  listening,
};
