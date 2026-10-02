# CityCopilot shop search library

`search` is a non-executable Maven library that keeps Elasticsearch behind the
`ShopSearchGateway` boundary. MySQL/Core remains the source of truth.

## Configuration

The library is disabled by default:

```yaml
citycopilot:
  search:
    enabled: false
    index-alias: citycopilot-shop-search
    index-prefix: citycopilot-shop
    max-page-size: 500
    bulk-batch-size: 500
```

- Disabled mode always exposes a gateway whose searches return
  `SEARCH_DISABLED`.
- Enabled mode uses Spring Boot's managed official `ElasticsearchClient`.
- If enabled mode has no client, a gateway remains available but returns
  `SEARCH_UNAVAILABLE`, so optional search cannot prevent Core from starting.

## Projection lifecycle

`upsert` writes through the alias. A failed incremental write marks this
process's projection state stale, and searches return `INDEX_STALE` until a
successful upsert or rebuild clears the local flag.

`rebuild` creates a new generation index, bulk-indexes and refreshes all
documents, then switches the read/write alias in one Elasticsearch alias
update. Core records retired generations for explicit cleanup, which is disabled
by default and requires retention and reader-grace windows; see the
[projection lifecycle policy](../../docs/adr/0010-versioned-shop-search-geo-projections.md).

The mapping uses only built-in Elasticsearch analyzers. Its n-gram fields make
Chinese shop names searchable without an IK plugin; standard multi-fields
retain normal token matching for Latin text.
