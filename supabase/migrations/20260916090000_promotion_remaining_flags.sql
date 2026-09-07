-- =============================================================================
-- Những cờ còn lại
--
-- The last of OM_DiscSeq's rule flags that anything actually evaluates, plus a
-- correctness fix to the required-items check that shipped in 20260910090000.
--
-- THE FIX FIRST. A required item the basket does not contain at all must kill
-- the rule. `min(floor(...))` cannot say that: an absent item contributes no
-- row, so a minimum over what is present let a rule requiring A and B fire on A
-- alone. OM10100 checks presence separately and sets `isPromotion = false`, and
-- so does this now. A rule saying "buy five of A and three of B" was paying out
-- on five of A and no B, which is the kind of bug that only shows up in the
-- shape of the master data nobody seeded yet.
--
-- RequiredType 'R' (`any_n`) is the fourth mode and reads differently from the
-- other three: the per-item values are ignored and what counts is how many
-- distinct products off the list the basket contains — "buy any three of these
-- ten". `RequiredNumber` is the three.
--
-- OnlyMaxLineRef: the rule pays once per order and then stops, whatever else its
-- ladder would have offered. Distinct from a bounded level, which is about how
-- many lots one level is worth.
--
-- ExactQty: the level has to be hit on the nose. Eleven against a level of ten
-- is not a level of ten with one left over, it is nothing —
-- `GetBundleDiscBreak` selects on `BreakAmt == bundleNbr` rather than `<=`.
--
-- DonateGroupProduct: the gift list stops meaning "one of each" and becomes a
-- pool shared evenly between the items. OM10100 sums the quantities and divides
-- by the count, so a rule offering 2 A and 1 B gives 1.5 of each; floored here,
-- because half a case is not something a van carries.
--
-- ConvertDiscAmtToFreeItem: a rule written as money and paid in goods. The
-- discount buys units of the gift at a price the campaign sets — `maxLot =
-- promoAmt / free.PromoPrice` — capped by what the depot has, and the money then
-- stops being money.
--
-- NOT IMPLEMENTED, AND NOT BECAUSE THEY WERE SKIPPED. `ProrateAmtType`,
-- `ChoiceType` and `IsRequiredQty` are evaluated nowhere: not in CalcPromo.cs,
-- not in the 1707-proc dump, and not in OM10100 either — zero occurrences in the
-- engine that books the orders. They are master-data columns the admin screen
-- maintains and nothing reads. Adding behaviour for them would mean inventing
-- it.
--
-- Also not here: the combo classes BB, B1 and CB, which run through
-- `CalculateGroupDiscBundle` — a bundle mechanism of its own, roughly a thousand
-- lines, and a feature rather than a flag.
-- =============================================================================

alter type discount_required_type add value if not exists 'any_n';

alter table discount_sequence
    add column if not exists required_number integer not null default 0
        constraint discount_sequence_required_number_not_negative
        check (required_number >= 0),
    add column if not exists only_once boolean not null default false,
    add column if not exists exact_qty boolean not null default false,
    add column if not exists donate_group boolean not null default false,
    add column if not exists convert_amount_to_goods boolean not null default false;

-- The price the campaign values a gift at, when a money reward is paid in goods.
-- Zero means this item cannot be used that way.
alter table discount_free_item
    add column if not exists promo_price bigint not null default 0
        constraint discount_free_item_promo_price_not_negative
        check (promo_price >= 0);
