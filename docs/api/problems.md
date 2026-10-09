# API problem types

Every error response from StockForge APIs uses [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with media type `application/problem+json`. The `type` field is a link to the matching section of this page.

```json
{
  "type": "https://github.com/madhawaawishka/StockForge/blob/main/docs/api/problems.md#product-not-found",
  "title": "Product not found",
  "status": 404,
  "detail": "Product 0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10 does not exist",
  "instance": "/api/v1/products/0192f5c4-7a1e-7c3b-9a55-2f1e4c8d9b10"
}
```

Clients should branch on `type` (stable) rather than on `title` or `detail` (human-readable, may change).

## validation-failed
**Status 400.** One or more fields or parameters are invalid. The `errors` array lists each violation:
```json
"errors": [
  {"field": "sku", "message": "must be 3-64 characters of A-Z, 0-9 or '-', starting with a letter or digit"},
  {"field": "price.amount", "message": "must be a decimal string such as 19.99"}
]
```
Some domain rules cannot be expressed as field constraints (for example, more decimal places than the currency allows); those return this type with an explanatory `detail` and no `errors` array.
**Fix:** correct the listed fields and retry.

## malformed-request
**Status 400.** The body is missing, is not valid JSON, has a value of the wrong type, or contains a field the endpoint does not accept. Unknown fields are rejected rather than ignored so that typos and mass-assignment attempts fail loudly.
**Fix:** send exactly the documented fields (see the OpenAPI document at `/v3/api-docs`).

## invalid-parameter
**Status 400.** A path or query parameter has the wrong format, e.g. a product ID that is not a UUID.
**Fix:** check the parameter named in `detail`.

## invalid-cursor
**Status 400.** The `cursor` query parameter was not produced by this API. Cursors are opaque.
**Fix:** omit `cursor` to start from the first page, or pass the `nextCursor` value from the previous page unchanged.

## product-not-found
**Status 404.** No product exists with the given ID.
**Fix:** check the ID; list products with `GET /api/v1/products`.

## sku-already-exists
**Status 409.** Another product already uses this SKU. SKUs are unique; concurrent attempts to create the same SKU result in exactly one product.
**Fix:** use a different SKU, or fetch the existing product.

## internal-error
**Status 500.** An unexpected server error. Details are logged server-side and deliberately not returned.
**Fix:** retry later; if it persists, report the time of the request and the `instance` value.
