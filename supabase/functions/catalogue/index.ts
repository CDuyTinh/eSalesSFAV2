// =============================================================================
// GET /catalogue
//
// The product catalogue with each product's sale units nested inside it, plus the
// price rules that apply to the caller.
//
// Replaces three unbounded client reads — product, product_uom, price_list — and
// the grouping the client did afterwards. Two things change beyond the round trip
// count.
//
// Paging is handled here. Those three reads had no limit, so on a real
// distributor's catalogue they would have hit PostgREST's row cap and arrived
// silently truncated: the app would run on a partial product list with nothing to
// say so. `readAll` pages until a short page proves the end, and every query it
// pages carries an explicit order — without one, paging can skip and repeat rows.
//
// Prices stay as rules rather than being resolved to one number per product. A
// route holds customers in different classes, so the price only exists relative
// to whichever outlet the rep is standing in; resolving it here would mean
// re-fetching the catalogue per customer.
//
// Must-stock lists ride along for the same reason and resolve the same way: they
// are scoped by channel and shop type, so which ones apply depends on the outlet.
// Shipping them with the catalogue means the stock screen can mark required SKUs
// with no signal, which is the situation it was built for.
// =============================================================================

import { handler, json, readAll } from "../_shared/client.ts";

const PRODUCT_COLUMNS =
  "id,code,name,base_uom,vat_basis_points,category:category_id(name,sort_order)";

const PRODUCT_UOM_COLUMNS =
  "product_id,uom_code,conversion_rate,is_default_sale,sort_order,uom:uom_code(name)";

const PRICE_COLUMNS = "product_id,uom_code,class_id,price,from_date,to_date";

interface ProductRow {
  id: string;
  code: string;
  name: string;
  base_uom: string;
  vat_basis_points: number;
  category: { name: string; sort_order: number } | null;
}

interface UomRow {
  product_id: string;
  uom_code: string;
  conversion_rate: number;
  is_default_sale: boolean;
  sort_order: number;
  uom: { name: string } | null;
}

const MSL_COLUMNS =
  "id,code,channel_id,shop_type_id,from_date,to_date," +
  "items:msl_item(product_id,min_base_qty)";

Deno.serve(handler(async (_req, db) => {
  const [products, units, priceRules, msl] = await Promise.all([
    readAll<ProductRow>((from, to) =>
      db.from("product")
        .select(PRODUCT_COLUMNS)
        .eq("is_active", true)
        .order("code")
        .range(from, to)
    ),
    readAll<UomRow>((from, to) =>
      db.from("product_uom")
        .select(PRODUCT_UOM_COLUMNS)
        .order("product_id")
        .order("sort_order")
        .range(from, to)
    ),
    readAll((from, to) =>
      db.from("price_list")
        .select(PRICE_COLUMNS)
        .order("product_id")
        .order("uom_code")
        .range(from, to)
    ),
    readAll((from, to) =>
      db.from("msl")
        .select(MSL_COLUMNS)
        .eq("is_active", true)
        .order("code")
        .range(from, to)
    ),
  ]);

  const unitsByProduct = new Map<string, UomRow[]>();
  for (const unit of units) {
    const list = unitsByProduct.get(unit.product_id);
    if (list) list.push(unit);
    else unitsByProduct.set(unit.product_id, [unit]);
  }

  return json({
    generated_at: new Date().toISOString(),
    products: products.map((p) => ({
      id: p.id,
      code: p.code,
      name: p.name,
      base_uom: p.base_uom,
      vat_basis_points: p.vat_basis_points,
      category_name: p.category?.name ?? null,
      // Products with no category sort last rather than first, so an
      // uncategorised item does not lead the catalogue.
      category_sort: p.category?.sort_order ?? 9999,
      units: (unitsByProduct.get(p.id) ?? []).map((u) => ({
        uom_code: u.uom_code,
        uom_name: u.uom?.name ?? u.uom_code,
        conversion_rate: u.conversion_rate,
        is_default_sale: u.is_default_sale,
        sort_order: u.sort_order,
      })),
    })),
    price_rules: priceRules,
    // Unresolved, like the prices: the client applies the outlet's channel and
    // shop type, unions the matching lists and keeps the strictest par level.
    msl: msl,
  });
}));
