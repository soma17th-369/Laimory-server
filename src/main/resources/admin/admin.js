"use strict";
const $ = id => document.getElementById(id);
let bootstrap, catalog = [], config;

function status(message, error = false) {
  $("status").textContent = message;
  $("status").dataset.error = String(error);
}

// 서버의 Long 계약은 유지한다. JSON 정수 원문을 사용해 2^53 이상에서도 반올림하지 않는다.
function parseResponse(text) {
  return JSON.parse(text, (key, value, context) => {
    if (["minAppVersion", "recommendAppVersion"].includes(key) && typeof value === "number") {
      if (context?.source) return context.source;
      if (Number.isSafeInteger(value)) return String(value);
      throw new Error("정확한 Long 조회를 지원하는 최신 브라우저가 필요합니다.");
    }
    return value;
  });
}

async function api(path, options = {}) {
  const response = await fetch(path, {credentials: "same-origin", cache: "no-store", ...options});
  let data;
  try { data = parseResponse(await response.text()); }
  catch (error) { throw new Error(`${error.message} (HTTP ${response.status})`); }
  if (!response.ok || data.header?.code !== 0) {
    throw new Error(`${data.header?.message || "요청 실패"} (HTTP ${response.status}, 요청 ID: ${response.headers.get("Transaction-Id") || "없음"})`);
  }
  return data.body;
}

function writeOptions(method, body) {
  return {method, headers: {"Content-Type": "application/json", [bootstrap.headerName]: bootstrap.token}, body};
}

function describe(doc) {
  return doc ? `${doc.termType} · ${doc.version}\n${doc.title}\n${doc.contentUrl}` : "등록된 문서가 없습니다.";
}

function httpsUrl(value) {
  const url = new URL(value);
  if (url.protocol !== "https:" || !url.hostname) throw new Error("호스트가 있는 절대 HTTPS URL을 입력해주세요.");
  return url;
}

function showCurrent() {
  const current = catalog.find(group => group.termType === $("term-type").value)?.current;
  $("current-term").textContent = describe(current);
  $("current-url").hidden = true;
  if (current) {
    try { $("current-url").href = httpsUrl(current.contentUrl).href; $("current-url").hidden = false; }
    catch { /* 오래된 잘못된 seed URL도 실행 가능한 링크로 만들지 않는다. */ }
  }
  $("published").checked = false;
}

async function loadTerms() {
  catalog = await api("/admin/api/terms");
  const selection = $("term-type").value;
  $("term-type").replaceChildren(...catalog.map(group => new Option(group.termType, group.termType)));
  if (catalog.some(group => group.termType === selection)) $("term-type").value = selection;
  $("history").replaceChildren();
  for (const group of catalog) for (const doc of group.documents) {
    const row = document.createElement("tr");
    for (const value of [doc.termType, doc.version + (doc.version === group.current?.version ? " · current" : ""), doc.title]) {
      const cell = document.createElement("td"); cell.textContent = value; row.append(cell);
    }
    const cell = document.createElement("td");
    try {
      const link = document.createElement("a"); link.href = httpsUrl(doc.contentUrl).href;
      link.textContent = "원문 ↗"; link.target = "_blank"; link.rel = "noopener noreferrer"; cell.append(link);
    } catch { cell.textContent = doc.contentUrl; }
    row.append(cell); $("history").append(row);
  }
  showCurrent();
}

async function loadConfig() {
  config = await api("/admin/api/app-config");
  $("minimum").value = config.minAppVersion ?? "";
  $("recommended").value = config.recommendAppVersion ?? "";
  $("debug-message").value = config.debugTestMessage ?? "";
}

function confirmChange(details, warning) {
  $("confirm-environment").textContent = bootstrap.environment.toUpperCase();
  $("confirm-details").textContent = details;
  $("confirm-warning").textContent = warning;
  const dialog = $("confirmation"); dialog.returnValue = "cancel"; dialog.showModal();
  return new Promise(resolve => dialog.addEventListener("close", () => resolve(dialog.returnValue === "commit"), {once: true}));
}

$("term-type").addEventListener("change", showCurrent);
for (const id of ["term-version", "term-title", "term-url"]) $(id).addEventListener("input", () => $("published").checked = false);
$("open-term").addEventListener("click", () => {
  try { window.open(httpsUrl($("term-url").value).href, "_blank", "noopener,noreferrer"); }
  catch (error) { status(error.message, true); }
});

$("term-form").addEventListener("submit", async event => {
  event.preventDefault(); $("term-fields").disabled = true;
  try {
    const proposal = {termType: $("term-type").value, version: $("term-version").value, title: $("term-title").value,
      contentUrl: $("term-url").value, publicationConfirmed: $("published").checked};
    httpsUrl(proposal.contentUrl);
    const current = catalog.find(group => group.termType === proposal.termType)?.current;
    if (!await confirmChange(`현재\n${describe(current)}\n\n등록 후\n${describe(proposal)}`, "새 문서가 즉시 current가 됩니다. 기존 행으로 되돌리는 기능은 없습니다.")) return;
    const result = await api("/admin/api/terms", writeOptions("POST", JSON.stringify(proposal)));
    status(`등록 완료: ${result.saved.termType} ${result.saved.version}. 현재 버전: ${result.current.version}`);
    $("term-form").reset();
    await loadTerms();
  } catch (error) { status(`${error.message}\n통신 오류였다면 이력을 확인한 뒤 재시도하세요. 자동 재시도하지 않습니다.`, true); }
  finally { $("term-fields").disabled = false; }
});

$("config-form").addEventListener("submit", async event => {
  event.preventDefault(); $("config-fields").disabled = true;
  try {
    const minimum = $("minimum").value, recommended = $("recommended").value;
    const maxLong = 9223372036854775807n;
    if (![minimum, recommended].every(value => /^[1-9][0-9]*$/.test(value) && BigInt(value) <= maxLong)
        || BigInt(minimum) > BigInt(recommended)) throw new Error("양의 Long 범위에서 최소 버전 ≤ 권장 버전으로 입력해주세요.");
    if (!await confirmChange(`최소 버전: ${config.minAppVersion} → ${minimum}\n권장 버전: ${config.recommendAppVersion} → ${recommended}`,
      BigInt(minimum) > BigInt(config.minAppVersion ?? 0) ? "최소 버전 상승: 낮은 버전 앱에 즉시 업데이트 요구가 반영됩니다." : "설정은 저장 즉시 앱의 /intro 응답에 반영됩니다.")) return;
    // 검증한 decimal 문자열을 JSON number로 전송한다(Number 변환으로 정밀도를 잃지 않는다).
    await api("/admin/api/app-config", writeOptions("PUT", `{"minAppVersion":${minimum},"recommendAppVersion":${recommended}}`));
    const intro = await api("/api/v1/intro");
    if (intro.minAppVersion !== minimum || intro.recommendAppVersion !== recommended) {
      throw new Error("저장은 완료됐지만 /intro 값이 다릅니다. 다른 운영자의 변경 여부를 확인해주세요.");
    }
    await loadConfig(); status("저장 완료. 공개 /api/v1/intro에서도 같은 최소·권장 버전을 확인했습니다.");
  } catch (error) { status(`${error.message}\n저장 결과가 불확실하면 새로고침하여 현재 값을 확인하세요.`, true); }
  finally { $("config-fields").disabled = false; }
});

(async () => {
  try {
    bootstrap = await api("/admin/api/csrf");
    document.body.dataset.environment = bootstrap.environment;
    $("environment").textContent = bootstrap.environment.toUpperCase();
    // 한 도메인의 조회 실패가 다른 도메인의 정상 기능을 숨기지 않도록 각각 활성화한다.
    const results = await Promise.allSettled([
      loadTerms().then(() => $("term-fields").disabled = false),
      loadConfig().then(() => $("config-fields").disabled = false)
    ]);
    const failures = results.filter(result => result.status === "rejected");
    if (failures.length) throw new Error(failures.map(result => result.reason.message).join("\n"));
    status("연결되었습니다. 변경할 환경과 현재 값을 확인해주세요.");
  } catch (error) { status(error.message, true); }
})();
