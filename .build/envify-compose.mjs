// Walk every per-feature docker-compose.yml and convert hard-coded
// values in the `environment:` block to the `${VAR:-default}` pattern,
// so operators can override them with a real .env file.
//
// Run from the repo root:
//   node .build/envify-compose.mjs           # dry-run, prints summary
//   node .build/envify-compose.mjs --write   # actually patch the files

import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const write = process.argv.includes('--write');

// Helper: strip optional surrounding double-quotes from a YAML scalar value.
const unquote = s => s.replace(/^"(.*)"$/, '$1');

// Each rule turns `KEY: literal` into `KEY: ${KEY:-literal}` exactly once
// per file. The regex anchors at the start of an indented line and accepts
// the value either bare (`ollama`) or quoted (`"ollama"`). We skip lines
// that already contain `${`, so re-running is idempotent.
//
// Order matters: more-specific patterns first.
const rules = [
    // 1. MONGODB_URI carries a per-example database name → match any path.
    {
        name: 'MONGODB_URI',
        regex: /^(\s+)MONGODB_URI:\s+("?)(mongodb:\/\/mongo:27017\/[A-Za-z0-9._-]+)\2\s*$/gm,
        format: (indent, _quote, value) => `${indent}MONGODB_URI: \${MONGODB_URI:-${value}}`,
    },
    // 2. REDIS_URL is identical everywhere.
    {
        name: 'REDIS_URL',
        regex: /^(\s+)REDIS_URL:\s+("?)redis:\/\/redis:6379\2\s*$/gm,
        format: (indent) => `${indent}REDIS_URL: \${REDIS_URL:-redis://redis:6379}`,
    },
    // 3. LLM_*_PROVIDER (only ollama, openai, anthropic, azure-openai — never a templated value).
    {
        name: 'LLM_*_PROVIDER',
        regex: /^(\s+)LLM_(PRIMARY|FALLBACK|ROUTING)_PROVIDER:\s+("?)(ollama|openai|anthropic|azure-openai)\3\s*$/gm,
        format: (indent, role, _quote, value) =>
            `${indent}LLM_${role}_PROVIDER: \${LLM_${role}_PROVIDER:-${value}}`,
    },
    // 4. LLM_*_API_KEY: "" or just empty.
    {
        name: 'LLM_*_API_KEY',
        regex: /^(\s+)LLM_(PRIMARY|FALLBACK|ROUTING)_API_KEY:\s+(?:""|'')?\s*$/gm,
        format: (indent, role) =>
            `${indent}LLM_${role}_API_KEY: \${LLM_${role}_API_KEY:-}`,
    },
    // 5. LLM_*_ENDPOINT: http://ollama:11434 (with or without quotes).
    {
        name: 'LLM_*_ENDPOINT',
        regex: /^(\s+)LLM_(PRIMARY|FALLBACK|ROUTING)_ENDPOINT:\s+("?)http:\/\/ollama:11434\3\s*$/gm,
        format: (indent, role) =>
            `${indent}LLM_${role}_ENDPOINT: \${LLM_${role}_ENDPOINT:-http://ollama:11434}`,
    },
    // 6. LLM_*_MODEL: <literal value, no $> — preserve the specific model name as the default.
    {
        name: 'LLM_*_MODEL',
        regex: /^(\s+)LLM_(PRIMARY|FALLBACK|ROUTING)_MODEL:\s+("?)([A-Za-z0-9._:\/-]+)\3\s*$/gm,
        format: (indent, role, _quote, value) =>
            `${indent}LLM_${role}_MODEL: \${LLM_${role}_MODEL:-${value}}`,
        // Skip if the value already starts with ${ — handled by the
        // post-check below since the regex would not match those anyway
        // (the character class excludes '$').
    },
    // 7. OLLAMA_HOST inside the ollama-init service.
    {
        name: 'OLLAMA_HOST',
        regex: /^(\s+)OLLAMA_HOST:\s+("?)http:\/\/ollama:11434\2\s*$/gm,
        format: (indent) => `${indent}OLLAMA_HOST: \${OLLAMA_HOST:-http://ollama:11434}`,
    },
];

const dirs = readdirSync(root)
    .filter(d => d.startsWith('agent-example-'))
    .map(d => join(root, d, 'docker-compose.yml'))
    .filter(p => {
        try { return statSync(p).isFile(); } catch { return false; }
    });

let totalEdits = 0;
const summary = [];

for (const file of dirs) {
    const before = readFileSync(file, 'utf8');
    let after = before;
    let perFileEdits = 0;
    const perRule = {};
    for (const rule of rules) {
        after = after.replace(rule.regex, (match, ...caps) => {
            // Skip lines that already contain a ${VAR:-…} pattern — idempotency.
            if (match.includes('${')) return match;
            perFileEdits++;
            perRule[rule.name] = (perRule[rule.name] || 0) + 1;
            return rule.format(...caps);
        });
    }
    if (perFileEdits > 0) {
        totalEdits += perFileEdits;
        summary.push({
            file: file.replace(root + '\\', '').replace(root + '/', ''),
            edits: perFileEdits,
            perRule,
        });
        if (write) writeFileSync(file, after, 'utf8');
    }
}

if (!summary.length) {
    console.log('Nothing to do — every docker-compose already uses ${VAR:-default}.');
    process.exit(0);
}

console.log(`${write ? 'Patched' : '[dry-run] Would patch'} ${summary.length} files (${totalEdits} substitutions total):\n`);
for (const { file, edits, perRule } of summary) {
    const breakdown = Object.entries(perRule).map(([k, v]) => `${k}×${v}`).join(', ');
    console.log(`  ${String(edits).padStart(3)} edits  ${file.padEnd(50)} (${breakdown})`);
}

if (!write) {
    console.log('\nRun again with --write to apply.');
}
