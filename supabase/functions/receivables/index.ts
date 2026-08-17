// =============================================================================
// GET  /receivables                    -> outlets that still owe something
// GET  /receivables?customer_id=…      -> that outlet's open invoices
// POST /receivables                    -> record collections
//
// Reading is two SQL functions; writing is a batch insert. They share an
// endpoint because they are one screen's worth of traffic and the write is four
// lines.
//
// The batch is what the rep actually does: they stand at the counter, are handed
// an amount, and put it against two or three invoices at once. Sending them one
// at a time would let the second fail after the first succeeded, leaving a
// receipt that is half recorded and no way for the rep to tell which half.
// =============================================================================

import { handler, HttpError, json, unwrap } from "../_shared/client.ts";

interface Allocation {
  id?: string;
  invoice_id?: string;
  amount?: number;
}

interface Body {
  visit_id?: string | null;
  collected_on?: string;
  note?: string | null;
  allocations?: Allocation[];
}

Deno.serve(handler(async (req, db) => {
  if (req.method === "GET") {
    const customerId = new URL(req.url).searchParams.get("customer_id");

    if (customerId) {
      return json({
        invoices: unwrap(
          await db.rpc("receivable_invoices", { p_customer_id: customerId }),
        ),
      });
    }

    return json({ customers: unwrap(await db.rpc("receivable_customers")) });
  }

  if (req.method !== "POST") throw new HttpError(405, "GET or POST only");

  const body = await req.json() as Body;
  const allocations = body.allocations ?? [];

  if (allocations.length === 0) {
    throw new HttpError(400, "chưa nhập số tiền thu");
  }
  if (!body.collected_on) throw new HttpError(400, "collected_on is required");

  for (const a of allocations) {
    if (!a.id) throw new HttpError(400, "each allocation needs a client-supplied id");
    if (!a.invoice_id) throw new HttpError(400, "each allocation needs an invoice_id");
    if (!a.amount || a.amount <= 0) {
      throw new HttpError(400, "số tiền thu phải lớn hơn 0");
    }
  }

  const me = (unwrap(
    await db.from("salesperson").select("id"),
  ) as { id: string }[])[0];

  if (!me) throw new HttpError(403, "no salesperson is linked to this account");

  // One statement, so the batch is one transaction: either every allocation
  // lands or none does. The overpayment trigger fires per row, and its message
  // names the invoice's remaining balance — which is what the rep needs to
  // correct the figure they were about to record.
  const inserted = await db.from("ar_payment").insert(
    allocations.map((a) => ({
      id: a.id,
      invoice_id: a.invoice_id,
      salesperson_id: me.id,
      visit_id: body.visit_id ?? null,
      amount: a.amount,
      collected_on: body.collected_on,
      note: body.note ?? null,
    })),
  ).select("id");

  // A replayed save. The ids are the client's, so the same batch arriving twice
  // is the same rows — already recorded is a success, not a failure.
  if (inserted.error?.code === "23505") {
    return json({ ok: true, replayed: true });
  }
  unwrap(inserted);

  return json({ ok: true, count: allocations.length });
}));
