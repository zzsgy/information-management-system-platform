/** 独立合成环境专项验收；不连接业务库，不以模拟回调或编辑器内部API代替真实键盘。 */
const fs = require("node:fs"),
  path = require("node:path"),
  crypto = require("node:crypto"),
  zlib = require("node:zlib"),
  assert = require("node:assert/strict");
const { chromium, expect } = require("@playwright/test");
const fixture = JSON.parse(
  fs.readFileSync(process.env.IMS_OFFICE_TEST_FIXTURE, "utf8"),
);
const base = process.env.IMS_OFFICE_TEST_URL || fixture.baseUrl;
assert.equal(
  new URL(base).hostname,
  "127.0.0.1",
  "本脚本仅允许本机合成验收环境",
);
const samples = process.env.IMS_OFFICE_SAMPLE_DIRECTORY,
  out = path.resolve(process.env.IMS_OFFICE_TEST_OUTPUT);
fs.mkdirSync(out, { recursive: true });
const runId = crypto.randomUUID().slice(0, 8),
  contexts = [],
  results = [],
  pageErrors = [],
  syncClocks = [];
// 单项中断后可从该格式继续，仍使用新样本；此前失败报告和已完成格式的证据保留。
const formatOrder = ["docx", "xlsx", "pptx"];
const fromFormat = process.env.IMS_OFFICE_TEST_FROM || "docx";
// 延迟实际基础组件响应，复现上游漏写AMD依赖时的加载顺序；不替换脚本内容。
const dependencyDelay = Number(process.env.IMS_OFFICE_TEST_DELAY_BASES_MS || 0);
assert.ok(
  Number.isInteger(dependencyDelay) &&
    dependencyDelay >= 0 &&
    dependencyDelay <= 10000,
);
assert.ok(
  formatOrder.includes(fromFormat),
  "专项起始格式必须为docx、xlsx或pptx",
);
let browser, leader, staff;
function passed(name) {
  results.push(name);
  console.log(name);
}
async function get(page, api) {
  const response = await page.request.get(base + "/api/v1" + api);
  assert.ok(response.ok(), api + ": " + response.status());
  return (await response.json()).data;
}
async function login(employee) {
  const context = await browser.newContext({
    // 编辑器跨HTTPS来源，必须验证真实证书信任，不能掩盖用户浏览器的脚本阻断。
    ignoreHTTPSErrors: false,
    viewport: { width: 1600, height: 1000 },
  });
  contexts.push(context);
  if (dependencyDelay)
    await context.route(
      /\/(?:component\/(?:BaseView|Window)|mods\/tooltip)\.js(?:\?|$)/,
      async (route) => {
        await new Promise((resolve) => setTimeout(resolve, dependencyDelay));
        await route.continue();
      },
    );
  const page = await context.newPage();
  page.on("pageerror", (e) => pageErrors.push(e.stack || e.message));
  // 仅保留原生同步确认的时刻与顺序，排除认证帧及文档变更正文。
  page.on("websocket", (socket) =>
    socket.on("framereceived", (event) => {
      const raw = String(event.payload);
      if (!raw.startsWith("42[") || !raw.includes('"unSaveLock"')) return;
      const data = JSON.parse(raw.slice(2))[1];
      // 原生无变更解锁返回time=-1，并不表示Unix时间回退。
      if (data.type === "unSaveLock" && data.time !== -1)
        syncClocks.push({
          page: contexts.indexOf(context),
          time: data.time,
          syncChangesIndex: data.syncChangesIndex,
        });
    }),
  );
  await page.goto(base);
  await page.getByLabel("公司工号").fill(employee);
  await page.getByLabel("登录密码", { exact: true }).fill(fixture.password);
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await page.getByRole("link", { name: "企业公盘", exact: true }).click();
  return page;
}
/** 再验证和业务操作走正式接口；授权变化会使员工旧登录失效，全部准备完成后再登录员工。 */
async function mutate(
  page,
  method,
  api,
  body,
  scope,
  operation = crypto.randomUUID(),
) {
  const me = await get(page, "/auth/me"),
    headers = {
      "X-CSRF-Token": me.csrfToken,
      Origin: base,
      "X-Operation-Id": operation,
    };
  if (scope) {
    const [objectType, objectId, action, version] = scope;
    const verification = await page.request.post(
      base + "/api/v1/auth/reauthentications",
      {
        headers,
        data: {
          password: fixture.password,
          purpose: "MANAGE",
          objectIds: [objectId],
          scopeItems: [{ objectType, objectId, actions: [action] }],
        },
      },
    );
    assert.equal(verification.status(), 200);
    headers["X-Reauth-Token"] = (
      await verification.json()
    ).data.verificationToken;
    if (version !== undefined) headers["X-Expected-Version"] = String(version);
  }
  const response = await page.request.fetch(base + "/api/v1" + api, {
    method,
    headers,
    data: body,
  });
  assert.ok(response.ok(), api + ": " + response.status());
  return (await response.json()).data;
}
async function prepare(documentCount = 3, participants = []) {
  const options = await get(
    leader,
    "/drive/access-options?folderId=" +
      fixture.folderId +
      "&keyword=" +
      fixture.staffNo,
  );
  const person = options.people.find((p) => p.employeeNo === fixture.staffNo);
  assert.ok(person);
  const documents = {};
  for (let index = 0; index < documentCount; index++) {
    const ext = ["docx", "xlsx", "pptx"][index % 3];
    const key = documentCount === 3 ? ext : ext + "-" + index;
    const bytes = fs.readFileSync(path.join(samples, "synthetic." + ext)),
      hash = crypto.createHash("sha256").update(bytes).digest("hex"),
      name = "双人专项-" + runId + "-" + index + "." + ext;
    const upload = await mutate(leader, "POST", "/storage/upload-sessions", {
      module: "DRIVE",
      purpose: "DRIVE_FILE",
      businessContextId: fixture.folderId,
      originalName: name,
      expectedSize: bytes.length,
      expectedSha256: hash,
      expectedMediaType: "application/octet-stream",
    });
    assert.ok(bytes.length < upload.partSize, "专项合成样本应小于单分片");
    const me = await get(leader, "/auth/me");
    const part = await leader.request.put(
      base + "/api/v1/storage/upload-sessions/" + upload.uploadId + "/parts/1",
      {
        headers: {
          "X-CSRF-Token": me.csrfToken,
          Origin: base,
          "Content-Type": "application/octet-stream",
          "Content-Range": `bytes 0-${bytes.length - 1}/${bytes.length}`,
          "X-Content-SHA256": hash,
        },
        data: bytes,
      },
    );
    assert.ok(part.ok());
    const job = await mutate(
      leader,
      "POST",
      "/storage/upload-sessions/" + upload.uploadId + "/complete",
      { partCount: 1, finalSha256: hash },
    );
    await expect
      .poll(async () => (await get(leader, "/jobs/" + job.jobId)).state, {
        timeout: 120000,
      })
      .toBe("SUCCEEDED");
    const doc = await mutate(leader, "POST", "/drive/documents", {
      folderId: fixture.folderId,
      uploadSessionId: upload.uploadId,
      name,
    });
    documents[key] = doc.id;
    await mutate(
      leader,
      "PATCH",
      "/drive/documents/" + doc.id,
      { collaborationEnabled: true, reason: "本机合成双人专项验收" },
      ["DRIVE_DOCUMENT", doc.id, "COLLABORATE", doc.rowVersion],
    );
    for (const subject of participants.length
      ? participants.slice(index * 2, index * 2 + 2)
      : [person]) {
      await mutate(
        leader,
        "POST",
        "/drive/resource-grants",
        {
          subjectId: subject.id,
          resourceType: "DOCUMENT",
          resourceId: doc.id,
          action: "EDIT",
          effect: "ALLOW",
          delegationId: options.delegations[0].id,
          reason: "本机合成双人专项验收",
        },
        ["FOLDER", fixture.folderId, "CREATE_GRANT"],
      );
    }
  }
  fs.writeFileSync(
    path.join(out, "documents.json"),
    JSON.stringify(documents, null, 2),
  );
  return documents;
}
async function openEditor(page, id) {
  const detail = page.getByRole("dialog", { name: "文件详情", exact: true });
  if (await detail.isVisible())
    await detail.getByRole("button", { name: "关闭", exact: true }).click();
  await page.getByRole("button", { name: "企业公盘", exact: true }).click();
  let folder;
  for (let n = 1; n <= 10; n++) {
    const data = await get(page, "/drive/folders?page=" + n);
    folder = data.items.find((item) => item.id === fixture.folderId);
    if (folder) break;
    assert.ok(data.hasNext);
    await page
      .getByRole("button", { name: "加载更多目录", exact: true })
      .click();
  }
  assert.ok(folder);
  await page
    .getByRole("button", { name: folder.name + " ›", exact: true })
    .click();
  const doc = await get(page, "/drive/documents/" + id);
  await page.getByRole("button", { name: doc.name, exact: true }).click();
  const pending = page.waitForResponse(
    (r) =>
      r.url().endsWith("/edit-sessions") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "在线编辑", exact: true }).click();
  const response = await pending;
  assert.ok(response.ok());
  const opened = (await response.json()).data;
  assert.equal(opened.checkpointEnabled, true);
  const workspace = page.locator(".office-workspace");
  await workspace.locator("iframe").waitFor({ timeout: 180000 });
  await expect(workspace.locator(".office-participants")).toContainText(
    "已同步到文档服务",
    { timeout: 180000 },
  );
  await page.waitForTimeout(1500);
  assert.deepEqual(pageErrors, [], "编辑器初始化期间不得出现脚本错误");
  const frame = await (
    await workspace.locator("iframe").elementHandle()
  ).contentFrame();
  assert.ok(frame.url().includes("/web-apps/"));
  // 确认原生工具提示扩展已经执行，而不只是基础tooltip稍后加载成功。
  assert.equal(
    await frame.evaluate(
      () =>
        typeof window.jQuery?.fn.tooltip?.Constructor?.prototype.updateTitle,
    ),
    "function",
  );
  return { frame, id: opened.sessionId };
}
/** Canvas的焦点/段落切换是异步动作，短等待只用于真实键盘落点；内容是否保存由独立文件核对决定。 */
async function edit(page, frame, ext, actor, round, text) {
  if (ext === "xlsx") {
    const name = frame.locator("#ce-cell-name");
    await name.fill("C" + (3 + round * 2 + actor));
    await name.press("Enter");
    await page.waitForTimeout(300);
    await page.keyboard.type(text, { delay: 100 });
    await page.keyboard.press("Enter");
    if (round === 0) {
      await name.fill(actor === 0 ? "B3" : "B4");
      await name.press("Enter");
      await page.waitForTimeout(300);
      await page.keyboard.type(actor === 0 ? "21" : "34");
      await page.keyboard.press("Enter");
    }
  } else {
    if (ext === "pptx") {
      await frame
        .locator("#id_thumbnails")
        .click({ position: { x: 130, y: actor === 0 ? 70 : 210 } });
      await page.waitForTimeout(600);
      await frame
        .locator("#id_viewer_overlay")
        .dblclick({ position: { x: 600, y: 380 } });
    } else
      await frame
        .locator("#id_viewer_overlay")
        .click({ position: { x: 350, y: 180 } });
    await page.waitForTimeout(500);
    await page.keyboard.press("Control+End");
    await page.waitForTimeout(500);
    await page.keyboard.press("Enter");
    await page.waitForTimeout(600);
    await page.keyboard.type(text, { delay: 150 });
    await page.waitForTimeout(300);
    if (ext === "pptx") {
      await page.keyboard.press("Escape");
      await page.keyboard.press("Escape");
    } else await page.keyboard.press("Enter");
  }
  await page.waitForTimeout(4000);
  await expect(page.locator(".office-participants")).toContainText(
    "已同步到文档服务",
  );
}
/** 仅检查已知小样本的OOXML；不复用业务解析器，避免把保存逻辑抄进断言。 */
function entries(bytes) {
  const end = bytes.lastIndexOf(Buffer.from([80, 75, 5, 6]));
  assert.ok(end >= 0);
  let offset = bytes.readUInt32LE(end + 16);
  const files = {};
  for (let n = 0; n < bytes.readUInt16LE(end + 10); n++) {
    const size = bytes.readUInt16LE(offset + 28),
      name = bytes.subarray(offset + 46, offset + 46 + size).toString(),
      local = bytes.readUInt32LE(offset + 42),
      start =
        local +
        30 +
        bytes.readUInt16LE(local + 26) +
        bytes.readUInt16LE(local + 28),
      packed = bytes.subarray(start, start + bytes.readUInt32LE(offset + 20));
    assert.ok(bytes.readUInt32LE(offset + 24) < 10485760);
    files[name] = (
      bytes.readUInt16LE(offset + 10) === 8
        ? zlib.inflateRawSync(packed)
        : packed
    ).toString();
    offset +=
      46 +
      size +
      bytes.readUInt16LE(offset + 30) +
      bytes.readUInt16LE(offset + 32);
  }
  return files;
}
function textOf(xml) {
  return [
    ...xml.matchAll(/<(?:w:|a:)?t(?:\s[^>]*)?>([\s\S]*?)<\/(?:w:|a:)?t>/g),
  ]
    .map((m) => m[1])
    .join("");
}
async function verifyFile(id, ext, markers, label) {
  const doc = await get(leader, "/drive/documents/" + id),
    response = await leader.request.get(
      base +
        "/api/v1/drive/documents/" +
        id +
        "/download?versionId=" +
        doc.currentVersion.id,
    );
  assert.equal(response.status(), 200);
  const bytes = await response.body(),
    files = entries(bytes);
  fs.writeFileSync(path.join(out, label + "." + ext), bytes);
  const text = Object.values(files).map(textOf).join("");
  for (const marker of markers)
    assert.ok(text.includes(marker), label + "缺少完整标记：" + marker);
  if (ext === "xlsx") {
    assert.match(
      files["xl/worksheets/sheet1.xml"],
      /<f[^>]*>SUM\(B3:B4\)<\/f><v>55<\/v>/,
    );
    assert.ok(files["xl/workbook.xml"].includes("合成验收"));
    assert.ok(text.includes("样本甲") && text.includes("样本乙"));
    // 共享字符串表可能保留不再引用的文本，必须确认每段修改仍位于实际单元格。
    const shared = [
      ...files["xl/sharedStrings.xml"].matchAll(/<si>([\s\S]*?)<\/si>/g),
    ].map((match) => textOf(match[1]));
    for (const marker of markers) {
      const row = marker.endsWith("Final")
        ? 10
        : 3 + Number(marker.at(-1)) * 2 + (marker.includes("Leader") ? 0 : 1);
      const cell = files["xl/worksheets/sheet1.xml"].match(
        new RegExp('<c\\b[^>]*\\br="C' + row + '"[^>]*>([\\s\\S]*?)</c>'),
      );
      assert.ok(cell, "缺少单元格C" + row);
      assert.equal(
        shared[Number(cell[1].match(/<v>(\d+)<\/v>/)[1])],
        marker,
        "单元格C" + row + "内容不匹配",
      );
    }
  } else if (ext === "pptx") {
    assert.equal(
      Object.keys(files).filter((p) => /^ppt\/slides\/slide\d+\.xml$/.test(p))
        .length,
      2,
    );
    for (const n of [1, 2])
      assert.ok(
        textOf(files["ppt/slides/slide" + n + ".xml"]).includes(
          "IMS_SYNTHETIC_SLIDE_" + n,
        ),
      );
    for (const marker of markers)
      assert.ok(
        textOf(
          files[
            "ppt/slides/slide" + (marker.includes("Leader") ? 1 : 2) + ".xml"
          ],
        ).includes(marker),
        "幻灯片内容不得错页",
      );
  } else
    assert.ok(
      text.includes("中文合成文档") &&
        text.includes("IMS_SYNTHETIC_WORD_PAGE_2"),
    );
  fs.appendFileSync(
    path.join(out, "versions.jsonl"),
    JSON.stringify({
      label,
      documentId: id,
      versionId: doc.currentVersion.id,
      sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
    }) + "\n",
  );
  return doc;
}
async function checkpoint(page, session) {
  const pending = page.waitForResponse(
    (r) => r.url().endsWith("/checkpoints") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "保存到平台", exact: true }).click();
  const response = await pending;
  assert.equal(response.status(), 202);
  const receipt = (await response.json()).data;
  await expect
    .poll(
      async () =>
        (await get(page, "/drive/edit-sessions/" + session)).saveReceipts.find(
          (item) => item.id === receipt.id,
        )?.state,
      { timeout: 180000 },
    )
    .toBe("SAVED");
  return { receipt, operation: response.request().headers()["x-operation-id"] };
}
/** 真实电脑浏览器逐级负载；各文档两名参与者，输入、回调及文件核对保持正式路径。 */
async function runConcurrent() {
  const count = Number(process.env.IMS_OFFICE_TEST_CONCURRENCY);
  assert.ok([6, 12, 20].includes(count));
  browser = await chromium.launch({ channel: "msedge", headless: true });
  leader = await login(fixture.leaderNo);
  const people = fixture.loadAccounts.slice(0, count);
  assert.equal(people.length, count);
  const documents = await prepare(count / 2, people),
    pairs = [],
    metrics = { editors: count, documents: count / 2, openMs: [], saveMs: [] };
  for (const [index, [key, id]] of Object.entries(documents).entries()) {
    pairs.push({
      id,
      ext: key.split("-")[0],
      first: await login(people[index * 2].employeeNo),
      second: await login(people[index * 2 + 1].employeeNo),
      markers: ["Load" + runId + "Leader0", "Load" + runId + "Staff0"],
    });
  }
  await Promise.all(
    pairs.flatMap((pair) =>
      ["first", "second"].map(async (who) => {
        const started = Date.now();
        pair[who + "Editor"] = await openEditor(pair[who], pair.id);
        metrics.openMs.push(Date.now() - started);
      }),
    ),
  );
  passed(count + "个真实编辑器同时在线，三格式初始化无脚本错误");
  for (const [who, actor] of [
    ["first", 0],
    ["second", 1],
  ])
    await Promise.all(
      pairs.map((pair) =>
        edit(
          pair[who],
          pair[who + "Editor"].frame,
          pair.ext,
          actor,
          0,
          pair.markers[actor],
        ),
      ),
    );
  await Promise.all(
    pairs.map(async (pair) => {
      const started = Date.now();
      const saved = await checkpoint(pair.second, pair.secondEditor.id);
      metrics.saveMs.push(Date.now() - started);
      const copies = await Promise.all(
        Array.from({ length: 3 }, () =>
          mutate(
            pair.second,
            "POST",
            "/drive/edit-sessions/" + pair.secondEditor.id + "/checkpoints",
            undefined,
            undefined,
            saved.operation,
          ),
        ),
      );
      assert.ok(copies.every((r) => r.id === saved.receipt.id));
    }),
  );
  for (const [i, pair] of pairs.entries())
    await verifyFile(
      pair.id,
      pair.ext,
      pair.markers,
      "load-" + count + "-checkpoint-" + i,
    );
  passed(count + "人负载下并发保存逐文件内容一致，重复提交不增版");
  await pairs[0].first.screenshot({
    path: path.join(out, "concurrent-editors.png"),
  });
  for (const who of ["first", "second"])
    await Promise.all(
      pairs.map((pair) =>
        pair[who]
          .getByRole("button", { name: "关闭编辑", exact: true })
          .click(),
      ),
    );
  for (const [i, pair] of pairs.entries()) {
    await expect
      .poll(
        async () =>
          (
            await get(
              pair.second,
              "/drive/edit-sessions/" + pair.secondEditor.id,
            )
          ).state,
        { timeout: 180000 },
      )
      .toBe("CLOSED");
    await verifyFile(
      pair.id,
      pair.ext,
      pair.markers,
      "load-" + count + "-final-" + i,
    );
  }
  passed(count + "人全部退出后的最终回调和内容核对通过");
  for (const key of ["openMs", "saveMs"]) {
    const values = [...metrics[key]].sort((a, b) => a - b);
    metrics[key + "P95"] = values[Math.ceil(values.length * 0.95) - 1];
    metrics[key + "Max"] = values.at(-1);
  }
  fs.writeFileSync(
    path.join(out, "load-metrics.json"),
    JSON.stringify(metrics, null, 2),
  );
}
/** 浏览器真正断网，验证临时失联不得销毁尚有同步状态的编辑器。 */
async function runNetwork() {
  browser = await chromium.launch({ channel: "msedge", headless: true });
  leader = await login(fixture.leaderNo);
  const docs = await prepare(),
    opened = await openEditor(leader, docs.docx),
    marker = "Network" + runId + "Leader0";
  await edit(leader, opened.frame, "docx", 0, 0, marker);
  await leader.context().setOffline(true);
  try {
    await leader.waitForTimeout(7000);
    assert.equal(
      await leader.locator(".office-workspace iframe").count(),
      1,
      "临时断网不能销毁编辑器草稿",
    );
    await expect(leader.locator(".office-workspace iframe")).toBeHidden();
    await leader.screenshot({ path: path.join(out, "network-suspended.png") });
  } finally {
    await leader.context().setOffline(false);
  }
  await expect
    .poll(
      async () =>
        (await get(leader, "/drive/edit-sessions/" + opened.id)).state,
      { timeout: 30000 },
    )
    .toMatch(/^(OPEN|CLOSED)$/);
  const restored = await get(leader, "/drive/edit-sessions/" + opened.id);
  if (restored.state === "OPEN") {
    await expect(leader.locator(".office-workspace iframe")).toBeVisible({
      timeout: 15000,
    });
    await checkpoint(leader, opened.id);
  }
  await verifyFile(docs.docx, "docx", [marker], "network-recovered");
  await leader.getByRole("button", { name: "关闭编辑", exact: true }).click();
  passed("真实断网保留隐藏稿件，恢复后核对原生保存结果和完整文件");
  const next = await openEditor(leader, docs.docx);
  // 只切断平台状态查询，原生连接保持；验证短时失联恢复同一编辑器，未伪造业务响应。
  const route = base + "/api/v1/drive/edit-sessions/" + next.id;
  await leader.route(route, (r) => r.abort("connectionfailed"));
  await leader.waitForTimeout(6500);
  await expect(leader.locator(".office-workspace iframe")).toBeHidden();
  assert.equal(await leader.locator(".office-workspace iframe").count(), 1);
  await leader.unroute(route);
  await expect(leader.locator(".office-workspace iframe")).toBeVisible({
    timeout: 15000,
  });
  assert.equal(
    (await get(leader, "/drive/edit-sessions/" + next.id)).state,
    "OPEN",
  );
  await leader.getByRole("button", { name: "关闭编辑", exact: true }).click();
  passed("平台链路恢复后重新鉴权，同一编辑器继续可用");
}
async function run() {
  browser = await chromium.launch({ channel: "msedge", headless: true });
  leader = await login(fixture.leaderNo);
  const documents = await prepare();
  staff = await login(fixture.staffNo);
  for (const ext of formatOrder.slice(formatOrder.indexOf(fromFormat))) {
    const id = documents[ext],
      first = await openEditor(leader, id),
      second = await openEditor(staff, id),
      markers = [];
    assert.equal(first.id, second.id);
    for (let round = 0; round < 3; round++) {
      for (const [page, frame, actor, label] of [
        [leader, first.frame, 0, "Leader"],
        [staff, second.frame, 1, "Staff"],
      ]) {
        const marker = "Office" + runId + label + round;
        await edit(page, frame, ext, actor, round, marker);
        markers.push(marker);
      }
      await staff.keyboard.press("Control+s");
      await staff.waitForTimeout(3000);
      const saved = await checkpoint(staff, second.id),
        before = await verifyFile(
          id,
          ext,
          markers,
          ext + "-checkpoint-" + round,
        );
      const duplicate = await mutate(
        staff,
        "POST",
        "/drive/edit-sessions/" + second.id + "/checkpoints",
        undefined,
        undefined,
        saved.operation,
      );
      assert.equal(duplicate.id, saved.receipt.id);
      assert.equal(
        (await get(leader, "/drive/documents/" + id)).currentVersion.id,
        before.currentVersion.id,
      );
      passed(ext + "第" + (round + 1) + "次双人保存内容完整，重复操作不增版");
    }
    await leader.screenshot({ path: path.join(out, ext + "-leader.png") });
    await staff.screenshot({ path: path.join(out, ext + "-staff.png") });
    await leader.getByRole("button", { name: "关闭编辑", exact: true }).click();
    assert.equal(
      (await get(leader, "/drive/edit-sessions/" + first.id)).state,
      "OPEN",
    );
    const finalMarker = "Office" + runId + "StaffFinal";
    await edit(staff, second.frame, ext, 1, 3, finalMarker);
    markers.push(finalMarker);
    await checkpoint(staff, second.id);
    await verifyFile(id, ext, markers, ext + "-remaining-editor");
    await staff.getByRole("button", { name: "关闭编辑", exact: true }).click();
    await expect
      .poll(
        async () =>
          (await get(staff, "/drive/edit-sessions/" + second.id)).state,
        { timeout: 180000 },
      )
      .toBe("CLOSED");
    await staff
      .getByRole("button", { name: "返回文件详情", exact: true })
      .click();
    await verifyFile(id, ext, markers, ext + "-final");
    passed(ext + "单人退出后继续编辑、保存及最终版本完整");
    const reopened = await openEditor(leader, id);
    assert.notEqual(reopened.id, first.id);
    await leader.getByRole("button", { name: "关闭编辑", exact: true }).click();
    await expect
      .poll(
        async () =>
          (await get(leader, "/drive/edit-sessions/" + reopened.id)).state,
        { timeout: 180000 },
      )
      .toBe("CLOSED");
    await leader
      .getByRole("button", { name: "返回文件详情", exact: true })
      .click();
    passed(ext + "最新版本重新打开并正常结束");
  }
  const viewer = await login(fixture.viewerNo),
    viewerIdentity = await get(viewer, "/auth/me");
  for (const ext of ["docx", "xlsx", "pptx"]) {
    const doc = await get(viewer, "/drive/documents/" + documents[ext]);
    const denied = await viewer.request.post(
      base + "/api/v1/drive/documents/" + documents[ext] + "/edit-sessions",
      {
        headers: {
          "X-CSRF-Token": viewerIdentity.csrfToken,
          Origin: base,
          "X-Operation-Id": crypto.randomUUID(),
        },
        data: { baseVersionId: doc.currentVersion.id },
      },
    );
    assert.equal(denied.status(), 403);
  }
  passed("三种格式只有VIEW的人员均不能发起编辑");
  // 撤权最后执行，避免使其余格式验收使用的员工登录失效。
  const id = documents.docx,
    active = await openEditor(staff, id);
  await edit(staff, active.frame, "docx", 1, 0, "Revoked" + runId);
  const stable = await get(leader, "/drive/documents/" + id);
  const grants = await get(
      leader,
      "/drive/resource-grants?resourceType=DOCUMENT&resourceId=" + id,
    ),
    grant = grants.items.find(
      (g) => g.action === "EDIT" && g.effect === "ALLOW",
    );
  assert.ok(grant);
  await mutate(
    leader,
    "DELETE",
    "/drive/resource-grants/" + grant.id,
    undefined,
    ["DRIVE_GRANT", grant.id, "REVOKE_GRANT", grant.version],
  );
  await expect(staff.locator(".office-workspace iframe")).toHaveCount(0, {
    timeout: 15000,
  });
  await expect
    .poll(
      async () =>
        (
          await get(leader, "/drive/documents/" + id + "/office-history")
        ).receipts.some((r) => r.state === "UNKNOWN" && r.objectId),
      { timeout: 180000 },
    )
    .toBe(true);
  assert.equal(
    (await get(leader, "/drive/documents/" + id)).currentVersion.id,
    stable.currentVersion.id,
  );
  passed("编辑中撤权清屏，迟到候选保留且不覆盖正式版本");
}
(process.env.IMS_OFFICE_TEST_SCENARIO === "network"
  ? runNetwork()
  : process.env.IMS_OFFICE_TEST_CONCURRENCY
    ? runConcurrent()
    : run()
)
  .then(() => {
    assert.deepEqual(pageErrors, []);
    assert.ok(syncClocks.length > 0, "必须取得真实文档服务的同步确认时刻");
    for (let n = 0; n < contexts.length; n++) {
      const clocks = syncClocks.filter((c) => c.page === n);
      for (let i = 1; i < clocks.length; i++)
        assert.ok(
          clocks[i].time >= clocks[i - 1].time,
          "原生同步确认时刻出现回退",
        );
    }
  })
  .catch(async (error) => {
    fs.writeFileSync(path.join(out, "failure.txt"), error.stack);
    console.error(error.stack);
    for (const [name, page] of [
      ["leader", leader],
      ["staff", staff],
    ])
      if (page && !page.isClosed())
        await page
          .screenshot({ path: path.join(out, "failure-" + name + ".png") })
          .catch(() => {});
    process.exitCode = 1;
  })
  .finally(async () => {
    fs.writeFileSync(
      path.join(out, "report.json"),
      JSON.stringify(
        {
          runId,
          passed: !process.exitCode,
          results,
          pageErrors,
          syncClockSamples: syncClocks.length,
          dependencyDelayMs: dependencyDelay,
        },
        null,
        2,
      ),
    );
    fs.writeFileSync(
      path.join(out, "sync-clocks.json"),
      JSON.stringify(syncClocks, null, 2),
    );
    for (const context of contexts) await context.close();
    if (browser) await browser.close();
  });
