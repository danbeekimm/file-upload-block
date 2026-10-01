/**
 * ERD 생성 스크립트
 *
 * 실행:  node scripts/generate-erd.js
 * 산출물:
 *   docs/erd.svg   - 독립 실행 SVG (GitHub 마크다운에서 그대로 렌더링)
 *   docs/ERD.md    - 파일 안의 <!-- mermaid:start --> ~ <!-- mermaid:end --> 구간만 갱신
 *
 * 아래 tables 정의는 2026-10-01에 실행 중인 PostgreSQL(fileupload-db, localhost:55432)의
 * information_schema / pg_constraint 를 조회한 결과를 옮긴 것이다. schema.sql 이 바뀌면
 * 이 정의도 같이 고쳐야 한다.
 */
const fs = require('fs');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..');
const svgOutputPath = path.join(projectRoot, 'docs', 'erd.svg');
const markdownPath = path.join(projectRoot, 'docs', 'ERD.md');

// ---------------------------------------------------------------------------
// 1. 스키마 정의
// ---------------------------------------------------------------------------

/** @typedef {{ name: string, type: string, key: ''|'PK'|'FK'|'UK', nullable: boolean }} Column */
/** @typedef {{ name: string, x: number, y: number, width: number, liveRowCount: number, columns: Column[] }} Table */

function column(name, type, options = {}) {
  return { name, type, key: options.key || '', nullable: options.nullable === true };
}

/** @type {Table[]} */
const tables = [
  {
    name: 'upload_policy', x: 40, y: 230, width: 280, liveRowCount: 1,
    columns: [
      column('id', 'BIGSERIAL', { key: 'PK' }),
      column('name', 'VARCHAR(50)', { key: 'UK' }),
      column('description', 'VARCHAR(255)', { nullable: true }),
      column('max_image_bytes', 'BIGINT'),
      column('max_file_bytes', 'BIGINT'),
      column('max_files_per_request', 'SMALLINT'),
      column('max_request_bytes', 'BIGINT'),
      column('version', 'INT'),
      column('created_at', 'TIMESTAMPTZ'),
      column('updated_at', 'TIMESTAMPTZ'),
    ],
  },
  {
    name: 'extension_rule', x: 460, y: 40, width: 280, liveRowCount: 8,
    columns: [
      column('id', 'BIGSERIAL', { key: 'PK' }),
      column('policy_id', 'BIGINT', { key: 'FK' }),
      column('extension', 'VARCHAR(20)', { key: 'UK' }),
      column('rule_type', 'VARCHAR(10)'),
      column('blocked', 'BOOLEAN'),
      column('version', 'INT'),
      column('created_at', 'TIMESTAMPTZ'),
      column('updated_at', 'TIMESTAMPTZ'),
    ],
  },
  {
    name: 'policy_change_log', x: 460, y: 270, width: 280, liveRowCount: 2,
    columns: [
      column('id', 'BIGSERIAL', { key: 'PK' }),
      column('policy_id', 'BIGINT', { key: 'FK' }),
      column('target', 'VARCHAR(20)'),
      column('extension', 'VARCHAR(20)', { nullable: true }),
      column('action', 'VARCHAR(10)'),
      column('detail', 'JSONB', { nullable: true }),
      column('actor_ip', 'VARCHAR(45)', { nullable: true }),
      column('user_agent', 'VARCHAR(255)', { nullable: true }),
      column('changed_at', 'TIMESTAMPTZ'),
    ],
  },
  {
    name: 'ip_block', x: 460, y: 520, width: 280, liveRowCount: 0,
    columns: [
      column('id', 'BIGSERIAL', { key: 'PK' }),
      column('client_ip', 'VARCHAR(45)'),
      column('score', 'SMALLINT'),
      column('blocked_at', 'TIMESTAMPTZ'),
      column('blocked_until', 'TIMESTAMPTZ'),
      column('released_at', 'TIMESTAMPTZ', { nullable: true }),
      column('released_by_ip', 'VARCHAR(45)', { nullable: true }),
    ],
  },
  {
    name: 'file_upload', x: 860, y: 40, width: 300, liveRowCount: 6,
    columns: [
      column('id', 'BIGSERIAL', { key: 'PK' }),
      column('public_id', 'UUID', { key: 'UK' }),
      column('request_id', 'UUID'),
      column('policy_id', 'BIGINT', { key: 'FK' }),
      column('policy_version', 'BIGINT'),
      column('status', 'VARCHAR(10)'),
      column('reject_reason', 'VARCHAR(30)', { nullable: true }),
      column('trust_level', 'VARCHAR(10)', { nullable: true }),
      column('violation_score', 'SMALLINT'),
      column('original_name', 'VARCHAR(255)'),
      column('download_name', 'VARCHAR(255)', { nullable: true }),
      column('extension', 'VARCHAR(20)', { nullable: true }),
      column('size_bytes', 'BIGINT'),
      column('sha256_original', 'CHAR(64)', { nullable: true }),
      column('sha256_stored', 'CHAR(64)', { nullable: true }),
      column('claimed_mime', 'VARCHAR(100)', { nullable: true }),
      column('detected_type', 'VARCHAR(100)', { nullable: true }),
      column('content_type', 'VARCHAR(100)', { nullable: true }),
      column('disposition', 'VARCHAR(10)', { nullable: true }),
      column('storage_key', 'VARCHAR(100)', { nullable: true }),
      column('client_ip', 'VARCHAR(45)', { nullable: true }),
      column('created_at', 'TIMESTAMPTZ'),
      column('deleted_at', 'TIMESTAMPTZ', { nullable: true }),
    ],
  },
];

/** 실제 FK 관계. 부모는 모두 upload_policy, 자식 쪽 FK 컬럼은 전부 NOT NULL. */
const foreignKeyRelations = [
  { child: 'extension_rule', parent: 'upload_policy', column: 'policy_id' },
  { child: 'policy_change_log', parent: 'upload_policy', column: 'policy_id' },
  { child: 'file_upload', parent: 'upload_policy', column: 'policy_id' },
];

// ---------------------------------------------------------------------------
// 2. SVG 그리기
// ---------------------------------------------------------------------------

const HEADER_HEIGHT = 28;
const ROW_HEIGHT = 18;
const CANVAS_WIDTH = 1200;
const CANVAS_HEIGHT = 700;

const svgStyle = `
  text { font-family: "IBM Plex Mono", "Cascadia Mono", Consolas, "D2Coding", monospace; }
  .table-box   { fill: #ffffff; stroke: #d9dee7; stroke-width: 1; }
  .table-head  { fill: #e8edf5; }
  .table-rule  { stroke: #d9dee7; stroke-width: 1; }
  .table-name  { font-size: 13px; font-weight: 600; fill: #1c2230; }
  .table-count { font-size: 10.5px; fill: #8a93a3; }
  .column-name { font-size: 11px; fill: #1c2230; }
  .column-type { font-size: 11px; fill: #5a6475; }
  .nullable    { fill: #8a93a3; font-style: italic; }
  .key-badge   { font-size: 9.5px; font-weight: 600; }
  .key-pk      { fill: #1f5fa8; }
  .key-fk      { fill: #b3541e; }
  .key-uk      { fill: #5b6b85; }
  .relation        { fill: none; stroke: #1f5fa8; stroke-width: 1.4; }
  .relation-logical{ fill: none; stroke: #8a93a3; stroke-width: 1.4; stroke-dasharray: 5 4; }
  .relation-dot    { fill: #ffffff; stroke: #8a93a3; stroke-width: 1.4; }
  .relation-label  { font-size: 10.5px; fill: #1f5fa8; }
  .relation-label-muted { font-size: 10.5px; fill: #8a93a3; }
  .cardinality     { font-size: 11px; font-weight: 600; fill: #1f5fa8; }
  .legend          { font-size: 11px; fill: #5a6475; }
`;

function tableHeight(table) {
  return HEADER_HEIGHT + ROW_HEIGHT * table.columns.length;
}

function findTable(name) {
  const found = tables.find((table) => table.name === name);
  if (!found) throw new Error(`정의되지 않은 테이블: ${name}`);
  return found;
}

function drawTable(table) {
  const height = tableHeight(table);
  const right = table.x + table.width;
  const parts = [];

  parts.push(`<g id="table-${table.name}">`);
  parts.push(`<rect x="${table.x}" y="${table.y}" width="${table.width}" height="${height}" rx="4" class="table-box"/>`);
  parts.push(`<rect x="${table.x}" y="${table.y}" width="${table.width}" height="${HEADER_HEIGHT}" rx="4" class="table-head"/>`);
  // 헤더 아래쪽 모서리를 직각으로 메움
  parts.push(`<rect x="${table.x}" y="${table.y + HEADER_HEIGHT - 4}" width="${table.width}" height="4" class="table-head"/>`);
  parts.push(`<line x1="${table.x}" y1="${table.y + HEADER_HEIGHT}" x2="${right}" y2="${table.y + HEADER_HEIGHT}" class="table-rule"/>`);
  parts.push(`<text x="${table.x + 10}" y="${table.y + 18.5}" class="table-name">${table.name}</text>`);
  parts.push(`<text x="${right - 10}" y="${table.y + 18.5}" text-anchor="end" class="table-count">${table.liveRowCount} rows</text>`);

  table.columns.forEach((col, index) => {
    const baseline = table.y + HEADER_HEIGHT + ROW_HEIGHT * index + 13;
    const nullableClass = col.nullable ? ' nullable' : '';
    if (col.key) {
      parts.push(`<text x="${table.x + 10}" y="${baseline}" class="key-badge key-${col.key.toLowerCase()}">${col.key}</text>`);
    }
    parts.push(`<text x="${table.x + 36}" y="${baseline}" class="column-name${nullableClass}">${col.name}</text>`);
    parts.push(`<text x="${right - 10}" y="${baseline}" text-anchor="end" class="column-type${nullableClass}">${col.type}${col.nullable ? '?' : ''}</text>`);
  });

  parts.push('</g>');
  return parts.join('\n');
}

/** 부모 쪽 끝: "정확히 하나" = 세로 막대 두 줄 */
function drawExactlyOneMark(x, y) {
  return [
    `<line x1="${x + 6}" y1="${y - 7}" x2="${x + 6}" y2="${y + 7}" class="relation"/>`,
    `<line x1="${x + 11}" y1="${y - 7}" x2="${x + 11}" y2="${y + 7}" class="relation"/>`,
  ].join('\n');
}

/** 자식 쪽 끝: 까마귀발(여러 개) + 세로 막대(필수) */
function drawManyMandatoryMark(x, y) {
  return [
    `<line x1="${x - 16}" y1="${y}" x2="${x}" y2="${y - 7}" class="relation"/>`,
    `<line x1="${x - 16}" y1="${y}" x2="${x}" y2="${y}" class="relation"/>`,
    `<line x1="${x - 16}" y1="${y}" x2="${x}" y2="${y + 7}" class="relation"/>`,
    `<line x1="${x - 20}" y1="${y - 7}" x2="${x - 20}" y2="${y + 7}" class="relation"/>`,
  ].join('\n');
}

/**
 * 선 경로는 테이블 배치에 맞춰 수동으로 정했다.
 * - extension_rule 로 가는 선은 x=390 에서 위로 꺾인다.
 * - file_upload 로 가는 선은 x=420 에서 꺾여 extension_rule 과 policy_change_log 사이 틈(y=241)을 지난다.
 * - policy_change_log 로 가는 선은 직선.
 */
function drawForeignKeyRelations() {
  const parent = findTable('upload_policy');
  const parentRight = parent.x + parent.width;
  const extensionRule = findTable('extension_rule');
  const changeLog = findTable('policy_change_log');
  const fileUpload = findTable('file_upload');

  const routes = [
    { child: extensionRule, exitY: 300, enterY: 126, bendX: 390 },
    { child: fileUpload, exitY: 334, enterY: 241, bendX: 420 },
    { child: changeLog, exitY: 368, enterY: 368, bendX: null },
  ];

  const parts = [];
  for (const route of routes) {
    const childLeft = route.child.x;
    const points = route.bendX === null
      ? `${parentRight},${route.exitY} ${childLeft},${route.enterY}`
      : `${parentRight},${route.exitY} ${route.bendX},${route.exitY} ${route.bendX},${route.enterY} ${childLeft},${route.enterY}`;

    parts.push(`<polyline points="${points}" class="relation"/>`);
    parts.push(drawExactlyOneMark(parentRight, route.exitY));
    parts.push(drawManyMandatoryMark(childLeft, route.enterY));
    // 라벨은 까마귀발 바로 왼쪽에 오른쪽 정렬, N 은 까마귀발 바로 위
    parts.push(`<text x="${childLeft - 34}" y="${route.enterY - 5}" text-anchor="end" class="relation-label">policy_id</text>`);
    parts.push(`<text x="${parentRight + 16}" y="${route.exitY - 5}" class="cardinality">1</text>`);
    parts.push(`<text x="${childLeft - 8}" y="${route.enterY - 5}" text-anchor="end" class="cardinality">N</text>`);
  }
  return parts.join('\n');
}

/** ip_block 과 file_upload 는 FK 없이 client_ip 문자열로만 이어진다. 점선으로 표시. */
function drawLogicalRelation() {
  const ipBlock = findTable('ip_block');
  const fileUpload = findTable('file_upload');
  const ipBlockRight = ipBlock.x + ipBlock.width;
  const ipBlockMiddleY = 597;
  const fileUploadBottom = fileUpload.y + tableHeight(fileUpload);
  const bendX = 1010;

  return [
    `<polyline points="${ipBlockRight},${ipBlockMiddleY} ${bendX},${ipBlockMiddleY} ${bendX},${fileUploadBottom}" class="relation-logical"/>`,
    `<circle cx="${ipBlockRight + 1}" cy="${ipBlockMiddleY}" r="3.5" class="relation-dot"/>`,
    `<circle cx="${bendX}" cy="${fileUploadBottom - 1}" r="3.5" class="relation-dot"/>`,
    `<text x="${ipBlockRight + 12}" y="${ipBlockMiddleY - 7}" class="relation-label-muted">client_ip 문자열로만 연결 · FK 없음</text>`,
  ].join('\n');
}

function drawLegend() {
  const x = 40;
  const y = 560;
  return [
    `<g id="legend">`,
    `<text x="${x}" y="${y}" class="legend"><tspan class="key-badge key-pk">PK</tspan> 기본키   <tspan class="key-badge key-fk">FK</tspan> 외래키   <tspan class="key-badge key-uk">UK</tspan> 유니크</text>`,
    `<text x="${x}" y="${y + 20}" class="legend"><tspan class="nullable">타입?</tspan> NULL 허용 컬럼</text>`,
    `<text x="${x}" y="${y + 40}" class="legend">1 ─────&lt; N   1:N, 양쪽 필수 (FK NOT NULL)</text>`,
    `<text x="${x}" y="${y + 60}" class="legend">○ ─ ─ ─ ○   FK 없는 논리적 연결</text>`,
    `<text x="${x}" y="${y + 90}" class="legend">PostgreSQL 16 · fileupload · 2026-10-01 조회 · 행 수는 조회 시점 값</text>`,
    `</g>`,
  ].join('\n');
}

function buildSvg() {
  const description = 'fileupload 데이터베이스 ERD. upload_policy 하나에 extension_rule, policy_change_log, file_upload 가 여러 개 매달리고, ip_block 은 FK 없이 client_ip 로만 file_upload 와 느슨하게 연결된다.';
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS_WIDTH} ${CANVAS_HEIGHT}" width="${CANVAS_WIDTH}" height="${CANVAS_HEIGHT}" role="img" aria-label="${description}">`,
    `<style>${svgStyle}</style>`,
    `<rect width="${CANVAS_WIDTH}" height="${CANVAS_HEIGHT}" fill="#f7f8fa"/>`,
    drawForeignKeyRelations(),
    drawLogicalRelation(),
    ...tables.map(drawTable),
    drawLegend(),
    `</svg>`,
  ].join('\n');
}

// ---------------------------------------------------------------------------
// 3. Mermaid erDiagram
// ---------------------------------------------------------------------------

/** Mermaid 는 타입 이름에 괄호를 허용하지 않아 VARCHAR(50) → varchar_50 으로 바꾼다. */
function toMermaidType(sqlType) {
  return sqlType.toLowerCase().replace(/[()]/g, '_').replace(/_$/, '');
}

function buildMermaid() {
  const lines = ['erDiagram'];
  for (const table of tables) {
    lines.push(`    ${table.name} {`);
    for (const col of table.columns) {
      const keyPart = col.key ? ` ${col.key}` : '';
      const commentPart = col.nullable ? ' "nullable"' : '';
      lines.push(`        ${toMermaidType(col.type)} ${col.name}${keyPart}${commentPart}`);
    }
    lines.push('    }');
  }
  for (const relation of foreignKeyRelations) {
    lines.push(`    ${relation.parent} ||--|{ ${relation.child} : "${relation.column}"`);
  }
  lines.push('    file_upload }o..o{ ip_block : "client_ip (FK 없음)"');
  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// 4. 파일 쓰기
// ---------------------------------------------------------------------------

function updateMermaidBlockInMarkdown(markdown, mermaidSource) {
  const startMarker = '<!-- mermaid:start -->';
  const endMarker = '<!-- mermaid:end -->';
  const startIndex = markdown.indexOf(startMarker);
  const endIndex = markdown.indexOf(endMarker);
  if (startIndex === -1 || endIndex === -1) {
    throw new Error(`${markdownPath} 에 ${startMarker} / ${endMarker} 표시가 없습니다.`);
  }
  const replacement = `${startMarker}\n\`\`\`mermaid\n${mermaidSource}\n\`\`\`\n`;
  return markdown.slice(0, startIndex) + replacement + markdown.slice(endIndex);
}

function main() {
  fs.writeFileSync(svgOutputPath, buildSvg(), 'utf8');
  console.log(`wrote ${path.relative(projectRoot, svgOutputPath)}`);

  const markdown = fs.readFileSync(markdownPath, 'utf8');
  fs.writeFileSync(markdownPath, updateMermaidBlockInMarkdown(markdown, buildMermaid()), 'utf8');
  console.log(`updated mermaid block in ${path.relative(projectRoot, markdownPath)}`);
}

main();
