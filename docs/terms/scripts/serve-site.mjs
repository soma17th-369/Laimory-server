import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../../..");
const outputRoot = resolve(repositoryRoot, "build/terms-site");
const port = Number(process.env.PORT ?? "4173");

const server = createServer(async (request, response) => {
    try {
        if (request.method !== "GET" && request.method !== "HEAD") {
            response.writeHead(405, { Allow: "GET, HEAD" }).end();
            return;
        }
        const pathname = decodeURIComponent(new URL(request.url, `http://${request.headers.host}`).pathname);
        const requestedPath = resolve(outputRoot, `.${pathname}`);
        if (requestedPath !== outputRoot && !requestedPath.startsWith(`${outputRoot}${sep}`)) {
            response.writeHead(400).end("Bad request");
            return;
        }
        const file = await stat(requestedPath);
        if (!file.isFile()) {
            response.writeHead(404).end("Not found");
            return;
        }
        const body = await readFile(requestedPath);
        const contentType = pathname.endsWith(".json")
            ? "application/json; charset=utf-8"
            : pathname.endsWith(".sql")
                ? "text/plain; charset=utf-8"
                : "text/html; charset=utf-8";
        response.writeHead(200, {
            "Content-Type": contentType,
            "Content-Length": body.length,
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
        });
        response.end(request.method === "HEAD" ? undefined : body);
    } catch {
        response.writeHead(404).end("Not found");
    }
});

server.listen(port, "127.0.0.1", () => {
    process.stdout.write(`Terms preview: http://127.0.0.1:${port}/terms/privacy-policy/1.0\n`);
});
