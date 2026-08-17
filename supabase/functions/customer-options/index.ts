// =============================================================================
// GET /customer-options
// GET /customer-options?province_id=…   -> that province's districts
// GET /customer-options?district_id=…   -> that district's wards
//
// The lists the registration form is filled in from.
//
// Split three ways rather than returned whole because of what these tables look
// like in production rather than in the seed: a country's wards run to five
// figures, and shipping all of them to fill one dropdown would be the largest
// response in the app by an order of magnitude. Classes, channels, shop types
// and provinces are small and bounded, so they come together in one call.
//
// None of this is cached on the device. It is read once when a form opens, which
// happens a handful of times a week, and a stale ward list is worse than a
// second's wait — an outlet registered against a ward that has since been
// renumbered lands in the wrong territory.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

const NAMED = "id,code,name";

Deno.serve(handler(async (req, db) => {
  if (req.method !== "GET") throw new HttpError(405, "GET only");

  const params = new URL(req.url).searchParams;
  const provinceId = params.get("province_id");
  const districtId = params.get("district_id");

  if (provinceId && districtId) {
    throw new HttpError(400, "ask for districts or wards, not both");
  }

  if (provinceId) {
    return json({
      districts: unwrap(
        await db.from("district").select(NAMED).eq("province_id", provinceId).order("name"),
      ),
    });
  }

  if (districtId) {
    return json({
      wards: unwrap(
        await db.from("ward").select(NAMED).eq("district_id", districtId).order("name"),
      ),
    });
  }

  const [classes, channels, shopTypes, provinces] = await Promise.all([
    db.from("customer_class").select(NAMED).order("name").then((r) => unwrap(r)),
    db.from("sales_channel").select(NAMED).order("name").then((r) => unwrap(r)),
    db.from("shop_type").select(NAMED).order("name").then((r) => unwrap(r)),
    db.from("province").select(NAMED).order("name").then((r) => unwrap(r)),
  ]);

  return json({ classes, channels, shop_types: shopTypes, provinces });
}));
