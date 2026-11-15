/** 日期筛选和服务端一样以公司时区为准，起止两天均计入范围。 */
export function recentDateRange(days: 7 | 30, now = new Date()) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).formatToParts(now).map(({ type, value }) => [type, value]),
  );
  const to = `${parts.year}-${parts.month}-${parts.day}`;
  const start = new Date(`${to}T00:00:00Z`);
  start.setUTCDate(start.getUTCDate() - (days - 1));
  return { from: start.toISOString().slice(0, 10), to };
}

/** 给已有服务端日期字段生成首次查询条件，缺省字段表示不限制日期。 */
export function recentWeekFilters(field?: string): Record<string, string> {
  if (!field) return {};
  const { from, to } = recentDateRange(7);
  return { [field + "From"]: from, [field + "To"]: to };
}
