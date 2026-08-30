import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { dirname } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { buildTermsSite, DOCUMENTS } from "./build-site.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../../..");

test("builds six mobile legal pages and six catalog seed rows", async () => {
    const temporaryRoot = await mkdtemp(join(tmpdir(), "laimory-terms-test-"));
    const outputRoot = resolve(temporaryRoot, "terms-site");
    const { manifest } = await buildTermsSite({ outputRoot });

    assert.equal(manifest.documents.length, 6);
    assert.deepEqual(
        manifest.documents.filter((document) => document.catalog !== null)
            .map((document) => document.catalog.termType)
            .sort(),
        [
            "CROSS_BORDER_TRANSFER_CONSENT",
            "LOCATION_BASED_SERVICE_TERMS",
            "PRIVACY_POLICY",
            "SENSITIVE_INFORMATION_CONSENT",
            "TERMS_OF_SERVICE",
            "THIRD_PARTY_PROVISION_CONSENT",
        ],
    );

    for (const document of DOCUMENTS) {
        const html = await readFile(resolve(outputRoot, `terms/${document.slug}/1.0`), "utf8");
        const committedResource = await readFile(
            resolve(repositoryRoot, `src/main/resources/terms-content/terms/${document.slug}/1.0`), "utf8");
        assert.equal(committedResource, html, `${document.slug} static resource is stale`);
        assert.match(html, /<meta name="viewport" content="width=device-width, initial-scale=1">/u);
        assert.match(html, new RegExp(`<h1 id="document-title">${document.title}</h1>`, "u"));
        assert.match(html, new RegExp(`https://laimory\\.app/terms/${document.slug}/1\\.0`, "u"));
        assert.match(html, /2026년 8월 31일/u);
        assert.doesNotMatch(html, /<script/u);
        assert.doesNotMatch(html, /\*\*/u);
        assert.doesNotMatch(html, /\]\(https:\/\//u);
    }

    const privacyPolicy = await readFile(resolve(outputRoot, "terms/privacy-policy/1.0"), "utf8");
    assert.match(privacyPolicy, /<div class="table-scroll"/u);
    assert.match(privacyPolicy, /<strong>미국 및 Google의 FCM 글로벌 처리 가능 국가/u);
    assert.match(privacyPolicy, /<a href="https:\/\/firebase\.google\.com\/support\/privacy\/dpo">/u);

    const thirdPartyConsent = await readFile(
        resolve(outputRoot, "terms/third-party-provision-consent/1.0"), "utf8");
    assert.match(thirdPartyConsent, /<br><br><strong>기록 요약 갱신 시:<\/strong>/u);

    const seedSql = await readFile(resolve(outputRoot, "term-documents-1.0.sql"), "utf8");
    assert.match(seedSql, /SET NAMES utf8mb4;\n\nINSERT INTO term_documents/u);
    assert.match(seedSql, /'2026-08-28 00:00:00'/u);
    assert.match(seedSql, /'TERMS_OF_SERVICE'/u);
    assert.match(seedSql, /'THIRD_PARTY_PROVISION_CONSENT'/u);
    assert.match(seedSql, /'SENSITIVE_INFORMATION_CONSENT'/u);
    assert.match(seedSql, /'CROSS_BORDER_TRANSFER_CONSENT'/u);
    assert.match(seedSql, /'LOCATION_BASED_SERVICE_TERMS'/u);
    assert.match(seedSql, /'PRIVACY_POLICY'/u);

    const privacyPolicyDeltaSql = await readFile(
        resolve(outputRoot, "term-documents-add-privacy-policy-1.0.sql"), "utf8");
    assert.match(privacyPolicyDeltaSql, /Existing five-row catalog upgrade/u);
    assert.match(privacyPolicyDeltaSql, /SET NAMES utf8mb4;\n\nINSERT INTO term_documents/u);
    assert.doesNotMatch(privacyPolicyDeltaSql, /INSERT IGNORE/u);
    assert.match(privacyPolicyDeltaSql, /'PRIVACY_POLICY'/u);
    assert.doesNotMatch(privacyPolicyDeltaSql, /'TERMS_OF_SERVICE'/u);
    assert.doesNotMatch(privacyPolicyDeltaSql, /'THIRD_PARTY_PROVISION_CONSENT'/u);
    assert.doesNotMatch(privacyPolicyDeltaSql, /'SENSITIVE_INFORMATION_CONSENT'/u);
    assert.doesNotMatch(privacyPolicyDeltaSql, /'CROSS_BORDER_TRANSFER_CONSENT'/u);
    assert.doesNotMatch(privacyPolicyDeltaSql, /'LOCATION_BASED_SERVICE_TERMS'/u);
});
