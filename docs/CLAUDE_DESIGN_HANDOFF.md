# Witness — Product and UX Design Handoff

## Instructions for Claude Design

Use this document as the authoritative product brief for Witness, a working semantic-layer application. Produce design requirements that an engineering team can implement; do not reduce the result to a visual mood board.

Your response should include:

1. Recommended information architecture and navigation.
2. Page-by-page functional requirements.
3. Primary user flows and edge cases.
4. Low- or medium-fidelity wireframe descriptions for desktop.
5. Component inventory and reusable interaction patterns.
6. Empty, loading, success, warning, error, permission, and conflict states.
7. Form behavior and validation requirements.
8. Responsive behavior and accessibility requirements.
9. A proposed visual direction suitable for a technical data-governance product.
10. Any product questions or contradictions that must be resolved before high-fidelity design.

Do not design functionality outside the stated MVP unless you clearly label it as a future recommendation.

---

## 1. Product summary

The product is a semantic layer for analytical systems. It lets teams define governed business-facing data objects, dimensions, metrics, and relationships in YAML, explore that model in a Web UI, and query it using PostgreSQL-compatible SQL. Queries are compiled into safe Trino SQL and physically executed by Trino.

The product connects three worlds:

- **Business semantics:** understandable objects and trusted metrics.
- **Engineering governance:** YAML, validation, version control, and GitLab merge requests.
- **Analytical consumption:** PostgreSQL JDBC and BI tools that discover semantic objects as schemas, tables, and columns.

The Web UI is both a catalog and a model-authoring workspace. It must make the active model easy to understand while preserving Git-based governance for changes.

### One-sentence value proposition

Define business metrics once, govern them as code, and query them from any PostgreSQL-compatible analytics client.

---

## 2. Fixed technical and product decisions

The following decisions are already made and should not be redesigned:

- Query engine: **Trino**.
- SQL interface: **PostgreSQL wire protocol**.
- Client compatibility target: PostgreSQL JDBC and compatible BI clients, initially pgjdbc and DBeaver.
- Semantic model format: versioned **YAML**.
- Production source of truth: a **GitLab repository default branch**.
- Production changes: branch → one atomic commit → Merge Request → merge → reload.
- The UI must never write directly to the GitLab default branch.
- The application is a modular monolith for the MVP.
- SQL is read-only and supports only a controlled `SELECT` subset.
- Physical tables cannot be queried unless registered in the semantic model.
- Domains are represented as PostgreSQL schemas.
- Semantic objects are represented as read-only virtual PostgreSQL tables.
- Dimensions and metrics are represented as columns on those tables.
- Metrics are not separate PostgreSQL tables.

Example database hierarchy in DBeaver:

```text
semantic database
├── retail schema
│   ├── customers table
│   ├── orders table
│   └── products table
└── ai_rnd schema
    └── experiments table
```

Example query:

```sql
SELECT
    customer_id,
    total_revenue
FROM retail.orders
GROUP BY customer_id
ORDER BY total_revenue DESC
LIMIT 100;
```

---

## 3. Product goals

### 3.1 MVP goals

- Make the semantic model discoverable to analysts and data engineers.
- Group model content by business or technical domain.
- Explain the physical lineage and business meaning of every object and metric.
- Let users find objects and metrics quickly.
- Let authorized users create, inspect, update, and delete metrics.
- Let authorized users propose object, relationship, and model changes.
- Validate model changes before submission.
- Show a comprehensible diff against the current GitLab default branch.
- Create a GitLab Merge Request without making the proposed model active.
- Expose the active model as PostgreSQL schemas, tables, dimensions, and metrics.
- Preserve the last valid active model if a newer revision is invalid or unavailable.

### 3.2 Success criteria

A successful user can:

1. Open the catalog and understand which domains exist.
2. Find an object or metric without knowing its YAML filename.
3. Understand an object's dimensions, metrics, relationships, ownership, and physical source.
4. Understand a metric's formula, aggregation, filters, result type, and base object.
5. Create or edit a metric through a form or YAML.
6. See validation issues at the exact field that caused them.
7. Review a multi-file diff and submit one governed change.
8. Open the resulting GitLab Merge Request.
9. Connect through DBeaver or JDBC and discover the same domain/object hierarchy.

### 3.3 Non-goals for MVP

- Data ingestion or analytical data storage.
- Replacing Trino as the compute engine.
- DML or DDL through the SQL endpoint.
- Full PostgreSQL dialect compatibility.
- Derived metrics composed from other metrics.
- Automatic arbitrary join-path discovery.
- Pre-aggregations, materialized views, or query-result caching.
- Row-level or column-level security.
- Complex RBAC, SSO, or multi-tenancy.
- Time-intelligence features.
- Git conflict resolution inside the UI.
- Automatic MR merge.
- Full YAML CI/CD management.

---

## 4. Target users and jobs

### 4.1 Data analyst / BI developer

Primary jobs:

- Find trusted business metrics.
- Learn which dimensions can be used with a metric.
- Understand metric meaning and filters.
- Discover tables and columns in DBeaver or a BI tool.
- Copy an example SQL query or connection details.

Needs:

- Clear business labels and descriptions.
- Search and filters.
- Visible ownership and status.
- Minimal exposure to Git/YAML unless requested.

### 4.2 Analytics engineer

Primary jobs:

- Create and edit semantic objects and metrics.
- Define relationships and physical mappings.
- Validate SQL expressions and model references.
- Review diffs and submit Merge Requests.

Needs:

- Efficient forms plus direct YAML access.
- Strong inline validation.
- Dependency and impact information.
- Exact technical identifiers and physical lineage.

### 4.3 Data platform engineer / administrator

Primary jobs:

- Monitor active model status and revision.
- Diagnose reload, GitLab, Trino, or validation failures.
- Confirm SQL/JDBC availability.
- Configure and operate the platform.

Needs:

- Health and model status visibility.
- Revision, timestamps, and structured errors.
- Connection instructions and known compatibility limitations.

### 4.4 Domain owner / reviewer

Primary jobs:

- Review definitions in their domain.
- Confirm business meaning and ownership.
- Review proposed changes in GitLab.

Needs:

- Domain-level grouping.
- Human-readable change summaries.
- Direct link to the Merge Request.

---

## 5. Core mental model and entities

### 5.1 Domain

A domain is the highest-level grouping visible to users. Examples: `retail`, `ai_rnd`, `finance`, `marketing`.

Rules:

- A domain is represented as a PostgreSQL schema.
- Every object belongs to exactly one domain.
- Every metric belongs to exactly one domain.
- A metric's domain must match the domain of its base object.
- Domain names must be safe SQL identifiers: `[A-Za-z_][A-Za-z0-9_]*`.
- In the current MVP, a domain is derived from object and metric metadata; it is not a separately editable entity.
- The UI should still present a domain as a coherent catalog grouping with counts and content.

### 5.2 Semantic object

A semantic object is a logical, read-only analytical table.

It contains:

- Technical name.
- Domain.
- Label and description.
- Owner and tags.
- Physical source: Trino catalog, schema, and table.
- Primary-key fields.
- Dimensions.
- Relationships.
- Associated metrics.
- Source YAML file.

MVP constraint: object technical names are globally unique across the complete model, even though SQL references include the domain.

### 5.3 Dimension

A dimension is a logical column exposed on a semantic object.

It contains:

- Technical name.
- Optional label and description.
- Semantic/JDBC type.
- SQL expression that maps to physical data.
- Nullable flag.
- Whether it is part of the primary key.

### 5.4 Metric

A metric is a governed aggregate exposed as a logical column on one base object.

It contains:

- Technical name.
- Domain.
- Label and description.
- Owner and tags.
- Base object.
- Aggregation: `sum`, `count`, `count_distinct`, `min`, `max`, `avg`, or `custom`.
- SQL expression.
- Result type.
- Display format.
- Zero or more filters.
- Source YAML file.

Rules:

- A metric belongs to one base object.
- A metric's domain must match its base object's domain.
- A metric name cannot collide with a dimension on its base object.
- Metric names are globally unique in the current MVP.
- A metric can be selected directly or wrapped in a compatible aggregate by BI tools without causing double aggregation.
- Metrics composed from other metrics are outside MVP scope.

### 5.5 Metric filter

A metric filter contains:

- Field on the base object.
- Operator: `eq`, `neq`, `in`, `not_in`, `gt`, `gte`, `lt`, `lte`, `is_null`, or `is_not_null`.
- Zero or more typed values, depending on operator.

### 5.6 Relationship

A relationship connects a source object to a target object.

It contains:

- Name.
- Target object.
- One or more source fields.
- Matching target fields.
- Cardinality: `one_to_one`, `one_to_many`, `many_to_one`, or `many_to_many`.
- Default join type: `inner`, `left`, `right`, or `full`.

Rules:

- Source and target field counts must match and be non-zero.
- Referenced fields must exist and have compatible types.
- SQL joins are explicit and must match a declared relationship.
- Cross-domain relationships may exist and should be visually distinguishable.

### 5.7 Change set

A change set is one proposed model update and can contain multiple YAML files.

It contains:

- Changed or new files.
- Deleted files where supported.
- Change title.
- Commit message.
- Merge Request description.
- Base revision.
- Validation result.
- Diff.
- Affected objects and metrics.

### 5.8 Active model revision

Only one immutable, validated model revision is active at a time.

It contains:

- Revision/SHA.
- Load timestamp.
- Health status.
- Validation status.
- Last reload message.

Invalid candidates never replace the active revision.

---

## 6. Example model content

### 6.1 Retail domain

Objects:

- `customers`
- `orders`
- `products`

Metrics on `orders`:

- `total_revenue`
- `order_count`
- `unique_customers`
- `average_order_value`

Relationships:

- Orders → Customers, many-to-one.
- Orders → Products, many-to-one.

### 6.2 AI R&D domain

Object:

- `experiments`

Metric:

- `average_model_quality`

### 6.3 Example object YAML

```yaml
version: 1
kind: object
metadata:
  name: orders
  domain: retail
  label: Orders
  description: Customer orders
  owner: analytics-platform
  tags: [sales, finance]
spec:
  source:
    catalog: postgres
    schema: public
    table: orders
  primaryKey: [order_id]
  dimensions:
    - name: order_id
      label: Order ID
      type: bigint
      sql: order_id
      nullable: false
    - name: customer_id
      label: Customer ID
      type: bigint
      sql: customer_id
      nullable: false
    - name: amount
      label: Order amount
      type: decimal(18,2)
      sql: amount
    - name: status
      label: Status
      type: varchar
      sql: status
  relationships:
    - name: order_customer
      targetObject: customers
      sourceFields: [customer_id]
      targetFields: [customer_id]
      cardinality: many_to_one
      defaultJoinType: left
```

### 6.4 Example metric YAML

```yaml
version: 1
kind: metric
metadata:
  name: total_revenue
  domain: retail
  label: Total revenue
  description: Revenue from completed orders
  owner: finance-analytics
  tags: [revenue, finance]
spec:
  baseObject: orders
  aggregation: sum
  expression: amount
  resultType: decimal(18,2)
  format: currency
  filters:
    - field: status
      operator: in
      values: [paid, completed]
```

---

## 7. Governance and lifecycle rules

### 7.1 Read behavior

- Catalog, object, metric, relationship, graph, and SQL metadata views always represent the active model.
- Metadata is served from the in-memory active model without querying Trino.
- The UI should display the active revision and status where operational context is relevant.

### 7.2 Metric CRUD behavior

CRUD describes the user's available actions, but activation behavior depends on deployment mode.

#### Local/demo mode

- Create, update, and delete can persist validated YAML immediately.
- The complete candidate model is validated before writing.
- A successful operation reloads the active model.
- The UI may show a concise success confirmation and navigate to the resulting metric or registry.

#### GitLab/governed mode

- Create, update, and delete are proposed changes.
- “Delete” means propose deletion of the metric YAML; it does not immediately remove the active metric.
- The flow continues through validation, diff review, and MR submission.
- The active catalog remains unchanged until merge and reload.
- The UI must clearly distinguish “proposed” from “active.”

### 7.3 Other model changes

Object and relationship creation or editing use the governed multi-file change workflow in production.

### 7.4 Reload behavior

- Manual reload and polling read only the default branch.
- Valid new revision: becomes active.
- Invalid new revision: previous revision remains active and an error status is recorded.
- GitLab temporarily unavailable: previous revision remains active.

---

## 8. Required information architecture

The current top-level areas are:

1. **Catalog** — domains and semantic objects.
2. **Metrics** — metric registry grouped by domain.
3. **ER Diagram** — relationship graph.
4. **Model Editor / Changes** — form/YAML editing, validation, diff, and MR submission.

The design may recommend an additional **Model Status** or **Settings / Connect** surface if it materially improves discoverability, but it must not invent a large administration product.

Recommended hierarchy:

```text
Catalog
├── Domain grouping
└── Object details
    ├── Dimensions
    ├── Metrics
    └── Relationships

Metrics
├── Domain grouping
├── Metric details
├── Create metric
└── Edit metric

ER Diagram

Model Editor / Change Workspace
├── Files or affected entities
├── Form mode
├── YAML mode
├── Validation
├── Diff review
└── Submission result / MR link
```

---

## 9. Page-level requirements

### 9.1 Global application shell

Must provide:

- Persistent product identity.
- Primary navigation.
- Active navigation state.
- Compact active-model health indicator.
- Responsive behavior for narrower screens.
- A consistent place for page title, context, and primary action.

The shell should feel appropriate for a technical catalog: information-dense, calm, trustworthy, and not consumer-oriented.

### 9.2 Catalog page

Purpose: browse semantic objects by domain.

Required content:

- Page title and object count.
- Search across technical name, label, description, owner, and tags.
- Filters for domain, owner, and tags.
- Domain sections with object counts.
- Object cards or rows containing:
  - label and technical name;
  - short description;
  - owner;
  - tags;
  - dimension count;
  - metric count;
  - relationship count.
- Primary action to create/propose a new object.

Required behavior:

- Domain grouping remains understandable when there are many domains.
- Search and filters work together.
- Empty search and empty-model states are distinct.
- The selected grouping/filter state should be preserved while navigating back when practical.

### 9.3 Domain presentation

The domain is a grouping rather than a separately managed object in MVP.

The design should determine whether domain sections are:

- collapsible groups on catalog and metrics pages;
- tabs or filter chips;
- or a dedicated lightweight domain landing view.

At minimum, a domain presentation must show:

- domain SQL/schema name;
- object count;
- metric count;
- visible distinction from other domains.

### 9.4 Object details page

Required header:

- Object label.
- Technical identity `domain.object`.
- Description.
- Owner and tags.
- Edit/propose-change action.

Required sections:

1. **Overview**
   - Domain/schema.
   - Physical source `catalog.schema.table`.
   - Primary key.
   - Source YAML path.
2. **Dimensions**
   - Name and label.
   - Type.
   - SQL expression.
   - Nullable state.
   - Primary-key marker.
   - Optional description.
3. **Metrics**
   - Metric label/name.
   - Aggregation and expression.
   - Result type and format.
   - Link to metric details.
4. **Relationships**
   - Name.
   - Target object and target domain.
   - Field mapping.
   - Cardinality.
   - Default join type.
   - Link to related object.

The page should make business metadata and technical implementation both available without mixing them into one unreadable block.

### 9.5 Metrics registry

Purpose: browse and manage business measures.

Required content:

- Search across name, label, description, owner, tags, and base object.
- Filters for domain, base object, owner, tag, aggregation, and result type.
- Domain grouping.
- Metric count.
- Create metric action.
- Table or structured list with:
  - label and technical name;
  - base object;
  - aggregation/expression summary;
  - result type;
  - format;
  - owner;
  - tags.

Consider sorting by label, technical name, owner, and recently changed if revision metadata becomes available. “Recently changed” is not required for the current MVP.

### 9.6 Metric details page

Required header:

- Label.
- Technical identity and domain.
- Description.
- Owner and tags.
- Edit action.
- Delete/propose-deletion action.

Required sections:

1. **Definition**
   - Base object with link.
   - Aggregation.
   - Expression.
   - Result type.
   - Display format.
2. **Filters**
   - Human-readable filter presentation.
   - Technical operator and values.
3. **Dependencies / lineage**
   - Physical dimensions referenced by expression and filters.
   - Base object and its physical source.
4. **SQL usage**
   - Example direct semantic selection.
   - Optional BI-compatible aggregate example.
5. **Source**
   - YAML path.
   - Optional YAML definition view.

### 9.7 Create metric flow

Required fields:

- Technical name.
- Base object.
- Domain, derived from base object and not independently contradictory.
- Label.
- Description.
- Owner.
- Tags.
- Aggregation.
- Expression.
- Result type.
- Format.
- Zero or more filters.

Form behavior:

- Selecting a base object automatically sets its domain.
- Domain is visible but read-only when derived.
- Technical names validate as safe SQL identifiers.
- Expression validation should be available before submission.
- Filter rows can be added, reordered if necessary, and removed.
- Filter value input adapts to the operator.
- Form and YAML modes represent the same draft and preserve changes when switching.
- Advanced users can edit YAML directly.
- Unsaved-change protection is required.

Submission behavior:

- Local mode: validate → save → reload → metric details.
- Governed mode: validate → diff/review → metadata for change → create MR → result.

### 9.8 Edit metric flow

Uses the create form with existing values.

Additional requirements:

- Technical name is immutable in the basic edit flow; renaming is a separate delete/create change unless later supported explicitly.
- Show what changed before submission.
- Preserve existing filters.
- If the base object changes, domain must update and all expressions/filters must revalidate.
- Warn when the change may alter analytical meaning.

### 9.9 Delete metric flow

Deletion is potentially disruptive and requires a deliberate confirmation.

The confirmation must show:

- Metric label and technical name.
- Domain and base object.
- Whether deletion is immediate local behavior or a proposed governed change.
- Known dependencies or an explicit statement when dependency information is unavailable.
- Irreversibility wording appropriate to the mode.

Required action labels should be explicit, such as:

- “Delete metric” in local mode.
- “Propose metric deletion” in governed mode.

Do not rely on color alone to communicate the destructive action.

### 9.10 ER diagram

Required graph behavior:

- One node per semantic object.
- Relationship edges with cardinality.
- Pan, zoom, fit-to-view, and minimap or equivalent navigation.
- Search by object.
- Filter by domain and tags.
- Distinguish cross-domain edges.
- Selecting a node opens a concise detail panel or popover.
- A clear action navigates to object details.

Node minimum content:

- Domain.
- Object label/name.
- A concise subset of dimensions.
- Optional metric count.

The graph must remain usable at larger model sizes; filtered and focused views are more important than showing every field at once.

### 9.11 Model editor / change workspace

Purpose: create a governed multi-file change.

Required structure:

- Affected files/entities list.
- Form mode.
- YAML mode.
- Validation panel.
- Diff/review panel.
- Change metadata fields.
- Submission action.

Required change metadata:

- Change title / MR title.
- Commit message.
- MR description.
- Base revision.

Required behavior:

- Switching between form and YAML does not lose edits.
- Multiple related files can be changed in one change set.
- Validation is available without submitting.
- Diff is against the current default-branch revision.
- A stale base revision is detected before submission.
- Submit is disabled while validation contains errors.
- Warnings may allow submission but must be visible.
- The user is warned before navigating away with unsaved changes.

### 9.12 Validation and diff review

Validation errors contain:

- File.
- YAML field path.
- Error code.
- Severity.
- Human-readable message.

Design requirements:

- Group errors by file or entity.
- Make error-to-field navigation possible.
- Distinguish structural, semantic, and expression errors when useful.
- Provide a summary count by severity.
- Diff must distinguish additions, modifications, and deletions.
- Affected objects and metrics must be summarized separately from raw YAML diff.
- The current/default branch side and proposed side must be unambiguous.

### 9.13 Merge Request result

After successful submission show:

- Success status.
- Created branch.
- Commit SHA, shortened visually but copyable in full.
- Merge Request ID and direct URL.
- Reminder that the active model has not changed yet.
- Primary action to open the MR.
- Secondary action back to the catalog or change workspace.

### 9.14 Model status / operational state

At minimum, status must be available through a compact global indicator and a detailed presentation reachable from it or another appropriate location.

Detailed status should include:

- Active revision.
- Last check/reload time.
- Healthy/degraded/invalid state.
- Latest validation summary.
- Last message.
- Manual reload action for authorized users.
- Trino, model, and GitLab health where available.

Reload failure must clearly state that the previous valid model is still active.

### 9.15 SQL connection guidance

The product should make connection details discoverable without becoming a full SQL IDE.

Useful content:

- JDBC URL pattern.
- Username source/configuration note.
- SSL mode note for local MVP.
- Domain/schema examples.
- Example query.
- Copy actions.
- Tested client note: pgjdbc and DBeaver.

The default local connection is currently:

```text
jdbc:postgresql://localhost:55433/semantic?sslmode=disable
username: semantic
password: semantic
```

Credentials shown in product UI must never expose production secrets.

---

## 10. Search, filtering, and scale behavior

### 10.1 Search

Search should match:

- Technical name.
- Label.
- Description.
- Owner.
- Tags.
- Base object for metrics.

Search should be forgiving about case. Exact SQL identifier behavior remains case-sensitive only where PostgreSQL rules require it.

### 10.2 Filters

Catalog:

- Domain.
- Owner.
- Tags.

Metrics:

- Domain.
- Base object.
- Owner.
- Tags.
- Aggregation.
- Result type.

ER diagram:

- Domain.
- Object search.
- Tags.

### 10.3 Large-model considerations

The system targets catalog loading of approximately 1,000 objects within five seconds excluding GitLab network time.

Design should account for:

- Many domains.
- Hundreds of objects in one domain.
- Long technical names.
- Large dimension tables.
- Many validation errors.
- ER diagrams too dense to show unfiltered.

The design may recommend pagination, virtualization, collapsible domain groups, or incremental rendering. Specify where each is needed.

---

## 11. State model

Every major page or component must define the following applicable states.

### 11.1 Loading

- Initial catalog load.
- Filter/search update.
- Object or metric details load.
- Validation in progress.
- Model reload in progress.
- MR submission in progress.

Avoid layout jumps where skeletons or stable containers are practical.

### 11.2 Empty

- No model content.
- Domain with no matching objects.
- Metric registry with no metrics.
- No relationships.
- Metric with no filters.
- No search results.
- No validation issues.

Empty states should distinguish “nothing exists” from “filters hide all results.”

### 11.3 Success

- Metric created/updated/deleted locally.
- Candidate validated.
- Model reloaded.
- Merge Request created.

### 11.4 Warning

- Proposed change is not yet active.
- Deletion impact is unknown.
- Model health degraded while previous revision remains active.
- Validation contains non-blocking warnings.
- Direct CRUD unavailable in GitLab mode; governed flow will be used.

### 11.5 Error

- API/network failure.
- Entity not found.
- Invalid YAML.
- Structural validation failure.
- Semantic validation failure.
- Expression validation failure.
- Stale base revision / `409 Conflict`.
- GitLab branch, commit, or MR failure.
- Model reload failure.
- Trino unavailable.
- Unsupported SQL/client behavior.

Errors returned by the API include status, code, message, path, correlation ID, and timestamp. The UI should expose the correlation ID in technical details or a copyable error panel.

### 11.6 Permission/mode

- Read-only user.
- Local direct-edit mode.
- Governed GitLab mode.
- Direct CRUD unavailable.

The design should avoid presenting an enabled action that will inevitably fail due to mode or permission.

---

## 12. Validation rules visible to users

### Structural validation

- Valid YAML.
- Required fields.
- Correct field types.
- Supported schema version.
- Allowed enum values.
- Unique names.

### Semantic validation

- Target object exists.
- Relationship fields exist.
- Relationship field counts match.
- Relationship types are compatible.
- Metric base object exists.
- Metric fields and filter fields exist.
- Metric domain matches base-object domain.
- No metric/dimension name collision.
- SQL expressions are valid and allowed.
- No forbidden references or unsafe identifiers.
- No invalid dependency cycles.

Validation is blocking when severity is `ERROR`. Warnings remain visible but may not block submission.

---

## 13. SQL and metadata behavior relevant to design

### 13.1 PostgreSQL representation

- Database: `semantic`.
- Schemas: domains such as `retail` and `ai_rnd`.
- Tables: semantic objects.
- Columns: dimensions followed by metrics.
- Tables are read-only and virtual.
- Primary keys and imported relationships are exposed where supported.

### 13.2 Supported query concepts

- Read-only `SELECT`.
- Dimensions and metrics.
- Aliases.
- `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`.
- Selected scalar and aggregate functions.
- Explicit declared joins.
- Quoted identifiers.
- Prepared-statement parameters.

### 13.3 Unsupported concepts

- `INSERT`, `UPDATE`, `DELETE`, `MERGE`, or DDL.
- Arbitrary physical Trino tables.
- Multi-statement queries.
- Unsafe pass-through SQL.
- Full PostgreSQL system catalog.

### 13.4 Metric semantics

These are equivalent at the semantic level:

```sql
SELECT customer_id, total_revenue
FROM retail.orders
GROUP BY customer_id;
```

```sql
SELECT customer_id, SUM(total_revenue)
FROM retail.orders
GROUP BY customer_id;
```

The compiler recognizes the metric and prevents double aggregation.

The UI should describe metrics as governed aggregate columns, not as ordinary raw numeric columns.

---

## 14. REST capabilities relevant to UI

All endpoints are under `/api/v1` and require an API key or development authentication.

### Read APIs

```text
GET /objects
GET /objects/{name}
GET /metrics
GET /metrics/{name}
GET /relationships
GET /graph
GET /model/status
```

Object and metric list APIs support search/filter parameters including domain where applicable.

### Metric CRUD

```text
POST   /metrics
PUT    /metrics/{name}
DELETE /metrics/{name}
```

### Model lifecycle and governed changes

```text
POST /model/validate
POST /model/reload
POST /changes/validate
POST /changes/submit
```

Change validation returns:

- Validation result and errors.
- Diff.
- Base revision.
- Affected objects.
- Affected metrics.

Change submission returns:

- Branch.
- Commit SHA.
- Merge Request ID and URL.

---

## 15. Accessibility requirements

Target WCAG 2.1 AA behavior for the MVP.

Required:

- Full keyboard access to navigation, forms, tables, dialogs, and graph controls where feasible.
- Visible focus indicators.
- Semantic headings and landmarks.
- Proper labels and error associations for every field.
- Error summaries that link or move focus to invalid fields.
- Color contrast suitable for AA.
- Status and severity not communicated by color alone.
- Accessible names for icon-only actions.
- Confirmation dialogs with predictable focus management.
- Table headers associated with cells.
- Reduced-motion support for non-essential animation.
- ER diagram must have a usable non-graph alternative, such as a relationship list.

---

## 16. Responsive requirements

Primary target: desktop data-workbench usage at 1280 px and above.

Secondary target: functional tablet/narrow-laptop layouts.

Requirements:

- Navigation collapses without hiding core destinations.
- Two-column detail/editor layouts become one column.
- Wide dimension/metric tables may use horizontal scrolling with sticky identifiers.
- Primary actions remain reachable.
- Dialogs and forms remain usable at narrow widths.
- ER diagram may remain desktop-first but must not break the rest of the page.

Mobile phone optimization is not a primary MVP goal, but read-only catalog pages should remain legible.

---

## 17. Visual and interaction direction

The design should communicate:

- Trust and governance.
- Technical precision.
- Clear distinction between business labels and SQL identifiers.
- High information density without visual noise.
- Safe, deliberate change workflows.

Recommended principles:

- Use a neutral foundation with a restrained accent color.
- Render technical identifiers in monospace.
- Use labels/descriptions as the primary human-facing language.
- Use badges/chips for domains, tags, status, types, and cardinality.
- Keep destructive actions visually separate from primary save/submit actions.
- Use progressive disclosure for raw YAML, physical SQL, and operational details.
- Maintain consistent patterns between object and metric details.

Claude Design should propose a specific design system direction, spacing scale, typography hierarchy, color roles, table patterns, form patterns, status patterns, and graph styling.

---

## 18. Security and trust constraints visible in UX

- SQL is read-only.
- Production secrets never appear in YAML.
- GitLab tokens and Trino credentials must not be displayed or logged.
- Production changes cannot bypass Merge Requests.
- Unsafe identifiers and SQL expressions are rejected.
- SQL and change-set sizes are limited.
- Queries have timeout and row limits.
- API and SQL endpoints require authentication.

The UI should not imply that a proposed change is active before merge/reload.

---

## 19. Non-functional constraints affecting design

- Catalog of 1,000 objects should load within five seconds excluding GitLab network time.
- Metadata comes from memory and should feel immediate after initial load.
- At least 20 simultaneous SQL connections are expected in MVP.
- Every API error is structured and has a correlation ID.
- Health includes application, model, Trino, and GitLab-related state.
- Graceful shutdown and protocol internals do not need direct UI design.

---

## 20. Design acceptance criteria

The design is complete enough for engineering when it specifies:

1. How domains organize both Catalog and Metrics.
2. How users distinguish domain, object, dimension, metric, and relationship.
3. How business labels and technical identifiers coexist.
4. Complete create, edit, and delete metric flows in local and governed modes.
5. Complete object/relationship governed-edit flow.
6. Form and YAML synchronization behavior.
7. Validation, diff, stale-revision, and MR-result states.
8. Object and metric detail information hierarchy.
9. ER graph interactions plus accessible fallback.
10. Active versus proposed model communication.
11. Model health and reload-failure behavior.
12. Search/filter behavior at large scale.
13. Empty/loading/error/permission states for every primary page.
14. Responsive and accessibility behavior.
15. A reusable component inventory and visual system direction.

---

## 21. Questions Claude Design should explicitly answer

1. Should domains be collapsible sections, a persistent filter, tabs, or lightweight landing pages at MVP scale?
2. What is the clearest pattern for showing business label and SQL identifier together?
3. How should dimensions and metrics be visually differentiated when both become PostgreSQL columns?
4. What is the safest and least confusing metric deletion flow in local versus governed mode?
5. How should the UI communicate that an MR exists but the active model has not changed?
6. What should remain visible while switching between form, YAML, validation, and diff?
7. How should validation errors navigate users to fields across multiple files?
8. How should cross-domain relationships appear in catalog pages and the ER graph?
9. What information belongs in the global model-health indicator versus a detailed status view?
10. At what model size should cards become tables, groups collapse, or lists virtualize?

---

## 22. Current implementation reference

The current working MVP already includes:

- React catalog, object details, metrics, metric details, ER diagram, and model editor.
- Domain grouping for `retail` and `ai_rnd`.
- Metric create, edit, and delete screens.
- Local YAML persistence for metric CRUD.
- GitLab change-validation and submission APIs.
- PostgreSQL schemas and DBeaver table/column discovery.
- Trino-backed semantic SQL queries.
- Active-model health and reload behavior.

The requested design may refine or reorganize these screens, but it must preserve the product rules and end-to-end flows in this specification.
