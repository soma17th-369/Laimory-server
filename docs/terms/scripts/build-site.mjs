import { createHash } from "node:crypto";
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, basename, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(SCRIPT_DIRECTORY, "../../..");
const DEFAULT_OUTPUT_ROOT = resolve(REPOSITORY_ROOT, "build/terms-site");
const STATIC_RESOURCE_ROOT = resolve(REPOSITORY_ROOT, "src/main/resources/terms-content");

const SITE_ORIGIN = "https://laimory.app";
const VERSION = "1.0";
const EFFECTIVE_AT_KST = "2026-08-31 00:00:00";
// 운영 catalog는 클라이언트 연동을 위해 먼저 활성화하되, 공개 원문의 시행일은 위 값을 유지한다.
const CATALOG_EFFECTIVE_AT_KST = "2026-08-28 00:00:00";
const CACHE_CONTROL = "public, max-age=31536000, immutable";

export const DOCUMENTS = Object.freeze([
    {
        source: "docs/terms/drafts/01-terms-of-service.md",
        slug: "terms-of-service",
        title: "라이모리 이용약관",
        catalog: { termType: "TERMS_OF_SERVICE", stage: "LOGIN", displayOrder: 1 },
    },
    {
        source: "docs/terms/drafts/03-third-party-provision-consent.md",
        slug: "third-party-provision-consent",
        title: "개인정보 제3자 제공 동의",
        catalog: { termType: "THIRD_PARTY_PROVISION_CONSENT", stage: "TIMELINE_FIRST_CREATE", displayOrder: 4 },
    },
    {
        source: "docs/terms/drafts/04-sensitive-information-consent.md",
        slug: "sensitive-information-consent",
        title: "민감정보 처리 동의",
        catalog: { termType: "SENSITIVE_INFORMATION_CONSENT", stage: "TIMELINE_FIRST_CREATE", displayOrder: 3 },
    },
    {
        source: "docs/terms/drafts/05-cross-border-transfer-consent.md",
        slug: "cross-border-transfer-consent",
        title: "개인정보 국외 이전 동의",
        catalog: { termType: "CROSS_BORDER_TRANSFER_CONSENT", stage: "TIMELINE_FIRST_CREATE", displayOrder: 5 },
    },
    {
        source: "docs/terms/drafts/06-location-based-service-terms.md",
        slug: "location-based-service-terms",
        title: "라이모리 위치기반서비스 이용약관",
        catalog: { termType: "LOCATION_BASED_SERVICE_TERMS", stage: "TIMELINE_FIRST_CREATE", displayOrder: 6 },
    },
    {
        source: "docs/terms/drafts/08-privacy-policy.md",
        slug: "privacy-policy",
        title: "라이모리 개인정보 처리방침",
        catalog: { termType: "PRIVACY_POLICY", stage: "LOGIN", displayOrder: 2 },
    },
]);

const PAGE_STYLE = `
:root {
  color-scheme: light;
  font-family: -apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif;
  font-size: 16px;
  color: #172033;
  background: #f5f7fb;
  -webkit-text-size-adjust: 100%;
  text-size-adjust: 100%;
}
* { box-sizing: border-box; }
body { margin: 0; background: #f5f7fb; line-height: 1.75; word-break: keep-all; overflow-wrap: anywhere; }
.skip-link { position: absolute; left: 1rem; top: -4rem; z-index: 10; padding: .65rem .9rem; color: #fff; background: #111827; border-radius: .5rem; }
.skip-link:focus { top: 1rem; }
.legal-document { width: min(100%, 960px); min-height: 100vh; margin: 0 auto; padding: 3rem 2.5rem 5rem; background: #fff; box-shadow: 0 0 0 1px rgba(15, 23, 42, .04); }
h1, h2, h3 { color: #111827; line-height: 1.35; letter-spacing: -.025em; scroll-margin-top: 1rem; }
h1 { margin: 0 0 2.5rem; font-size: 2rem; }
h2 { margin: 3rem 0 1rem; padding-bottom: .55rem; border-bottom: 1px solid #dbe2ea; font-size: 1.4rem; }
h3 { margin: 2rem 0 .75rem; font-size: 1.16rem; }
p { margin: .85rem 0; }
ol, ul { margin: .9rem 0; padding-left: 1.55rem; }
li + li { margin-top: .45rem; }
a { color: #075bb5; text-decoration-thickness: .08em; text-underline-offset: .18em; }
a:focus-visible { outline: 3px solid #60a5fa; outline-offset: 3px; border-radius: .15rem; }
hr { margin: 3rem 0; border: 0; border-top: 1px solid #cbd5e1; }
.table-scroll { width: 100%; margin: 1.25rem 0 1.75rem; overflow-x: auto; border: 1px solid #cbd5e1; border-radius: .65rem; background: #fff; -webkit-overflow-scrolling: touch; }
.table-scroll:focus-visible { outline: 3px solid #60a5fa; outline-offset: 2px; }
table { width: 100%; min-width: 720px; border-collapse: collapse; font-size: .9375rem; line-height: 1.6; }
th, td { padding: .85rem .9rem; border-right: 1px solid #dbe2ea; border-bottom: 1px solid #dbe2ea; text-align: left; vertical-align: top; }
th:last-child, td:last-child { border-right: 0; }
tbody tr:last-child td { border-bottom: 0; }
th { color: #111827; background: #eef3f8; font-weight: 700; }
p strong, li strong, td strong, th strong { color: #172554; font-size: 1.2em; font-weight: 750; text-decoration-line: underline; text-decoration-color: #93c5fd; text-decoration-thickness: .12em; text-underline-offset: .2em; }
@media (max-width: 640px) {
  .legal-document { padding: 1.6rem 1.1rem 3rem; box-shadow: none; }
  h1 { margin-bottom: 2rem; font-size: 1.65rem; }
  h2 { margin-top: 2.5rem; font-size: 1.28rem; }
  h3 { font-size: 1.1rem; }
  table { min-width: 680px; }
}
@media print {
  :root, body { background: #fff; }
  .legal-document { width: auto; padding: 0; box-shadow: none; }
  .table-scroll { overflow: visible; border-radius: 0; }
  table { min-width: 0; font-size: 9pt; }
  a { color: inherit; }
}
`;

function escapeHtml(value) {
    return value
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function renderInline(value) {
    if (value.includes("\uE000") || value.includes("\uE001")) {
        throw new Error("Markdown source contains reserved renderer characters");
    }

    const fragments = [];
    const store = (html) => {
        const token = `\uE000${fragments.length}\uE001`;
        fragments.push(html);
        return token;
    };

    let prepared = value.replace(/<br\s*\/?\s*>/giu, () => store("<br>"));
    prepared = prepared.replace(/\[([^\]]+)\]\((https:\/\/[^\s)]+)\)/gu,
        (_, label, href) => store(`<a href="${escapeHtml(href)}">${renderInline(label)}</a>`));
    prepared = prepared.replace(/\*\*(.+?)\*\*/gu,
        (_, content) => store(`<strong>${renderInline(content)}</strong>`));
    prepared = prepared.replace(/https:\/\/[^\s<>()\[\]\uE000\uE001]+/gu,
        (href) => store(`<a href="${escapeHtml(href)}">${escapeHtml(href)}</a>`));
    prepared = prepared.replace(/(?<![\w.+-])([\w.+-]+@[\w.-]+\.[A-Za-z]{2,})(?![\w.-])/gu,
        (_, address) => store(`<a href="mailto:${escapeHtml(address)}">${escapeHtml(address)}</a>`));

    let html = escapeHtml(prepared);
    const tokenPattern = /\uE000(\d+)\uE001/gu;
    while (tokenPattern.test(html)) {
        tokenPattern.lastIndex = 0;
        html = html.replace(tokenPattern, (_, index) => fragments[Number(index)]);
    }
    return html;
}

function isTableDelimiter(line) {
    return /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/u.test(line);
}

function splitTableRow(line) {
    const trimmed = line.trim().replace(/^\|/u, "").replace(/\|$/u, "");
    return trimmed.split("|").map((cell) => cell.trim());
}

function isBlockStart(lines, index) {
    const line = lines[index] ?? "";
    return /^#{1,6}\s+/u.test(line)
        || /^\s*(?:\d+\.|[-+*])\s+/u.test(line)
        || /^\s*---+\s*$/u.test(line)
        || (line.trim().startsWith("|") && isTableDelimiter(lines[index + 1] ?? ""));
}

export function renderMarkdown(markdown) {
    const lines = markdown.replaceAll("\r\n", "\n").split("\n");
    const blocks = [];
    let headingNumber = 0;
    let index = 0;

    while (index < lines.length) {
        const line = lines[index];
        if (line.trim() === "") {
            index += 1;
            continue;
        }

        const heading = /^(#{1,6})\s+(.+)$/u.exec(line);
        if (heading) {
            const level = heading[1].length;
            headingNumber += 1;
            const id = level === 1 ? "document-title" : `section-${headingNumber}`;
            blocks.push(`<h${level} id="${id}">${renderInline(heading[2].trim())}</h${level}>`);
            index += 1;
            continue;
        }

        if (/^\s*---+\s*$/u.test(line)) {
            blocks.push("<hr>");
            index += 1;
            continue;
        }

        if (line.trim().startsWith("|") && isTableDelimiter(lines[index + 1] ?? "")) {
            const headers = splitTableRow(line);
            index += 2;
            const rows = [];
            while (index < lines.length && lines[index].trim().startsWith("|")) {
                const cells = splitTableRow(lines[index]);
                if (cells.length !== headers.length) {
                    throw new Error(`Table column mismatch near line ${index + 1}`);
                }
                rows.push(cells);
                index += 1;
            }
            const label = headers.map((header) => header.replaceAll("**", "")).join(", ");
            blocks.push([
                `<div class="table-scroll" role="region" aria-label="${escapeHtml(label)}" tabindex="0">`,
                "<table>",
                `<thead><tr>${headers.map((cell) => `<th scope="col">${renderInline(cell)}</th>`).join("")}</tr></thead>`,
                `<tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${renderInline(cell)}</td>`).join("")}</tr>`).join("")}</tbody>`,
                "</table>",
                "</div>",
            ].join(""));
            continue;
        }

        const listItem = /^\s*(\d+\.|[-+*])\s+(.+)$/u.exec(line);
        if (listItem) {
            const ordered = listItem[1].endsWith(".");
            const items = [];
            while (index < lines.length) {
                const current = /^\s*(\d+\.|[-+*])\s+(.+)$/u.exec(lines[index]);
                if (!current || current[1].endsWith(".") !== ordered) {
                    break;
                }
                let item = current[2].trim();
                index += 1;
                while (index < lines.length && lines[index].trim() !== "" && !isBlockStart(lines, index)) {
                    item += ` ${lines[index].trim()}`;
                    index += 1;
                }
                items.push(item);
                if (lines[index]?.trim() === "") {
                    break;
                }
            }
            const tag = ordered ? "ol" : "ul";
            blocks.push(`<${tag}>${items.map((item) => `<li>${renderInline(item)}</li>`).join("")}</${tag}>`);
            continue;
        }

        const paragraph = [];
        while (index < lines.length && lines[index].trim() !== "" && !isBlockStart(lines, index)) {
            paragraph.push(lines[index].trim());
            index += 1;
        }
        blocks.push(`<p>${renderInline(paragraph.join(" "))}</p>`);
    }

    return blocks.join("\n");
}

function renderPage(document, markdown, canonicalUrl) {
    const expectedHeading = `# ${document.title}`;
    if (markdown.split("\n", 1)[0] !== expectedHeading) {
        throw new Error(`${document.source}: expected first heading ${expectedHeading}`);
    }
    if (!markdown.includes("2026년 8월 31일")) {
        throw new Error(`${document.source}: effective date is missing`);
    }
    if (!/문서 버전(?:은|:)\s*1\.0/u.test(markdown)) {
        throw new Error(`${document.source}: version 1.0 is missing`);
    }

    const body = renderMarkdown(markdown);
    if (body.includes("**") || /\]\(https:\/\//u.test(body)) {
        throw new Error(`${document.source}: unrendered inline Markdown remains`);
    }

    return `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
  <title>${escapeHtml(document.title)} | 라이모리</title>
  <link rel="canonical" href="${escapeHtml(canonicalUrl)}">
  <style>${PAGE_STYLE}</style>
</head>
<body>
  <a class="skip-link" href="#main-content">본문으로 바로가기</a>
  <main id="main-content" class="legal-document" aria-labelledby="document-title">
${body}
  </main>
</body>
</html>
`;
}

function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

function sqlLiteral(value) {
    return `'${value.replaceAll("'", "''")}'`;
}

function renderSeedSql(documents, instruction) {
    const catalogDocuments = documents
        .filter((document) => document.catalog !== null)
        .sort((left, right) => left.catalog.displayOrder - right.catalog.displayOrder);
    const nowKst = "CONVERT_TZ(NOW(6), @@session.time_zone, '+09:00')";
    const rows = catalogDocuments.map((document) => [
        "    (",
        sqlLiteral(document.catalog.termType), ", ",
        sqlLiteral(VERSION), ", ",
        sqlLiteral(document.title), ",\n     ",
        sqlLiteral(`${SITE_ORIGIN}/terms/${document.slug}/${VERSION}`), ", ",
        sqlLiteral(CATALOG_EFFECTIVE_AT_KST), ",\n     ",
        nowKst, ", ", nowKst, ")",
    ].join(""));

    return `-- Generated from docs/terms/drafts by docs/terms/scripts/build-site.mjs.
-- ${instruction}
SET NAMES utf8mb4;

INSERT INTO term_documents
    (term_type, version, title, content_url, effective_at, created_at, updated_at)
VALUES
${rows.join(",\n")};
`;
}

function assertSafeOutputRoot(outputRoot) {
    if (!new Set(["terms-site", "terms-content"]).has(basename(outputRoot))) {
        throw new Error(`Refusing to clean unexpected output directory: ${outputRoot}`);
    }
}

export async function buildTermsSite({ outputRoot = DEFAULT_OUTPUT_ROOT } = {}) {
    const resolvedOutputRoot = resolve(outputRoot);
    assertSafeOutputRoot(resolvedOutputRoot);
    await rm(resolvedOutputRoot, { recursive: true, force: true });
    await mkdir(resolvedOutputRoot, { recursive: true });

    const manifestDocuments = [];
    for (const document of DOCUMENTS) {
        const sourcePath = resolve(REPOSITORY_ROOT, document.source);
        const markdown = await readFile(sourcePath, "utf8");
        const canonicalUrl = `${SITE_ORIGIN}/terms/${document.slug}/${VERSION}`;
        const html = renderPage(document, markdown, canonicalUrl);
        const objectKey = `terms/${document.slug}/${VERSION}`;
        const outputPath = resolve(resolvedOutputRoot, objectKey);
        await mkdir(dirname(outputPath), { recursive: true });
        await writeFile(outputPath, html, "utf8");
        manifestDocuments.push({
            source: document.source,
            slug: document.slug,
            title: document.title,
            version: VERSION,
            canonicalUrl,
            objectKey,
            contentType: "text/html; charset=utf-8",
            cacheControl: CACHE_CONTROL,
            sourceSha256: sha256(markdown),
            htmlSha256: sha256(html),
            catalog: document.catalog,
        });
    }

    const manifest = {
        schemaVersion: 1,
        siteOrigin: SITE_ORIGIN,
        version: VERSION,
        effectiveAtKst: EFFECTIVE_AT_KST,
        generatedAt: new Date().toISOString(),
        documents: manifestDocuments,
    };
    await writeFile(resolve(resolvedOutputRoot, "publish-manifest.json"),
        `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
    await writeFile(resolve(resolvedOutputRoot, "term-documents-1.0.sql"),
        renderSeedSql(DOCUMENTS,
            "Empty-catalog bootstrap: run only after all six immutable HTTPS pages return 200 without authentication."),
        "utf8");
    await writeFile(resolve(resolvedOutputRoot, "term-documents-add-privacy-policy-1.0.sql"),
        renderSeedSql(
            DOCUMENTS.filter((document) => document.catalog?.termType === "PRIVACY_POLICY"),
            "Existing five-row catalog upgrade: run once after the privacy policy page returns 200 without authentication.",
        ),
        "utf8");

    return { outputRoot: resolvedOutputRoot, manifest };
}

async function syncStaticResources(buildResult) {
    assertSafeOutputRoot(STATIC_RESOURCE_ROOT);
    await rm(STATIC_RESOURCE_ROOT, { recursive: true, force: true });
    for (const document of buildResult.manifest.documents) {
        const source = resolve(buildResult.outputRoot, document.objectKey);
        const destination = resolve(STATIC_RESOURCE_ROOT, document.objectKey);
        await mkdir(dirname(destination), { recursive: true });
        await copyFile(source, destination);
    }
}

const invokedAsScript = process.argv[1]
    && pathToFileURL(resolve(process.argv[1])).href === import.meta.url;
if (invokedAsScript) {
    const result = await buildTermsSite();
    if (process.argv.includes("--sync-resources")) {
        await syncStaticResources(result);
    }
    process.stdout.write(`Built ${result.manifest.documents.length} immutable terms pages in ${result.outputRoot}\n`);
    if (process.argv.includes("--sync-resources")) {
        process.stdout.write(`Synced immutable pages to ${STATIC_RESOURCE_ROOT}\n`);
    }
}
